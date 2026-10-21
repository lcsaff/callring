package com.greatwall.callring.platform.calendar

import java.time.LocalDate

internal object CalendarRefreshPolicy {
    fun due(now: Long, lastAttempt: Long, succeeded: Boolean): Boolean =
        lastAttempt <= 0 || now < lastAttempt ||
            now - lastAttempt >= (if (succeeded) 7 * 24 else 6) * 60 * 60_000L
    fun years(today: LocalDate): List<Int> =
        if (today.monthValue >= 10) listOf(today.year, today.year + 1) else listOf(today.year)
}
