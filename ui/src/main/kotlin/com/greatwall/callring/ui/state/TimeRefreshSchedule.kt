package com.greatwall.callring.ui.state

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** The query owner retains this baseline across disposed/restored navigation compositions. */
internal class TimeRefreshSchedule(val clock: Clock = SystemLocalClock) {
    private var checkedAt = clock.millis()
    private var checkedZone = clock.zone
    private var checkedSignal: Long? = null
    fun captureRead(): Pair<Long, ZoneId> = clock.millis() to clock.zone
    fun readSucceeded(startedAt: Pair<Long, ZoneId>) {
        checkedAt = startedAt.first
        checkedZone = startedAt.second
    }
    fun due(now: Long, zone: ZoneId, signal: Long, boundary: Long?, interval: Long?): Boolean {
        val changed = (checkedSignal != null && signal != checkedSignal) || zone != checkedZone || now < checkedAt ||
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate() != Instant.ofEpochMilli(checkedAt).atZone(checkedZone).toLocalDate() ||
            (boundary != null && boundary > checkedAt && now >= boundary) ||
            (interval != null && now - checkedAt >= interval)
        checkedSignal = signal
        if (changed) { checkedAt = now; checkedZone = zone }
        return changed
    }
    fun delayMillis(now: Long, boundary: Long?, interval: Long?): Long? = listOfNotNull(
        interval?.let { checkedAt + it - now }, boundary?.takeIf { it > checkedAt }?.minus(now))
        .minOrNull()?.coerceAtLeast(1L)
}

/** Unlike Clock.systemDefaultZone(), this observes a timezone changed while the owner was retained. */
internal object SystemLocalClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = Clock.system(zone)
    override fun instant(): Instant = Instant.now()
}
