package com.greatwall.callring.platform.update

import android.content.Context
import com.greatwall.callring.application.api.ApiError
import com.greatwall.callring.application.api.ApiResult
import com.greatwall.callring.application.api.AppReleaseInfo
import com.greatwall.callring.application.api.AppUpdateApi
import com.greatwall.callring.application.api.ErrorCode
import com.greatwall.callring.application.api.UpdatePhase
import com.greatwall.callring.application.api.UpdateStatus
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
            val previous = synchronized(lock) { decodeUpdateState(persistence.read()) }
            val outcome = withContext(io) { fetchCheck(previous) }
            synchronized(lock) {
                persistence.write(encodeUpdateState(outcome))
                statusInternal()
            }
        }
    }

    override suspend fun download(): ApiResult<UpdateStatus> = attempt {
        val plan = synchronized(lock) { prepareDownload() }
        if (plan != null) launchDownload(plan)
        synchronized(lock) { statusInternal() }
    }

    override suspend fun cancel(): ApiResult<UpdateStatus> = attempt {
        cancelRequested = true
        runCatching { activeConnection?.disconnect() }
        val job = downloadJob
        if (job != null) withTimeoutOrNull(5_000) { job.cancel(); job.join() }
        synchronized(lock) {
            downloadJob = null
            directory()?.let(::deleteUpdateFiles)
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
        val failure = verifyUpdateFile(file, latest, build().packageName, build().deviceSdk, apkInspector, installedCertificate)
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
        cancelRequested = true
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

    private class UpdateFailure(val reason: String, val detail: String) : Exception(detail)
    private class UpdateApiException(val key: String) : Exception(key)

    private fun prepareDownload(): DownloadPlan? {
        val status = statusInternal()
        val latest = status.latest ?: return null
        if (downloadJob?.isActive == true) return null
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
        deleteUpdateFiles(directory)
        if (!directory.isDirectory && !directory.mkdirs()) {
            persistence.write(encodeUpdateState(currentState().copy(
                phase = UpdatePhase.VERIFY_FAILED,
                failureReason = UpdateReasons.DOWNLOAD_FAILED,
                detail = "directory_unavailable",
            )))
            return null
        }
        cancelRequested = false
        val part = File(directory, latest.assetName + ".part")
        val target = confinedFile(directory, latest.assetName) ?: return null
        persistence.write(encodeUpdateState(currentState().copy(
            phase = UpdatePhase.DOWNLOADING,
            failureReason = null,
            detail = null,
            downloadedBytes = 0,
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
                if (failure != null) {
                    plan.target.delete()
                    persist(phase = UpdatePhase.VERIFY_FAILED, reason = failure, detail = "install_verification")
                } else {
                    persist(phase = UpdatePhase.VERIFIED, reason = null, detail = null, verified = true)
                }
            } catch (_: CancellationException) {
                plan.part.delete()
                plan.target.delete()
                throw CancellationException("update.cancelled")
            } catch (failure: UpdateFailure) {
                plan.part.delete()
                plan.target.delete()
                persist(phase = UpdatePhase.VERIFY_FAILED, reason = failure.reason, detail = failure.detail)
            } catch (_: Exception) {
                plan.part.delete()
                plan.target.delete()
                persist(phase = UpdatePhase.VERIFY_FAILED, reason = UpdateReasons.DOWNLOAD_FAILED, detail = "unexpected")
            } finally {
                activeConnection = null
            }
        }
    }

    private suspend fun downloadToPart(plan: DownloadPlan) {
        val http = openConnection(URL(plan.latest.downloadUrl))
        activeConnection = http
        try {
            http.instanceFollowRedirects = true
            http.connectTimeout = NETWORK_CONNECT_TIMEOUT_MS
            http.readTimeout = downloadReadTimeoutMs
            http.requestMethod = "GET"
            http.setRequestProperty("User-Agent", UPDATE_USER_AGENT_PREFIX + build().current.versionName)
            http.setRequestProperty("Accept", "application/vnd.android.package-archive")
            val status = http.responseCode
            if (status !in 200..299) throw UpdateFailure(UpdateReasons.DOWNLOAD_FAILED, "http_$status")
            val declared = http.contentLengthLong.takeIf { it > 0 }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastPublished = 0L
            plan.part.outputStream().use { output ->
                http.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelRequested) throw CancellationException("update.cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (downloaded + count > plan.latest.sizeBytes) {
                            throw UpdateFailure(UpdateReasons.DOWNLOAD_FAILED, "size_overflow")
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        downloaded += count
                        if (downloaded - lastPublished >= 256 * 1024) {
                            lastPublished = downloaded
                            synchronized(lock) { persistProgress(downloaded, declared ?: plan.latest.sizeBytes) }
                        }
                    }
                }
            }
            synchronized(lock) { persistProgress(downloaded, declared ?: plan.latest.sizeBytes) }
            if (downloaded != plan.latest.sizeBytes) throw UpdateFailure(UpdateReasons.DOWNLOAD_FAILED, "size_mismatch")
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actual.equals(plan.latest.sha256, ignoreCase = true)) throw UpdateFailure(UpdateReasons.VERIFY_FAILED, "sha256_mismatch")
            if (plan.target.exists() && !plan.target.delete()) throw UpdateFailure(UpdateReasons.DOWNLOAD_FAILED, "replace_failed")
            if (!plan.part.renameTo(plan.target)) throw UpdateFailure(UpdateReasons.DOWNLOAD_FAILED, "rename_failed")
        } finally {
            runCatching { http.disconnect() }
            activeConnection = null
        }
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
        if (downloadJob?.isActive != true && (state.phase == UpdatePhase.DOWNLOADING || state.phase == UpdatePhase.VERIFYING)) {
            directory()?.let(::deleteUpdateFiles)
            state = if (latest != null) state.copy(
                phase = UpdatePhase.UPDATE_AVAILABLE,
                failureReason = UpdateReasons.DOWNLOAD_FAILED,
                detail = "interrupted",
                downloadedBytes = 0,
                totalBytes = latest.sizeBytes,
                fileName = null,
                verified = false,
            ) else state.copy(phase = UpdatePhase.CHECK_FAILED, failureReason = UpdateReasons.CHECK_FAILED, downloadedBytes = 0, fileName = null)
            persistence.write(encodeUpdateState(state))
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
