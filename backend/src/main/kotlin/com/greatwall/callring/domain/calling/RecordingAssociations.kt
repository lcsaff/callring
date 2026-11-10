package com.greatwall.callring.domain.calling


data class RecordingCall(val id: Long, val number: String, val startedAt: Long, val durationSeconds: Long?)
data class RecordingCallLink(val id: Long, val number: String, val startedAt: Long, val durationSeconds: Long?,
    val hidden: Boolean = false, val linkedScreenId: Long? = null)

fun stableRecordingCallId(id: Long, linkedScreenId: Long?): Long = linkedScreenId ?: id

/** Screening ID is the stable archive key even after system sync replaces its UI identity. */
fun resolveRecordingCall(requested: RecordingCallLink, visibleLinks: List<RecordingCallLink>): RecordingCall? {
    val visible = if (requested.hidden) visibleLinks.singleOrNull() ?: return null else requested
    return RecordingCall(stableRecordingCallId(visible.id, visible.linkedScreenId), visible.number, visible.startedAt, visible.durationSeconds)
}
