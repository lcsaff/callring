package com.greatwall.callring.platform.update

import android.content.Context
import android.content.SharedPreferences
import com.greatwall.callring.application.api.AppReleaseInfo
import com.greatwall.callring.application.api.UpdatePhase
import org.json.JSONObject

/** 应用私有存储：整个更新状态是一段稳定 JSON，写入失败由调用方转成 ApiResult.Failure。 */
internal interface UpdatePersistence {
    fun read(): String?
    fun write(value: String?)
}

internal class SharedPreferencesUpdatePersistence(private val preferences: SharedPreferences) : UpdatePersistence {
    override fun read(): String? = preferences.getString(KEY, null)
    override fun write(value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(KEY) else editor.putString(KEY, value)
        if (!editor.commit()) throw IllegalStateException("update.storage_failed")
    }
    companion object {
        private const val KEY = "state"
        fun forContext(context: Context) = SharedPreferencesUpdatePersistence(
            context.getSharedPreferences("app_update", Context.MODE_PRIVATE),
        )
    }
}

/** 可恢复的最小事实：ETag、最近状态、最近元数据、下载/校验结果与目标文件。 */
internal data class PersistedUpdateState(
    val etag: String? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val failureReason: String? = null,
    val detail: String? = null,
    val checkedAtEpochMillis: Long? = null,
    val latest: AppReleaseInfo? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val fileName: String? = null,
    val verified: Boolean = false,
)

internal fun encodeUpdateState(state: PersistedUpdateState): String = JSONObject().apply {
    put("version", 1)
    put("etag", state.etag ?: JSONObject.NULL)
    put("phase", state.phase.name)
    put("failureReason", state.failureReason ?: JSONObject.NULL)
    put("detail", state.detail ?: JSONObject.NULL)
    put("checkedAt", state.checkedAtEpochMillis ?: JSONObject.NULL)
    put("downloadedBytes", state.downloadedBytes)
    put("totalBytes", state.totalBytes ?: JSONObject.NULL)
    put("fileName", state.fileName ?: JSONObject.NULL)
    put("verified", state.verified)
    put("latest", state.latest?.let { release ->
        JSONObject().apply {
            put("versionName", release.versionName)
            put("versionCode", release.versionCode)
            put("publishedAt", release.publishedAtEpochMillis ?: JSONObject.NULL)
            put("notes", release.notes)
            put("assetName", release.assetName)
            put("sizeBytes", release.sizeBytes)
            put("sha256", release.sha256)
            put("downloadUrl", release.downloadUrl)
        }
    } ?: JSONObject.NULL)
}.toString()

internal fun decodeUpdateState(text: String?): PersistedUpdateState? {
    if (text == null) return null
    return runCatching {
        val root = JSONObject(text)
        if (root.optInt("version", 0) != 1) return null
        val phase = runCatching { UpdatePhase.valueOf(root.getString("phase")) }.getOrNull() ?: return null
        val latest = root.optJSONObject("latest")?.let {
            AppReleaseInfo(
                versionName = it.getString("versionName"),
                versionCode = it.getLong("versionCode"),
                publishedAtEpochMillis = if (it.isNull("publishedAt")) null else it.getLong("publishedAt"),
                notes = it.optString("notes"),
                assetName = it.getString("assetName"),
                sizeBytes = it.getLong("sizeBytes"),
                sha256 = it.getString("sha256"),
                downloadUrl = it.getString("downloadUrl"),
            )
        }
        PersistedUpdateState(
            etag = root.nullableString("etag"),
            phase = phase,
            failureReason = root.nullableString("failureReason"),
            detail = root.nullableString("detail"),
            checkedAtEpochMillis = if (root.isNull("checkedAt")) null else root.getLong("checkedAt"),
            latest = latest,
            downloadedBytes = root.optLong("downloadedBytes", 0),
            totalBytes = if (root.isNull("totalBytes")) null else root.getLong("totalBytes"),
            fileName = root.nullableString("fileName"),
            verified = root.optBoolean("verified", false),
        )
    }.getOrNull()
}

private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
