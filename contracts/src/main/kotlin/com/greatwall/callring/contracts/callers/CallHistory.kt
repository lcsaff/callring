package com.greatwall.callring.contracts.callers

import com.greatwall.callring.contracts.change.InvalidationToken
import com.greatwall.callring.contracts.common.SnapshotMeta

data class CallHistoryQuery(val pageSize: Int = 50, val cursor: CallHistoryCursor? = null)

/** Pass the cursor unchanged to continue at the same page size. */
data class CallHistoryCursor(
    val token: InvalidationToken,
    val startedAtEpochMillis: Long,
    val callId: Long,
    val pageSize: Int,
)

data class CallHistoryPage(
    val entries: List<CallHistoryEntry>,
    val nextCursor: CallHistoryCursor?,
    val meta: SnapshotMeta,
)

data class CallHistoryEntry(
    val call: CallDetail,
    val recording: CallHistoryRecording,
    val linkedScreeningCallId: Long? = null,
)

enum class CallArchiveAvailability { NONE, AVAILABLE, INDEXING, UNREADABLE }

data class CallHistoryRecording(
    val archiveId: Long?,
    val availability: CallArchiveAvailability,
    val hasAudio: Boolean,
    val hasTranscript: Boolean,
)
