package com.greatwall.callring.application.ports.recording

import com.greatwall.callring.domain.calling.RecordingCall
import com.greatwall.callring.domain.model.SystemCallRecord

fun interface RecordingHistoryReconciler { fun reconcile(records: List<SystemCallRecord>) }
data class RetainedRecordingOrigin(val eventId: Long, val number: String, val started: Long, val direction: com.greatwall.callring.domain.model.CallDirection)
interface RetainedRecordingLinks {
    fun hasRetainedHistory(): Boolean
    fun retainedOrigin(logId: String): RetainedRecordingOrigin?
    fun retainedCandidates(number: String, at: Long, direction: com.greatwall.callring.domain.model.CallDirection): List<RecordingCall>
    /** Validates retained identity and inserts its alias without creating or queueing an archive. */
    fun linkRetained(statisticsId: Long, eventId: Long, direction: com.greatwall.callring.domain.model.CallDirection): Boolean
}
