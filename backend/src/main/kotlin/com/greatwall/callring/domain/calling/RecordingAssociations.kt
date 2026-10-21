package com.greatwall.callring.domain.calling


data class RecordingCall(val id: Long, val number: String, val startedAt: Long, val durationSeconds: Long?)
data class RecordingCallLink(val id: Long, val number: String, val startedAt: Long, val durationSeconds: Long?,
    val hidden: Boolean = false, val linkedScreenId: Long? = null)

/** Screening ID is the stable archive key even after system sync replaces its UI identity. */
fun resolveRecordingCall(requested: RecordingCallLink, visibleLinks: List<RecordingCallLink>): RecordingCall? {
    val visible = if (requested.hidden) visibleLinks.singleOrNull() ?: return null else requested
    return RecordingCall(visible.linkedScreenId ?: visible.id, visible.number, visible.startedAt, visible.durationSeconds)
}
