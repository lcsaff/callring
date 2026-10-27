package com.greatwall.callring.platform.update

import com.greatwall.callring.contracts.runtime.*
import android.content.Context
import com.greatwall.callring.contracts.common.ApiError
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.runtime.AppReleaseInfo
import com.greatwall.callring.contracts.runtime.AppUpdateApi
import com.greatwall.callring.contracts.common.ErrorCode
import com.greatwall.callring.contracts.runtime.UpdatePhase
import com.greatwall.callring.contracts.runtime.UpdateStatus
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 应用内更新的平台实现：GitHub 检查（UA + ETag/304）、流式下载、SHA-256/签名校验、
 * FileProvider 安装与状态持久化。网络、系统包管理与安装器都可注入，测试不触网、不安装。
 */
internal class AndroidAppUpdate(
    private val context: Context,
    private val build: () -> UpdateBuildInfo = { defaultUpdateBuildInfo(context) },
    private val directory: () -> File? = { context.getExternalFilesDir("Download/update") },
    private val persistence: UpdatePersistence = SharedPreferencesUpdatePersistence.forContext(context),
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val apkInspector: ApkInspector = AndroidApkInspector(context.packageManager),
    private val installedCertificate: InstalledCertificateReader =
        AndroidInstalledCertificate(context.packageManager, context.packageName),
    private val installer: UpdateInstaller = AndroidUpdateInstaller(context),
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val downloadReadTimeoutMs: Int = DOWNLOAD_READ_TIMEOUT_MS,
) : AppUpdateApi, AutoCloseable {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + io)
    private val client by lazy {
        GitHubReleaseClient(openConnection, UPDATE_USER_AGENT_PREFIX + build().current.versionName)
    }
    @Volatile private var downloadJob: Job? = null
    @Volatile private var cancelRequested = false
    @Volatile private var activeConnection: HttpURLConnection? = null
    private var checkGeneration = 0L
    @Volatile private var closed = false

    override suspend fun status(): ApiResult<UpdateStatus> = attempt { synchronized(lock) { statusInternal() } }

    override suspend fun check(): ApiResult<UpdateStatus> = attempt {
        val info = build()
        if (!info.updatable) {
            synchronized(lock) {
                persistence.write(encodeUpdateState(PersistedUpdateState(
                    phase = UpdatePhase.UNSUPPORTED_DEVICE,
                    failureReason = UpdateReasons.UNSUPPORTED_BUILD,
                    checkedAtEpochMillis = clock(),
                )))
                statusInternal()
            }
        } else {
            val (generation, previous) = synchronized(lock) {
                val status = statusInternal()
                if (status.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.VERIFIED,
                        UpdatePhase.INSTALL_PERMISSION_REQUIRED, UpdatePhase.INSTALL_PENDING_VERIFY)) return@attempt status
                ++checkGeneration to persistence.read()
            }
            val outcome = withContext(io) { fetchCheck(decodeUpdateState(previous)) }
            synchronized(lock) {
                // A newer check or download/install transition owns the state now.
                if (!closed && generation == checkGeneration && persistence.read() == previous) {
                    val old = decodeUpdateState(previous)
                    val same = old?.latest?.let { a -> outcome.latest?.let { b -> sameDownload(a, b) } } == true
                    val bytes = old?.let(::partialBytes) ?: 0L
                    val saved = when {
                        bytes > 0 && outcome.phase == UpdatePhase.CHECK_FAILED -> outcome.copy(
                            phase = UpdatePhase.VERIFY_FAILED, latest = old?.latest, fileName = old?.fileName,
                            downloadedBytes = bytes, totalBytes = old?.latest?.sizeBytes)
                        same && bytes > 0 -> outcome.copy(fileName = old.fileName, downloadedBytes = bytes, totalBytes = old.latest.sizeBytes)
                        else -> { directory()?.let(::deleteUpdateFiles); outcome }
                    }
                    persistence.write(encodeUpdateState(saved))
                }
                statusInternal()
            }
        }
    }

    override suspend fun download(): ApiResult<UpdateStatus> = attempt {
        synchronized(lock) {
            val plan = prepareDownload()
            if (plan != null) launchDownload(plan)
            statusInternal()
        }
    }

    override suspend fun cancel(): ApiResult<UpdateStatus> = attempt {
        cancelRequested = true
        runCatching { activeConnection?.disconnect() }
        val job = downloadJob
        if (job != null) withTimeoutOrNull(5_000) { job.cancel(); job.join() }
        synchronized(lock) {
            if (job?.isCompleted != false) {
                downloadJob = null
                directory()?.let(::deleteUpdateFiles)
            }
            val current = decodeUpdateState(persistence.read()) ?: PersistedUpdateState()
            val latest = current.latest
            persistence.write(encodeUpdateState(current.copy(
                phase = UpdatePhase.UPDATE_AVAILABLE,
                failureReason = if (latest == null) UpdateReasons.CHECK_FAILED else null,
                detail = null,
                downloadedBytes = 0,
                totalBytes = latest?.sizeBytes,
                fileName = null,
                verified = false,
            )))
            statusInternal()
        }
    }

    override suspend fun install(): ApiResult<UpdateStatus> = attempt {
        val status = synchronized(lock) { statusInternal() }
        val latest = status.latest ?: return@attempt status
        if (!installer.canInstall()) {
            installer.openInstallPermission()
            return@attempt synchronized(lock) { persistPermissionRequired() }
        }
        val state = synchronized(lock) { decodeUpdateState(persistence.read()) }
        val directory = directory()
        val file = state?.fileName?.let { name -> directory?.let { dir -> confinedFile(dir, name) } }
        if (file == null || !file.isFile || status.phase !in setOf(UpdatePhase.VERIFIED, UpdatePhase.INSTALL_PERMISSION_REQUIRED)) {
            return@attempt synchronized(lock) { persistVerifyFailed(status, "no_verified_file") }
        }
        val failure = withContext(io) {
            val info = build()
            verifyUpdateFile(file, latest, info.packageName, info.deviceSdk, apkInspector, installedCertificate)
        }
        if (failure != null) {
            return@attempt synchronized(lock) {
                file.delete()
                persistVerifyFailed(status, failure)
            }
        }
        if (!runCatching { installer.launch(file) }.getOrDefault(false)) {
            throw UpdateApiException(UpdateReasons.INSTALL_FAILED)
        }
        synchronized(lock) {
            val current = decodeUpdateState(persistence.read()) ?: PersistedUpdateState()
            persistence.write(encodeUpdateState(current.copy(phase = UpdatePhase.INSTALL_PENDING_VERIFY, failureReason = null, detail = null)))
            statusInternal()
        }
    }

    override fun close() {
        synchronized(lock) { closed = true; checkGeneration++ }
        runCatching { activeConnection?.disconnect() }
        downloadJob?.cancel()
        scope.cancel()
    }

    private data class DownloadPlan(
        val latest: AppReleaseInfo,
        val part: File,
        val target: File,
        val directory: File,
    )

    private class UpdateApiException(val key: String) : Exception(key)

    private fun prepareDownload(): DownloadPlan? {
        val status = statusInternal()
        val latest = status.latest ?: return null
        if (downloadJob?.isCompleted == false) return null
        if (status.phase == UpdatePhase.VERIFIED || status.phase == UpdatePhase.INSTALL_PENDING_VERIFY) return null
        if (status.phase !in setOf(UpdatePhase.UPDATE_AVAILABLE, UpdatePhase.VERIFY_FAILED)) return null
        val directory = directory() ?: run {
            persistence.write(encodeUpdateState(currentState().copy(
                phase = UpdatePhase.VERIFY_FAILED,
                failureReason = UpdateReasons.DOWNLOAD_FAILED,
                detail = "external_storage_unavailable",
            )))
            return null
        }
        if (!directory.isDirectory && !directory.mkdirs()) {
            persistence.write(encodeUpdateState(currentState().copy(
                phase = UpdatePhase.VERIFY_FAILED,
                failureReason = UpdateReasons.DOWNLOAD_FAILED,
                detail = "directory_unavailable",
            )))
            return null
        }
        cancelRequested = false
        val part = confinedFile(directory, latest.assetName + ".part") ?: return null
        val target = confinedFile(directory, latest.assetName) ?: return null
        if (currentState().fileName != latest.assetName || part.length() > latest.sizeBytes) part.delete()
        target.delete()
        persistence.write(encodeUpdateState(currentState().copy(
            phase = UpdatePhase.DOWNLOADING,
            failureReason = null,
            detail = null,
            downloadedBytes = part.length(),
            totalBytes = latest.sizeBytes,
            fileName = target.name,
            verified = false,
        )))
        return DownloadPlan(latest, part, target, directory)
    }

    private fun launchDownload(plan: DownloadPlan) {
        downloadJob = scope.launch {
            try {
                downloadToPart(plan)
                persist(phase = UpdatePhase.VERIFYING)
                val failure = verifyUpdateFile(plan.target, plan.latest, build().packageName, build().deviceSdk, apkInspector, installedCertificate)
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (closed || cancelRequested) throw CancellationException("update.cancelled")
                    if (failure != null) {
                        plan.target.delete()
                        persist(phase = UpdatePhase.VERIFY_FAILED, reason = failure, detail = "install_verification")
                    } else {
                        persist(phase = UpdatePhase.VERIFIED, reason = null, detail = null, verified = true)
                    }
                }
            } catch (_: CancellationException) {
                synchronized(lock) {
                    if (!closed && cancelRequested) { plan.part.delete(); plan.target.delete() }
                }
                throw CancellationException("update.cancelled")
            } catch (failure: UpdateDownloadFailure) {
                synchronized(lock) {
                    if (closed) return@launch
                    if (failure.discardPartial || cancelRequested) plan.part.delete()
                    plan.target.delete()
                    persistDownloadFailure(plan, failure.reason, failure.detail)
                }
            } catch (_: Exception) {
                synchronized(lock) {
                    if (closed) return@launch
                    if (cancelRequested) plan.part.delete()
                    plan.target.delete()
                    persistDownloadFailure(plan, UpdateReasons.DOWNLOAD_FAILED, "interrupted")
                }
            } finally {
                activeConnection = null
            }
        }
    }

    private suspend fun downloadToPart(plan: DownloadPlan) {
        downloadUpdatePart(plan.latest, plan.part,
            UPDATE_USER_AGENT_PREFIX + build().current.versionName, downloadReadTimeoutMs, openConnection,
            connectionChanged = { connection -> synchronized(lock) {
                if (connection != null && (closed || cancelRequested)) {
                    connection.disconnect()
                    throw CancellationException("update.cancelled")
                }
                activeConnection = connection
            } },
            progress = { bytes -> synchronized(lock) {
                if (!closed && !cancelRequested) persistProgress(bytes, plan.latest.sizeBytes)
            } },
            commitFile = { synchronized(lock) {
                if (closed || cancelRequested) throw CancellationException("update.cancelled")
                if (plan.target.exists() && !plan.target.delete()) throw IOException("replace_failed")
                if (!plan.part.renameTo(plan.target)) throw IOException("rename_failed")
            } })
        currentCoroutineContext().ensureActive()
    }

    private fun persistDownloadFailure(plan: DownloadPlan, reason: String, detail: String) {
        synchronized(lock) {
            if (closed || cancelRequested) return
            if (plan.part.length() == 0L) plan.part.delete()
            val bytes = plan.part.length()
            persistence.write(encodeUpdateState(currentState().copy(
                phase = UpdatePhase.VERIFY_FAILED, failureReason = reason, detail = detail,
                downloadedBytes = bytes, totalBytes = plan.latest.sizeBytes,
                fileName = plan.target.name.takeIf { bytes > 0 }, verified = false,
            )))
        }
    }

    private fun sameDownload(a: AppReleaseInfo, b: AppReleaseInfo): Boolean =
        a.versionCode == b.versionCode && a.assetName == b.assetName && a.sizeBytes == b.sizeBytes &&
            a.sha256.equals(b.sha256, ignoreCase = true)

    private fun partialBytes(state: PersistedUpdateState): Long {
        val latest = state.latest ?: return 0
        if (state.fileName != latest.assetName) return 0
        val part = directory()?.let { confinedFile(it, latest.assetName + ".part") } ?: return 0
        return if (part.isFile && part.length() in 1..latest.sizeBytes) part.length() else 0
    }

    private fun fetchCheck(previous: PersistedUpdateState?): PersistedUpdateState {
        // A 304 is usable only when the corresponding installable metadata still exists.
        // Failed checks and up-to-date results retain no metadata, so fetch them afresh.
        val cachedEtag = previous?.etag?.takeIf { previous.latest != null }
        return when (val latest = client.fetchLatest(cachedEtag)) {
            is LatestFetch.Failed -> PersistedUpdateState(
                etag = previous?.etag,
                phase = UpdatePhase.CHECK_FAILED,
                failureReason = UpdateReasons.CHECK_FAILED,
                detail = latest.detail,
                checkedAtEpochMillis = clock(),
            )
            is LatestFetch.NotModified -> previous?.takeIf { it.latest != null }?.copy(checkedAtEpochMillis = clock())
                ?: PersistedUpdateState(
                    etag = latest.etag,
                    phase = UpdatePhase.CHECK_FAILED,
                    failureReason = UpdateReasons.CHECK_FAILED,
                    detail = "cache_missing",
                    checkedAtEpochMillis = clock(),
                )
            is LatestFetch.Fetched -> when (val manifest = client.fetchManifest(latest.release)) {
                is ManifestFetch.Failed -> PersistedUpdateState(
                    etag = latest.etag,
                    phase = if (manifest.detail == "manifest_missing" || manifest.detail.startsWith("manifest_invalid")) UpdatePhase.NO_INSTALLABLE else UpdatePhase.CHECK_FAILED,
                    failureReason = if (manifest.detail == "manifest_missing" || manifest.detail.startsWith("manifest_invalid")) UpdateReasons.NO_INSTALLABLE else UpdateReasons.CHECK_FAILED,
                    detail = manifest.detail,
                    checkedAtEpochMillis = clock(),
                )
                is ManifestFetch.Fetched -> {
                    val info = build()
                    val evaluation = evaluateCheck(
                        latest.release,
                        manifest.manifest,
                        InstalledApp(info.packageName, info.current.versionName, info.current.versionCode),
                        info.deviceSdk,
                    )
                    PersistedUpdateState(
                        etag = latest.etag,
                        phase = evaluation.phase,
                        failureReason = evaluation.failureReason,
                        checkedAtEpochMillis = clock(),
                        latest = evaluation.latest,
                        totalBytes = evaluation.latest?.sizeBytes,
                    )
                }
            }
        }
    }

    private fun statusInternal(): UpdateStatus {
        check(!closed) { "update.closed" }
        val info = build()
        var state = decodeUpdateState(persistence.read()) ?: PersistedUpdateState()
        val latest = state.latest
        val installed = latest != null && info.current.versionCode >= latest.versionCode
        if (installed) {
            if (state.phase != UpdatePhase.UP_TO_DATE) {
                directory()?.let(::deleteUpdateFiles)
                state = PersistedUpdateState(etag = state.etag, phase = UpdatePhase.UP_TO_DATE, checkedAtEpochMillis = state.checkedAtEpochMillis)
                persistence.write(encodeUpdateState(state))
            }
            return statusFrom(state, info)
        }
        if (downloadJob?.isCompleted != false && (state.phase == UpdatePhase.DOWNLOADING || state.phase == UpdatePhase.VERIFYING)) {
            if (latest != null && state.fileName == latest.assetName) {
                directory()?.let { dir ->
                    val target = confinedFile(dir, latest.assetName)
                    val part = confinedFile(dir, latest.assetName + ".part")
                    if (target?.isFile == true && target.length() == latest.sizeBytes && part != null && !part.exists()) target.renameTo(part)
                }
            }
            val bytes = partialBytes(state)
            state = if (latest != null) state.copy(
                phase = UpdatePhase.UPDATE_AVAILABLE,
                failureReason = UpdateReasons.DOWNLOAD_FAILED,
                detail = "interrupted",
                downloadedBytes = bytes,
                totalBytes = latest.sizeBytes,
                fileName = latest.assetName.takeIf { bytes > 0 },
                verified = false,
            ) else state.copy(phase = UpdatePhase.CHECK_FAILED, failureReason = UpdateReasons.CHECK_FAILED, downloadedBytes = 0, fileName = null)
            persistence.write(encodeUpdateState(state))
        }
        if (downloadJob?.isCompleted != false && state.phase in setOf(UpdatePhase.UPDATE_AVAILABLE, UpdatePhase.VERIFY_FAILED)) {
            val bytes = partialBytes(state)
            val fileName = latest?.assetName?.takeIf { bytes > 0 }
            if (bytes != state.downloadedBytes || fileName != state.fileName) {
                state = state.copy(downloadedBytes = bytes, fileName = fileName, verified = false)
                persistence.write(encodeUpdateState(state))
            }
        }
        if (state.phase == UpdatePhase.VERIFIED) {
            val directory = directory()
            val file = state.fileName?.let { name -> directory?.let { confinedFile(it, name) } }
            if (latest == null || file == null || !file.isFile || file.length() != latest.sizeBytes) {
                file?.delete()
                state = state.copy(phase = UpdatePhase.VERIFY_FAILED, failureReason = UpdateReasons.VERIFY_FAILED, detail = "file_missing", verified = false, fileName = null)
                persistence.write(encodeUpdateState(state))
            }
        } else if (state.phase in setOf(UpdatePhase.UP_TO_DATE, UpdatePhase.NO_INSTALLABLE, UpdatePhase.CHECK_FAILED) && state.fileName != null) {
            val file = directory()?.let { confinedFile(it, state.fileName) }
            file?.delete()
            state = state.copy(fileName = null, verified = false, downloadedBytes = 0, totalBytes = null)
            persistence.write(encodeUpdateState(state))
        }
        return statusFrom(state, info)
    }

    private fun statusFrom(state: PersistedUpdateState, info: UpdateBuildInfo) = UpdateStatus(
        current = info.current,
        phase = state.phase,
        latest = state.latest,
        downloadedBytes = state.downloadedBytes,
        totalBytes = state.totalBytes,
        failureReason = state.failureReason,
        failureDetail = state.detail,
        checkedAtEpochMillis = state.checkedAtEpochMillis,
    )

    private fun currentState(): PersistedUpdateState = decodeUpdateState(persistence.read()) ?: PersistedUpdateState()

    private fun persist(
        phase: UpdatePhase,
        reason: String? = null,
        detail: String? = null,
        verified: Boolean = false,
    ) {
        synchronized(lock) {
            if (closed || cancelRequested) return
            val current = currentState()
            persistence.write(encodeUpdateState(current.copy(
                phase = phase,
                failureReason = reason,
                detail = detail,
                verified = verified,
                fileName = if (phase == UpdatePhase.VERIFIED || phase == UpdatePhase.VERIFYING || phase == UpdatePhase.DOWNLOADING) current.fileName else null,
            )))
        }
    }

    private fun persistProgress(downloaded: Long, total: Long) {
        val current = currentState()
        persistence.write(encodeUpdateState(current.copy(
            phase = UpdatePhase.DOWNLOADING,
            downloadedBytes = downloaded,
            totalBytes = total,
            failureReason = null,
            detail = null,
        )))
    }

    private fun persistPermissionRequired(): UpdateStatus {
        val current = currentState()
        persistence.write(encodeUpdateState(current.copy(phase = UpdatePhase.INSTALL_PERMISSION_REQUIRED, failureReason = UpdateReasons.PERMISSION_REQUIRED, detail = null)))
        return statusInternal()
    }

    private fun persistVerifyFailed(status: UpdateStatus, detail: String): UpdateStatus {
        val current = currentState()
        persistence.write(encodeUpdateState(current.copy(phase = UpdatePhase.VERIFY_FAILED, failureReason = UpdateReasons.VERIFY_FAILED, detail = detail, verified = false, fileName = null)))
        return statusInternal()
    }

    private fun confinedFile(directory: File, name: String): File? {
        if (name.contains('/') || name.contains('\\')) return null
        val file = File(directory, name)
        return file.takeIf { it.canonicalFile.parentFile == directory.canonicalFile }
    }

    private fun deleteUpdateFiles(directory: File) {
        runCatching { directory.listFiles()?.forEach { it.delete() } }
    }

    private suspend fun attempt(block: suspend () -> UpdateStatus): ApiResult<UpdateStatus> = try {
        check(!closed) { "update.closed" }
        ApiResult.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: UpdateApiException) {
        ApiResult.Failure(ApiError(ErrorCode.INTERNAL, failure.key, retryable = true))
    } catch (failure: Exception) {
        if (failure.message == "update.storage_failed") {
            ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "update.storage_failed", retryable = true))
        } else {
            ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "update.failed", retryable = true))
        }
    }
}
