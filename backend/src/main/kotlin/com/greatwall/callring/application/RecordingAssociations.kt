package com.greatwall.callring.application

data class RecordingCall(val id: Long, val number: String, val startedAt: Long, val durationSeconds: Long?)
data class RecordingCallLink(val id: Long, val number: String, val startedAt: Long, val durationSeconds: Long?,
    val hidden: Boolean = false, val linkedScreenId: Long? = null)

/** Screening ID is the stable archive key even after system sync replaces its UI identity. */
fun resolveRecordingCall(records: Collection<RecordingCallLink>, id: Long): RecordingCall? {
    val requested = records.firstOrNull { it.id == id } ?: return null
    val visible = if (requested.hidden) records.singleOrNull { !it.hidden && it.linkedScreenId == id } ?: return null else requested
    return RecordingCall(visible.linkedScreenId ?: visible.id, visible.number, visible.startedAt, visible.durationSeconds)
}

/** A historical row is never evidence of an active incoming call. */
class IncomingRecordingSession {
    private var observingRing = false
    private var offHook = false
    private var lastIdleAt = Long.MIN_VALUE
    private val calls = linkedMapOf<Long, Long>()
    @Synchronized fun ringing() { observingRing = true; offHook = false }
    @Synchronized fun answered() { offHook = true }
    @Synchronized fun activeCandidate(now: Long): Long? = if (offHook) candidate(now) else null
    @Synchronized fun register(id: Long, at: Long) { if (at > lastIdleAt) calls[id] = at }
    @Synchronized fun candidate(now: Long): Long? {
        if (!observingRing) return null
        return calls.filterValues { it in (now - 120_000)..now }.keys.singleOrNull()
    }
    @Synchronized fun idle(at: Long) { observingRing = false; offHook = false; lastIdleAt = at; calls.clear() }
}
