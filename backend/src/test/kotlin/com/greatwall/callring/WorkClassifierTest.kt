package com.greatwall.callring

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.WorkClassifier
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WorkClassifierTest {
    private fun at(value: String) = Instant.parse(value).toEpochMilli()

    private fun state() = BackendState().apply { schedules += defaultWorkSchedule("UTC") }

    @Test
    fun leaveOverridesWorkingAndHolidayOverridesOvernightCarry() {
        val s = state()
        s.schedules.clear()
        s.schedules +=
            WorkScheduleSettings(
                "UTC",
                mapOf(1 to listOf(TimeWindow(1320, 120))),
                emptyList(),
                1,
                0,
            )
        assertTrue(WorkClassifier.classify(s, at("2026-10-13T01:00:00Z")).working)
        s.calendarYears[2026] = CalendarYear(2026, "source", "2025-01-01", "v1")
        s.holidays[LocalDate.parse("2026-10-13")] = "holiday"
        assertEquals(
            WorkReason.HOLIDAY,
            WorkClassifier.classify(s, at("2026-10-13T01:00:00Z")).reason,
        )
        s.leaves[1] = LeaveRecord(1, at("2026-10-13T00:00:00Z"), at("2026-10-14T00:00:00Z"), "")
        assertEquals(
            WorkReason.PERSONAL_LEAVE,
            WorkClassifier.classify(s, at("2026-10-13T01:00:00Z")).reason,
        )
    }

    @Test
    fun scheduleVersionsApplyAtTheirEffectiveBoundaryAndHistoryKeepsOriginal() {
        val s = state()
        val boundary = at("2026-10-12T10:00:00Z")
        s.schedules += WorkScheduleSettings("UTC", emptyMap(), emptyList(), 2, boundary)
        assertTrue(WorkClassifier.classify(s, boundary - 1).working)
        assertFalse(WorkClassifier.classify(s, boundary).working)
        val original = WorkClassifier.classify(s, boundary - 1)
        s.calls[1] =
            RawCallRecord(
                1,
                "30001",
                boundary - 1,
                null,
                CallResult.PENDING,
                CallDecision.CUSTOM_RINGTONE,
                CallSource.SCREENING,
                null,
                "key",
                true,
                original,
                original,
            )
        s.overrides[LocalDate.parse("2026-10-12")] =
            DateOverride(LocalDate.parse("2026-10-12"), DateOverrideKind.REST, emptyList())
        WorkClassifier.reclassify(s)
        assertFalse(s.calls.getValue(1).classification.working)
        assertEquals(original, s.calls.getValue(1).originalClassification)
    }

    @Test
    fun nextBoundaryIncludesLeaveEndAndWindowStart() {
        val s = state()
        val now = at("2026-10-12T08:00:00Z")
        assertEquals(
            at("2026-10-12T09:00:00Z"),
            WorkClassifier.status(s, now).nextBoundaryEpochMillis,
        )
    }

    @Test
    fun nextBoundaryIncludesTheEndOfPreviousDaysOvernightWindow() {
        val s = state()
        s.schedules.clear()
        s.schedules +=
            WorkScheduleSettings(
                "UTC",
                mapOf(1 to listOf(TimeWindow(1320, 120))),
                emptyList(),
                1,
                0,
            )
        assertEquals(
            at("2026-10-13T02:00:00Z"),
            WorkClassifier.status(s, at("2026-10-13T01:00:00Z")).nextBoundaryEpochMillis,
        )
    }

    @Test
    fun nextBoundaryIncludesTheSecondOccurrenceOfAnOverlapWindow() {
        val s = state()
        s.schedules.clear()
        s.schedules +=
            WorkScheduleSettings(
                "America/New_York",
                mapOf(7 to listOf(TimeWindow(90, 100))),
                emptyList(),
                1,
                0,
            )
        val now = at("2026-11-01T05:45:00Z")
        assertFalse(WorkClassifier.classify(s, now).working)
        assertFalse(WorkClassifier.classify(s, at("2026-11-01T06:00:00Z")).working)
        assertEquals(
            at("2026-11-01T06:30:00Z"),
            WorkClassifier.status(s, now).nextBoundaryEpochMillis,
        )
    }
}
