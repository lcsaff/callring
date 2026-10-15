package com.greatwall.callring;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class CallerScoreTest {
    private CallerScore.Entry entry(long time, boolean working) { return new CallerScore.Entry(time, working); }
    private CallerScore.Result score(List<CallerScore.Entry> entries) {
        return CallerScore.calculate(entries, 0, Long.MAX_VALUE, 5, 2, 3);
    }

    @Test public void threeOffHoursAndTwoRepeatsDeductNineteenPoints() {
        CallerScore.Result result = score(Arrays.asList(entry(1000, false), entry(2000, false), entry(3000, false)));
        assertEquals(Integer.valueOf(81), result.points);
        assertEquals(3, result.total);
        assertEquals(3, result.offHours);
        assertEquals(2, result.repeats);
        assertEquals(19, result.deductions);
    }
    @Test public void noDataHasNoScore() {
        CallerScore.Result result = score(Collections.<CallerScore.Entry>emptyList());
        assertNull(result.points);
        assertEquals(0, result.total);
        assertEquals(0, result.deductions);
    }
    @Test public void workingCallsAloneKeepOneHundredPoints() {
        CallerScore.Result result = score(Arrays.asList(entry(1000, true), entry(300000, true)));
        assertEquals(Integer.valueOf(100), result.points);
        assertEquals(0, result.offHours);
        assertEquals(0, result.repeats);
    }
    @Test public void filtersInclusiveStartExclusiveEndBeforeCountingRepeats() {
        CallerScore.Result result = CallerScore.calculate(Arrays.asList(entry(999, false), entry(1000, true), entry(1500, false), entry(2000, false)), 1000, 2000, 5, 2, 3);
        assertEquals(2, result.total);
        assertEquals(1, result.offHours);
        assertEquals(1, result.repeats);
        assertEquals(Integer.valueOf(93), result.points);
    }
    @Test public void sortsTimestampsAndIncludesExactRepeatThreshold() {
        List<CallerScore.Entry> entries = Arrays.asList(entry(360001, true), entry(180000, true), entry(0, true));
        CallerScore.Result result = score(entries);
        assertEquals(1, result.repeats);
        assertEquals(Integer.valueOf(98), result.points);
        assertEquals(360001, entries.get(0).timeMillis);
    }
    @Test public void equalTimestampsCountOneRepeatPerSuccessor() {
        CallerScore.Result result = score(Arrays.asList(entry(10, true), entry(10, true), entry(10, true)));
        assertEquals(2, result.repeats);
        assertEquals(Integer.valueOf(96), result.points);
    }
    @Test public void repeatChainsCountAdjacentCallsRatherThanAllPairs() {
        CallerScore.Result result = score(Arrays.asList(entry(0, true), entry(120000, true), entry(240000, true), entry(360000, true)));
        assertEquals(3, result.repeats);
        assertEquals(Integer.valueOf(94), result.points);
    }
    @Test public void scoresStopAtZeroAndUseLongDeductions() {
        CallerScore.Result result = CallerScore.calculate(Arrays.asList(entry(0, false), entry(1, false), entry(2, false)), 0, 10, Integer.MAX_VALUE, Integer.MAX_VALUE, 1);
        assertEquals(Integer.valueOf(0), result.points);
        assertEquals(5L * Integer.MAX_VALUE, result.deductions);
    }
    @Test public void zeroWeightsPreserveOneHundredPoints() {
        CallerScore.Result result = CallerScore.calculate(Arrays.asList(entry(0, false), entry(1, false)), 0, 10, 0, 0, 1);
        assertEquals(Integer.valueOf(100), result.points);
    }
    @Test public void disturbancesCanOnlyLowerScore() {
        int previous = 100;
        List<CallerScore.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            entries.add(entry(i * 1000, false));
            CallerScore.Result result = score(entries);
            assertNotNull(result.points);
            int current = result.points;
            assertTrue(current <= previous);
            assertTrue(current >= 0);
            previous = current;
        }
    }
    @Test public void timestampDifferenceCannotOverflowIntoRepeat() {
        CallerScore.Result result = CallerScore.calculate(Arrays.asList(entry(Long.MIN_VALUE, true), entry(Long.MAX_VALUE - 1, true)), Long.MIN_VALUE, Long.MAX_VALUE, 5, 2, Integer.MAX_VALUE);
        assertEquals(0, result.repeats);
        assertEquals(Integer.valueOf(100), result.points);
    }
    @Test public void rejectsNegativeWeightsAndNonpositiveRepeatMinutes() {
        for (int[] params : new int[][]{{-1, 2, 3}, {5, -1, 3}, {5, 2, 0}, {5, 2, -1}}) {
            try {
                CallerScore.calculate(Collections.<CallerScore.Entry>emptyList(), 0, 10, params[0], params[1], params[2]);
                fail("Accepted invalid scoring parameters");
            } catch (IllegalArgumentException expected) { }
        }
    }
}
