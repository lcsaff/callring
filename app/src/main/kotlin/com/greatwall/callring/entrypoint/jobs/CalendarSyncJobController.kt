package com.greatwall.callring.entrypoint.jobs

import com.greatwall.callring.platform.calendar.CalendarRefreshPolicy
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

class CalendarSyncJobController(
    private val clock: () -> Clock,
    private val cached: suspend (Int) -> Boolean,
    private val attempts: Attempts,
    private val synchronize: suspend (Int) -> Boolean,
) {
    data class Attempt(val timestamp: Long, val succeeded: Boolean)
    interface Attempts {
        fun read(year: Int): Attempt
        fun begin(year: Int, now: Long): Boolean
        fun finish(year: Int, success: Boolean)
    }

    suspend fun run(stopped: () -> Boolean) {
        try {
            val time = clock()
            val today = LocalDate.now(time.withZone(ZoneId.of("Asia/Shanghai")))
            for (year in CalendarRefreshPolicy.years(today)) {
                if (stopped()) break
                val now = time.millis()
                val exists = cached(year)
                val previous = attempts.read(year)
                if (!CalendarRefreshPolicy.due(now, previous.timestamp, exists && previous.succeeded)) continue
                if (!attempts.begin(year, now)) continue
                attempts.finish(year, synchronize(year))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The next periodic job retries; cached data stays usable. */ }
    }
}
