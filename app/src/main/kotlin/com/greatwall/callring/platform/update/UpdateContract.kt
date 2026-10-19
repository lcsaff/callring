package com.greatwall.callring.platform.update

import com.greatwall.callring.application.api.AppReleaseInfo
import com.greatwall.callring.application.api.UpdatePhase
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** 更新链路固定的仓库/协议常量；元数据只走 update.json 一条路径（SHA256SUMS.txt 仅人工核对）。 */
internal const val CALLRING_PACKAGE = "com.greatwall.callring"
internal const val RELEASES_API_URL = "https://api.github.com/repos/lcsaff/callring/releases/latest"
internal const val UPDATE_MANIFEST_ASSET = "update.json"
internal const val UPDATE_USER_AGENT_PREFIX = "Callring/"
internal const val NETWORK_CONNECT_TIMEOUT_MS = 10_000
internal const val NETWORK_READ_TIMEOUT_MS = 10_000
internal const val DOWNLOAD_READ_TIMEOUT_MS = 30_000
internal const val MAX_RELEASE_BYTES = 512 * 1024
internal const val MAX_MANIFEST_BYTES = 64 * 1024

/** UI 已识别的失败键；未知细节另存在 PersistedUpdateState.detail，不改变契约字段。 */
internal object UpdateReasons {
    const val CHECK_FAILED = "update.check_failed"
    const val DOWNLOAD_FAILED = "update.download_failed"
    const val VERIFY_FAILED = "update.verify_failed"
    const val PERMISSION_REQUIRED = "update.permission_required"
    const val INSTALL_FAILED = "update.install_failed"
    const val UNSUPPORTED_DEVICE = "update.unsupported_device"
    const val UNSUPPORTED_BUILD = "update.unsupported_build"
    const val NO_INSTALLABLE = "update.no_installable"
}

internal class UpdateFormatException(val detail: String) : Exception(detail)

internal data class GitHubAsset(val name: String, val sizeBytes: Long, val downloadUrl: String)

internal data class GitHubRelease(
    val tagName: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val publishedAtEpochMillis: Long?,
    val notes: String,
    val assets: List<GitHubAsset>,
)

internal data class UpdateManifest(
    val schemaVersion: Int,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val assetName: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal data class InstalledApp(val packageName: String, val versionName: String, val versionCode: Long)

internal data class UpdateEvaluation(
    val phase: UpdatePhase,
    val latest: AppReleaseInfo? = null,
    val failureReason: String? = null,
)

internal fun parseLatestRelease(json: String): GitHubRelease {
    val root = try { JSONObject(json) } catch (failure: Exception) { throw UpdateFormatException("release_json") }
    val tag = root.optString("tag_name").takeIf { it.isNotBlank() } ?: throw UpdateFormatException("release_tag")
    val assetsJson: JSONArray = root.optJSONArray("assets") ?: JSONArray()
    val assets = buildList {
        for (index in 0 until assetsJson.length()) {
            val asset = assetsJson.optJSONObject(index) ?: continue
            val name = asset.optString("name").takeIf { it.isNotBlank() } ?: continue
            val url = asset.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: continue
            val size = asset.optLong("size", -1L).takeIf { it >= 0 } ?: continue
            add(GitHubAsset(name, size, url))
        }
    }
    val published = root.optString("published_at").takeIf { it.isNotBlank() }?.let {
        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
    }
    return GitHubRelease(
        tagName = tag,
        draft = root.optBoolean("draft", false),
        prerelease = root.optBoolean("prerelease", false),
        publishedAtEpochMillis = published,
        notes = root.optString("body", "").take(4000),
        assets = assets,
    )
}

internal fun parseUpdateManifest(json: String): UpdateManifest {
    val root = try { JSONObject(json) } catch (failure: Exception) { throw UpdateFormatException("manifest_json") }
    fun required(key: String): String = root.optString(key).takeIf { it.isNotBlank() } ?: throw UpdateFormatException("manifest_$key")
    val schema = root.optInt("schemaVersion", -1)
    if (schema != 1) throw UpdateFormatException("manifest_schema")
    val versionCode = root.optLong("versionCode", -1L)
    if (versionCode <= 0) throw UpdateFormatException("manifest_versionCode")
    val minSdk = root.optInt("minSdk", -1)
    if (minSdk <= 0) throw UpdateFormatException("manifest_minSdk")
    val size = root.optLong("sizeBytes", -1L)
    if (size <= 0) throw UpdateFormatException("manifest_sizeBytes")
    val sha = required("sha256").lowercase(Locale.ROOT)
    if (!sha.matches(Regex("^[a-f0-9]{64}$"))) throw UpdateFormatException("manifest_sha256")
    val assetName = required("assetName")
    if (assetName.contains('/') || assetName.contains('\\')) throw UpdateFormatException("manifest_assetName")
    return UpdateManifest(
        schemaVersion = schema,
        applicationId = required("applicationId"),
        versionCode = versionCode,
        versionName = required("versionName"),
        minSdk = minSdk,
        assetName = assetName,
        sizeBytes = size,
        sha256 = sha,
    )
}

/** 释放页面 tag 必须是 `v<versionName>`，资产名固定 `callring-<versionName>.apk`。 */
internal fun evaluateCheck(
    release: GitHubRelease,
    manifest: UpdateManifest,
    installed: InstalledApp,
    deviceSdk: Int,
): UpdateEvaluation {
    if (release.draft || release.prerelease) return UpdateEvaluation(UpdatePhase.NO_INSTALLABLE, failureReason = UpdateReasons.NO_INSTALLABLE)
    if (release.tagName != "v${manifest.versionName}") return UpdateEvaluation(UpdatePhase.NO_INSTALLABLE, failureReason = UpdateReasons.NO_INSTALLABLE)
    if (manifest.applicationId != installed.packageName) return UpdateEvaluation(UpdatePhase.NO_INSTALLABLE, failureReason = UpdateReasons.NO_INSTALLABLE)
    val assetName = "callring-${manifest.versionName}.apk"
    if (manifest.assetName != assetName) return UpdateEvaluation(UpdatePhase.NO_INSTALLABLE, failureReason = UpdateReasons.NO_INSTALLABLE)
    val asset = release.assets.firstOrNull { it.name == assetName }
        ?: return UpdateEvaluation(UpdatePhase.NO_INSTALLABLE, failureReason = UpdateReasons.NO_INSTALLABLE)
    if (asset.sizeBytes != manifest.sizeBytes) return UpdateEvaluation(UpdatePhase.NO_INSTALLABLE, failureReason = UpdateReasons.NO_INSTALLABLE)
    if (manifest.versionCode <= installed.versionCode) return UpdateEvaluation(UpdatePhase.UP_TO_DATE)
    if (manifest.minSdk > deviceSdk) return UpdateEvaluation(UpdatePhase.UNSUPPORTED_DEVICE, failureReason = UpdateReasons.UNSUPPORTED_DEVICE)
    return UpdateEvaluation(
        UpdatePhase.UPDATE_AVAILABLE,
        latest = AppReleaseInfo(
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            publishedAtEpochMillis = release.publishedAtEpochMillis,
            notes = release.notes,
            assetName = manifest.assetName,
            sizeBytes = manifest.sizeBytes,
            sha256 = manifest.sha256,
            downloadUrl = asset.downloadUrl,
        ),
    )
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal fun certificateSha256(encoded: ByteArray): String = sha256Hex(encoded)
