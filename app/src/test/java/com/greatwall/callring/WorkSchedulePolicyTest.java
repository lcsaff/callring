package com.greatwall.callring;

import org.junit.Test;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class WorkSchedulePolicyTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private WorkSchedulePolicy.Config config() {
        WorkSchedulePolicy.Config c = new WorkSchedulePolicy.Config();
        c.zone = ZONE;
        c.coveredYears.add(2026);
        return c;
    }

    private long at(String local) {
        return ZonedDateTime.parse(local + "+08:00[Asia/Shanghai]").toInstant().toEpochMilli();
    }

    private List<WorkSchedulePolicy.Window> windows(int start, int end) {
        return Collections.singletonList(new WorkSchedulePolicy.Window(start, end));
    }

    @Test public void defaultZoneFollowsPhoneLocalTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertEquals(ZoneId.systemDefault(), new WorkSchedulePolicy.Config().zone);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test public void defaultsToWeekdaySplitShifts() {
        WorkSchedulePolicy.Config c = config();
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T09:00:00")).working);
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T11:59:59")).working);
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-14T12:00:00")).working);
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T13:00:00")).working);
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-14T18:00:00")).working);
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-17T10:00:00")).working);
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-18T10:00:00")).working);
        assertEquals("工作中", WorkSchedulePolicy.evaluate(c, at("2026-10-14T10:00:00")).reason);
        assertEquals("工作时段外", WorkSchedulePolicy.evaluate(c, at("2026-10-14T12:30:00")).reason);
        assertEquals("休息日", WorkSchedulePolicy.evaluate(c, at("2026-10-17T10:00:00")).reason);
    }

    @Test public void holidayOverridesWeeklyHours() {
        WorkSchedulePolicy.Config c = config();
        c.holidays.put(LocalDate.of(2026, 10, 14), "测试节");
        WorkSchedulePolicy.Result result = WorkSchedulePolicy.evaluate(c, at("2026-10-14T10:00:00"));
        assertFalse(result.working);
        assertTrue(result.verified);
        assertEquals("节假日·测试节", result.reason);
    }

    @Test public void makeupDayUsesDedicatedHoursOnSaturday() {
        WorkSchedulePolicy.Config c = config();
        c.makeupDays.add(LocalDate.of(2026, 10, 17));
        c.makeup = windows(600, 660);
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-17T10:00:00")).working);
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-17T09:30:00")).working);
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-17T11:00:00")).working);
        assertEquals("调休上班", WorkSchedulePolicy.evaluate(c, at("2026-10-17T10:00:00")).reason);
    }

    @Test public void manualWorkOverridesOfficialHoliday() {
        WorkSchedulePolicy.Config c = config();
        LocalDate date = LocalDate.of(2026, 10, 14);
        c.holidays.put(date, "测试节");
        c.overrides.put(date, windows(600, 660));
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T10:00:00")).working);
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-14T09:00:00")).working);
    }

    @Test public void manualRestOverridesOfficialMakeup() {
        WorkSchedulePolicy.Config c = config();
        LocalDate date = LocalDate.of(2026, 10, 17);
        c.makeupDays.add(date);
        c.overrides.put(date, Collections.<WorkSchedulePolicy.Window>emptyList());
        WorkSchedulePolicy.Result result = WorkSchedulePolicy.evaluate(c, at("2026-10-17T10:00:00"));
        assertFalse(result.working);
        assertEquals("休息日", result.reason);
    }

    @Test public void leaveWinsAtInclusiveStartAndEndsExclusively() {
        WorkSchedulePolicy.Config c = config();
        c.overrides.put(LocalDate.of(2026, 10, 14), windows(540, 1080));
        c.leaves.add(new WorkSchedulePolicy.Leave(3, at("2026-10-14T10:00:00"), at("2026-10-14T11:00:00"), "请假"));
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T09:59:59")).working);
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-14T10:00:00")).working);
        assertEquals("请假期间", WorkSchedulePolicy.evaluate(c, at("2026-10-14T10:30:00")).reason);
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T11:00:00")).working);
    }

    @Test public void fridayOvernightCarriesIntoOrdinarySaturday() {
        WorkSchedulePolicy.Config c = config();
        c.weekly.put(5, windows(1320, 120));
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-16T22:00:00")).working);
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-17T01:59:59")).working);
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-17T02:00:00")).working);
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-16T21:59:59")).working);
    }

    @Test public void holidayCancelsPriorDayOvernightCarry() {
        WorkSchedulePolicy.Config c = config();
        c.weekly.put(5, windows(1320, 120));
        c.holidays.put(LocalDate.of(2026, 10, 17), "休假");
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-17T01:00:00")).working);
        assertEquals("节假日·休假", WorkSchedulePolicy.evaluate(c, at("2026-10-17T01:00:00")).reason);
    }

    @Test public void explicitRestCancelsPriorDayOvernightCarry() {
        WorkSchedulePolicy.Config c = config();
        c.weekly.put(5, windows(1320, 120));
        c.overrides.put(LocalDate.of(2026, 10, 17), Collections.<WorkSchedulePolicy.Window>emptyList());
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-17T01:00:00")).working);
    }

    @Test public void holidayStartDayHasNoOvernightCarry() {
        WorkSchedulePolicy.Config c = config();
        c.weekly.put(5, windows(1320, 120));
        c.holidays.put(LocalDate.of(2026, 10, 16), "休假");
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-17T01:00:00")).working);
    }

    @Test public void manualOvernightHoursCarryFromTheirStartDate() {
        WorkSchedulePolicy.Config c = config();
        c.overrides.put(LocalDate.of(2026, 10, 17), windows(1320, 120));
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-18T01:00:00")).working);
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-18T02:00:00")).working);
    }

    @Test public void makeupOvernightHoursCarryIntoSunday() {
        WorkSchedulePolicy.Config c = config();
        c.makeupDays.add(LocalDate.of(2026, 10, 17));
        c.makeup = windows(1320, 120);
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-18T01:00:00")).working);
        assertEquals("调休上班", WorkSchedulePolicy.evaluate(c, at("2026-10-18T01:00:00")).reason);
    }

    @Test public void unknownYearFallsBackAndReportsUnverified() {
        WorkSchedulePolicy.Config c = config();
        c.holidays.put(LocalDate.of(2027, 10, 13), "未核验");
        WorkSchedulePolicy.Result result = WorkSchedulePolicy.evaluate(c, at("2027-10-13T10:00:00"));
        assertTrue(result.working);
        assertFalse(result.verified);
        c.overrides.put(LocalDate.of(2027, 10, 16), windows(540, 1080));
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2027-10-16T10:00:00")).working);
        c.leaves.add(new WorkSchedulePolicy.Leave(1, at("2027-10-16T09:00:00"), at("2027-10-16T11:00:00"), ""));
        assertEquals("请假期间", WorkSchedulePolicy.evaluate(c, at("2027-10-16T10:00:00")).reason);
    }

    @Test public void evaluatesTimestampInConfiguredZone() {
        WorkSchedulePolicy.Config c = config();
        c.zone = ZoneId.of("UTC");
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-14T10:00:00")).working);
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T17:00:00")).working);
    }

    @Test public void weeklyWindowHelperHandlesOvernightAndBoundaries() {
        WorkSchedulePolicy.Config c = config();
        c.weekly.clear();
        c.weekly.put(5, windows(1320, 120));
        assertTrue(WorkSchedulePolicy.inWeeklyWindows(c.weekly, ZONE, at("2026-10-16T22:00:00")));
        assertTrue(WorkSchedulePolicy.inWeeklyWindows(c.weekly, ZONE, at("2026-10-17T01:00:00")));
        assertFalse(WorkSchedulePolicy.inWeeklyWindows(c.weekly, ZONE, at("2026-10-17T02:00:00")));
        assertFalse(WorkSchedulePolicy.inWeeklyWindows(c.weekly, ZONE, at("2026-10-16T01:00:00")));
    }

    @Test public void overlappingWorkWindowsFormContinuousCoverage() {
        WorkSchedulePolicy.Config c = config();
        c.weekly.put(3, Arrays.asList(new WorkSchedulePolicy.Window(540, 660),
                new WorkSchedulePolicy.Window(600, 720)));
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-14T08:59:59")).working);
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T09:00:00")).working);
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T10:30:00")).working);
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T11:00:00")).working);
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T11:59:59")).working);
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-10-14T12:00:00")).working);
    }

    @Test public void overnightOverrideCarriesAcrossCoveredYearBoundary() {
        WorkSchedulePolicy.Config c = config();
        c.coveredYears.add(2027);
        c.overrides.put(LocalDate.of(2026, 12, 31), windows(1320, 120));
        WorkSchedulePolicy.Result before = WorkSchedulePolicy.evaluate(c, at("2026-12-31T23:59:59"));
        WorkSchedulePolicy.Result after = WorkSchedulePolicy.evaluate(c, at("2027-01-01T00:00:00"));
        assertTrue(before.working);
        assertTrue(before.verified);
        assertTrue(after.working);
        assertTrue(after.verified);
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2027-01-01T01:59:59")).working);
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2027-01-01T02:00:00")).working);
    }

    @Test public void newYearHolidayCancelsPreviousYearOvernightCarry() {
        WorkSchedulePolicy.Config c = config();
        c.coveredYears.add(2027);
        c.overrides.put(LocalDate.of(2026, 12, 31), windows(1320, 120));
        c.holidays.put(LocalDate.of(2027, 1, 1), "元旦");
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-12-31T23:59:59")).working);
        WorkSchedulePolicy.Result result = WorkSchedulePolicy.evaluate(c, at("2027-01-01T00:00:00"));
        assertFalse(result.working);
        assertTrue(result.verified);
        assertEquals("节假日·元旦", result.reason);
    }

    @Test public void newYearExplicitRestCancelsPreviousYearOvernightCarry() {
        WorkSchedulePolicy.Config c = config();
        c.coveredYears.add(2027);
        c.overrides.put(LocalDate.of(2026, 12, 31), windows(1320, 120));
        c.overrides.put(LocalDate.of(2027, 1, 1), Collections.<WorkSchedulePolicy.Window>emptyList());
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2027-01-01T01:00:00")).working);
    }

    @Test public void overnightCarryIntoUnknownYearUsesManualHoursAndIsUnverified() {
        WorkSchedulePolicy.Config c = config();
        c.overrides.put(LocalDate.of(2026, 12, 31), windows(1320, 120));
        c.holidays.put(LocalDate.of(2027, 1, 1), "未核验");
        WorkSchedulePolicy.Result result = WorkSchedulePolicy.evaluate(c, at("2027-01-01T01:00:00"));
        assertTrue(result.working);
        assertFalse(result.verified);
    }

    @Test public void officialDateOverridesApplyOnlyToTheirExactYear() {
        WorkSchedulePolicy.Config c = config();
        c.coveredYears.add(2027);
        c.holidays.put(LocalDate.of(2026, 1, 1), "元旦");
        assertFalse(WorkSchedulePolicy.evaluate(c, at("2026-01-01T10:00:00")).working);
        WorkSchedulePolicy.Result result = WorkSchedulePolicy.evaluate(c, at("2027-01-01T10:00:00"));
        assertTrue(result.working);
        assertTrue(result.verified);
    }

    @Test public void wholeDayLeaveIncludesEveryMomentUntilNextMidnight() {
        WorkSchedulePolicy.Config c = config();
        c.overrides.put(LocalDate.of(2026, 10, 15), windows(0, 60));
        c.leaves.add(new WorkSchedulePolicy.Leave(1, at("2026-10-14T00:00:00"),
                at("2026-10-15T00:00:00"), "整天"));
        for (String local : new String[]{"2026-10-14T00:00:00", "2026-10-14T10:00:00", "2026-10-14T23:59:59"}) {
            WorkSchedulePolicy.Result result = WorkSchedulePolicy.evaluate(c, at(local));
            assertFalse(result.working);
            assertEquals("请假期间", result.reason);
        }
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-15T00:00:00")).working);
    }

    @Test public void multiDayLeaveSuppressesWorkAcrossIntermediateDays() {
        WorkSchedulePolicy.Config c = config();
        c.leaves.add(new WorkSchedulePolicy.Leave(1, at("2026-10-14T10:00:00"),
                at("2026-10-16T11:00:00"), "多天"));
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T09:59:59")).working);
        for (String local : new String[]{"2026-10-14T10:00:00", "2026-10-15T10:00:00", "2026-10-16T10:59:59"}) {
            assertEquals("请假期间", WorkSchedulePolicy.evaluate(c, at(local)).reason);
        }
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-16T11:00:00")).working);
    }

    @Test public void crossMidnightLeaveInterruptsThenResumesOvernightWork() {
        WorkSchedulePolicy.Config c = config();
        c.weekly.put(3, windows(1320, 120));
        c.leaves.add(new WorkSchedulePolicy.Leave(1, at("2026-10-14T23:00:00"),
                at("2026-10-15T01:00:00"), "跨夜"));
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T22:59:59")).working);
        assertEquals("请假期间", WorkSchedulePolicy.evaluate(c, at("2026-10-14T23:00:00")).reason);
        assertEquals("请假期间", WorkSchedulePolicy.evaluate(c, at("2026-10-15T00:00:00")).reason);
        assertEquals("请假期间", WorkSchedulePolicy.evaluate(c, at("2026-10-15T00:59:59")).reason);
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-15T01:00:00")).working);
    }

    @Test public void overlappingLeavesRemainActiveUntilBothHaveEnded() {
        WorkSchedulePolicy.Config c = config();
        c.overrides.put(LocalDate.of(2026, 10, 14), windows(540, 1080));
        c.leaves.add(new WorkSchedulePolicy.Leave(1, at("2026-10-14T10:00:00"),
                at("2026-10-14T11:00:00"), "第一段"));
        c.leaves.add(new WorkSchedulePolicy.Leave(2, at("2026-10-14T10:30:00"),
                at("2026-10-14T12:00:00"), "第二段"));
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T09:59:59")).working);
        for (String local : new String[]{"2026-10-14T10:00:00", "2026-10-14T10:30:00", "2026-10-14T11:00:00", "2026-10-14T11:59:59"}) {
            assertEquals("请假期间", WorkSchedulePolicy.evaluate(c, at(local)).reason);
        }
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2026-10-14T12:00:00")).working);
        Collections.reverse(c.leaves);
        assertEquals("请假期间", WorkSchedulePolicy.evaluate(c, at("2026-10-14T11:00:00")).reason);
    }

    @Test public void leaveSpanningYearBoundaryEndsExclusivelyInUnknownYear() {
        WorkSchedulePolicy.Config c = config();
        c.leaves.add(new WorkSchedulePolicy.Leave(1, at("2026-12-31T10:00:00"),
                at("2027-01-01T11:00:00"), "跨年"));
        WorkSchedulePolicy.Result result = WorkSchedulePolicy.evaluate(c, at("2027-01-01T10:59:59"));
        assertFalse(result.working);
        assertFalse(result.verified);
        assertEquals("请假期间", result.reason);
        assertTrue(WorkSchedulePolicy.evaluate(c, at("2027-01-01T11:00:00")).working);
    }

    @Test public void rejectsInvalidWindows() {
        for (int[] pair : new int[][]{{-1, 60}, {60, -1}, {1440, 60}, {60, 1440}, {60, 60}}) {
            try {
                new WorkSchedulePolicy.Window(pair[0], pair[1]);
                fail("Accepted invalid minutes " + Arrays.toString(pair));
            } catch (IllegalArgumentException expected) { }
        }
        WorkSchedulePolicy.Window window = new WorkSchedulePolicy.Window(1439, 0);
        assertEquals(1439, window.startMinute);
        assertEquals(0, window.endMinute);
    }
}
