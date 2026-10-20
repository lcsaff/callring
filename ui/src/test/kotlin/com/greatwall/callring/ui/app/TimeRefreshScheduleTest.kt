package com.greatwall.callring.ui.app

import java.time.ZoneId
import java.time.Clock
import java.time.Instant
import com.greatwall.callring.ui.state.TimeRefreshSchedule
import org.junit.Assert.*
import org.junit.Test

class TimeRefreshScheduleTest {
    private val utc = ZoneId.of("UTC")
    @Test fun shortResumeIsZeroButElapsedIntervalAndMissedBoundaryRefreshOnce() {
        val schedule = TimeRefreshSchedule(Clock.fixed(Instant.ofEpochMilli(1000), utc))
        assertFalse(schedule.due(1000, utc, 0, 5000, 60000))
        assertFalse(schedule.due(2000, utc, 0, 5000, 60000))
        assertTrue(schedule.due(6000, utc, 0, 5000, 60000))
        assertFalse(schedule.due(6001, utc, 0, 5000, 60000))
        assertTrue(schedule.due(66000, utc, 0, 5000, 60000))
        assertFalse(schedule.due(66001, utc, 0, 5000, 60000))
    }
    @Test fun dateTimezoneExplicitTimeSignalAndBackwardsClockAreRealInvalidations() {
        val schedule = TimeRefreshSchedule(Clock.fixed(Instant.EPOCH, utc))
        assertTrue(schedule.due(1, ZoneId.of("Asia/Shanghai"), 0, null, null))
        assertTrue(schedule.due(2, ZoneId.of("Asia/Shanghai"), 1, null, null))
        assertFalse(schedule.due(3, ZoneId.of("Asia/Shanghai"), 1, null, null))
        assertTrue(schedule.due(86400000, ZoneId.of("Asia/Shanghai"), 1, null, null))
        assertTrue(schedule.due(0, ZoneId.of("Asia/Shanghai"), 1, null, null))
    }
}
