package com.greatwall.callring.contracts.storage

import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.common.ApiError
import com.greatwall.callring.contracts.recording.ArchiveStatus

/** Null means unknown, including a failed observation. Database bytes are reusable after deletion. */
data class StorageInventory(val ownedAudioBytes: Long?, val databaseBytes: Long?, val temporaryBytes: Long?,
    val updateCacheBytes: Long?, val freeBytes: Long?)
/** Half-open interval in epoch milliseconds. At most 1,000 matching archives per preview. */
data class ArchiveCleanupSelection(val fromEpochMillis: Long, val beforeEpochMillis: Long, val statuses: Set<ArchiveStatus>)
data class CleanupTarget(val callId: Long, val generation: Long)
data class ArchiveCleanupPreview(val token: String, val targets: List<CleanupTarget>, val selection: ArchiveCleanupSelection,
    val audioBytes: Long?, val excludedActive: Int, val expiresAtEpochMillis: Long)
enum class CleanupItemStatus { COMMITTED, FAILED, UNSTARTED }
data class CleanupItemReceipt(val target: CleanupTarget, val status: CleanupItemStatus, val error: ApiError? = null)
data class ArchiveCleanupReceipt(val items: List<CleanupItemReceipt>, val cancelled: Boolean)
data class NumberProfileResetPreview(val token: String, val profileCount: Long, val callCount: Long,
    val widgetBindingCount: Long, val archiveCount: Long, val audioBytes: Long?, val expiresAtEpochMillis: Long)
data class NumberProfileResetReceipt(val profileCount: Long, val callCount: Long,
    val widgetBindingCount: Long, val archiveCount: Long, val audioBytes: Long?,
    val keepRecordings: Boolean, val applicationRestartRequested: Boolean)
interface StorageManagementApi {
    suspend fun previewNumberProfiles(): ApiResult<NumberProfileResetPreview>
    suspend fun confirmNumberProfiles(token: String, keepRecordings: Boolean): ApiResult<NumberProfileResetReceipt>
    suspend fun discardNumberProfiles(token: String): ApiResult<Unit>
    suspend fun inventory(): ApiResult<StorageInventory>
    suspend fun preview(selection: ArchiveCleanupSelection): ApiResult<ArchiveCleanupPreview>
    /** Explicit confirmation of the exact preview; repeated confirmation returns its original receipt. */
    suspend fun confirm(token: String): ApiResult<ArchiveCleanupReceipt>
    /** Stops unstarted items only; committed deletions cannot be undone. */
    fun cancel(token: String)
}
