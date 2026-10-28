package com.greatwall.callring.contracts.backup

import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.common.DocumentRef

/** Counts are from verified staged databases, never from untrusted manifest claims. */
data class BackupSummary(
    val createdAtEpochMillis: Long,
    val callers: Long,
    val calls: Long,
    val archives: Long,
    val audioFiles: Long,
    val audioBytes: Long,
    val includesAudio: Boolean,
    val includesCustomRingtone: Boolean,
)

/** Opaque, process-local, single-use preview; bound to the source digest and current owner revision. */
data class BackupPreview(val token: String, val summary: BackupSummary)
/** Data and the new business owner are committed; UI restart has been requested. */
data class RestoreReceipt(val applicationRestartRequested: Boolean)

enum class BackupPhase { IDLE, PREPARING, EXPORTING, VALIDATING, READY, RESTORING, COMPLETE, FAILED }
data class BackupProgress(val phase: BackupPhase)

interface BackupApi {
    val progress: kotlinx.coroutines.flow.StateFlow<BackupProgress>
    /** Password arrays are cleared after use; the UI must never persist them in saved state. */
    suspend fun export(destination: DocumentRef, password: CharArray, includeAudio: Boolean): ApiResult<BackupSummary>
    suspend fun preview(source: DocumentRef, password: CharArray): ApiResult<BackupPreview>
    suspend fun restore(previewToken: String): ApiResult<RestoreReceipt>
    suspend fun discard(previewToken: String): ApiResult<Unit>
}
