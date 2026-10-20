package com.greatwall.callring

import com.greatwall.callring.domain.model.WorkScheduleSettings
import com.greatwall.callring.domain.model.CalendarYear
import com.greatwall.callring.domain.model.DateOverride
import com.greatwall.callring.domain.model.TimeWindow
import com.greatwall.callring.domain.model.CallResult
import com.greatwall.callring.domain.model.CallDecision
import com.greatwall.callring.domain.model.CallSource
import com.greatwall.callring.domain.model.WorkReason
import com.greatwall.callring.domain.model.WorkDayKind
import com.greatwall.callring.domain.model.DateOverrideKind
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.BackendState
import com.greatwall.callring.domain.LeaveRecord
import com.greatwall.callring.domain.RawCallRecord
import com.greatwall.callring.domain.WorkClassifier
import com.greatwall.callring.domain.defaultWorkSchedule
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WorkClassifierTest {
    private fun at(value: String) = Instant.parse(value).toEpochMilli()

    private fun state() = BackendState().apply { schedules += defaultWorkSchedule("UTC") }

    @Test fun leaveDoesNotHideHolidayDayTypeAndName() {
        val s = state()
        val date = LocalDate.parse("2026-10-01")
        s.calendarYears[2026] = CalendarYear(2026, "source", "2025-11-04", "v1")
        s.holidays[date] = "国庆节"
        s.leaves[1] = LeaveRecord(1, at("2026-10-01T00:00:00Z"), at("2026-10-02T00:00:00Z"), "")
        val status = WorkClassifier.status(s, at("2026-10-01T10:00:00Z"))
        assertFalse(status.classification.working)
        assertEquals(WorkReason.PERSONAL_LEAVE, status.classification.reason)
        assertEquals(WorkDayKind.HOLIDAY, status.dayKind)
        assertEquals("国庆节", status.holidayName)
    }

    @Test fun lunchAndAfterHoursRemainCalendarWorkdays() {
        val s = state()
        for (hour in listOf("12:30", "19:00")) {
            val status = WorkClassifier.status(s, at("2026-10-12T${hour}:00Z"))
            assertEquals(WorkDayKind.WORKDAY, status.dayKind)
            assertFalse(status.classification.working)
        }
    }

    @Test fun midnightRefreshesDayTypeEvenDuringMultiDayLeave() {
        val s = state()
        s.leaves[1] = LeaveRecord(1, at("2026-10-16T00:00:00Z"), at("2026-10-19T00:00:00Z"), "")
        val status = WorkClassifier.status(s, at("2026-10-16T23:00:00Z"))
        assertEquals(at("2026-10-17T00:00:00Z"), status.nextBoundaryEpochMillis)
    }

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
