package com.greatwall.callring.platform

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CalendarRefreshPolicyTest {
    @Test fun missingYearRetriesAfterSixHoursAndCachedYearAfterOneWeek() {
        val now = 2_000_000_000_000L
        assertFalse(CalendarRefreshPolicy.due(now, now - 60_000, false))
        assertTrue(CalendarRefreshPolicy.due(now, now - 6 * 60 * 60_000, false))
        assertFalse(CalendarRefreshPolicy.due(now, now - 6 * 60 * 60_000, true))
        assertTrue(CalendarRefreshPolicy.due(now, now - 7 * 24 * 60 * 60_000, true))
        assertTrue(CalendarRefreshPolicy.due(now, 0, true))
        assertTrue(CalendarRefreshPolicy.due(now, now + 1, true))
    }
    @Test fun nextYearPrefetchStartsInOctober() {
        assertEquals(listOf(2026), CalendarRefreshPolicy.years(LocalDate.of(2026, 9, 30)))
        assertEquals(listOf(2026, 2027), CalendarRefreshPolicy.years(LocalDate.of(2026, 10, 1)))
    }
}
