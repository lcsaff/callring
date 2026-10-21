package com.greatwall.callring.application

data class RecordingCall(val id: Long, val number: String, val startedAt: Long, val durationSeconds: Long?)
data class RecordingCallLink(val id: Long, val number: String, val startedAt: Long, val durationSeconds: Long?,
    val hidden: Boolean = false, val linkedScreenId: Long? = null)

/** The entire target/visible-link closure is consumed under the same storage lease. */
fun findRecordingCall(reads: com.greatwall.callring.application.ports.store.CallingTransactions, id: Long): RecordingCall? =
    reads.readCalling { reader ->
        val requested = reader.callingCall(id) ?: return@readCalling null
        fun com.greatwall.callring.domain.model.RawCallRecord.link() = RecordingCallLink(this.id, number, time, duration, hidden, linkedScreenId)
        resolveRecordingCall(requested.link(), if (requested.hidden) reader.visibleRecordingLinks(id).map { it.link() } else emptyList())
    }

/** Screening ID is the stable archive key even after system sync replaces its UI identity. */
fun resolveRecordingCall(requested: RecordingCallLink, visibleLinks: List<RecordingCallLink>): RecordingCall? {
    val visible = if (requested.hidden) visibleLinks.singleOrNull() ?: return null else requested
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
