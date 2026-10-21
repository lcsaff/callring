package com.greatwall.callring.application.recording


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
