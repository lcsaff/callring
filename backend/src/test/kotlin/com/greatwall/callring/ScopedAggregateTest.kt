package com.greatwall.callring

import com.greatwall.callring.domain.model.ScoreSettings
import com.greatwall.callring.domain.model.CallResult
import com.greatwall.callring.domain.model.TimeRange
import com.greatwall.callring.domain.report.*
import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import org.junit.Assert.*
import org.junit.Test

class ScopedAggregateTest {
    @Test fun streamingScoreKeepsPreviousCountedAcrossPageBoundaries() {
        val range=TimeRange(0,1000000,"UTC")
        val accumulator=StreamingReportAccumulator(ScoreSettings(5,2,10,30),range)
        accumulator.accept(1000,true,false,60,CallResult.ANSWERED)
        accumulator.accept(61000,true,true,60,CallResult.ANSWERED)
        accumulator.accept(70000,false,false,500,CallResult.ANSWERED)
        accumulator.accept(900000,true,false,null,CallResult.MISSED)
        val score=accumulator.score()
        assertEquals(3L,score.totalCalls);assertEquals(2L,score.offHoursCalls)
        assertEquals(1L,score.repeatedCalls);assertEquals(88,score.points)
        assertEquals(120L,accumulator.totals().knownDurationSeconds)
    }
    @Test fun rangeExcludesPreviousCallsAndRepeatThresholdIsInclusive() {
        val accumulator = StreamingReportAccumulator(ScoreSettings(5, 2, 10, 30), TimeRange(1000, 1000000, "UTC"))
        accumulator.accept(999, true, false, 100, CallResult.MISSED)
        accumulator.accept(1000, true, true, 1, CallResult.ANSWERED)
        accumulator.accept(601000, true, true, 1, CallResult.ANSWERED)
        accumulator.accept(1000000, true, false, 100, CallResult.MISSED)
        assertEquals(2L, accumulator.score().totalCalls)
        assertEquals(1L, accumulator.score().repeatedCalls)
        assertEquals(98, accumulator.score().points)
        assertEquals(2L, accumulator.totals().knownDurationSeconds)
        assertEquals(0L, accumulator.totals().missedCount)
    }
    @Test fun emptyRangeHasNullableScoreAndMaximumTimestampRequiresOptIn() {
        val settings = ScoreSettings(5, 2, 10, 30)
        val range = TimeRange(Long.MAX_VALUE - 1, Long.MAX_VALUE, "UTC")
        val excluded = StreamingReportAccumulator(settings, range)
        excluded.accept(Long.MAX_VALUE, true, false, null, CallResult.PENDING)
        assertNull(excluded.score().points)
        assertEquals(0L, excluded.totals().pendingCount)
        val included = StreamingReportAccumulator(settings, range, includeSaturatedMax = true)
        included.accept(Long.MAX_VALUE, true, false, null, CallResult.PENDING)
        assertEquals(95, included.score().points)
        assertEquals(1L, included.totals().unknownDurationCount)
        assertEquals(1L, included.totals().pendingCount)
    }
    @Test fun totalsSaturateAndUncountedPendingDoesNotAffectScore() {
        val accumulator = StreamingReportAccumulator(ScoreSettings(500, 200, 10, 30), TimeRange(0, 1000, "UTC"))
        accumulator.accept(1, true, false, Long.MAX_VALUE, CallResult.REJECTED)
        accumulator.accept(2, true, false, 10, CallResult.BLOCKED)
        accumulator.accept(3, false, false, 10, CallResult.UNRESOLVED)
        assertEquals(0, accumulator.score().points)
        assertEquals(Long.MAX_VALUE, accumulator.totals().knownDurationSeconds)
        assertEquals(2L, accumulator.totals().rejectedCount)
        assertEquals(1L, accumulator.totals().pendingCount)
        assertEquals(2L, accumulator.score().totalCalls)
    }
    @Test(expected = IllegalArgumentException::class)
    fun countedInputsMustRemainAscending() {
        val accumulator = StreamingReportAccumulator(ScoreSettings(5, 2, 10, 30), TimeRange(0, 1000, "UTC"))
        accumulator.accept(2, true, true, 1, CallResult.ANSWERED)
        accumulator.accept(1, true, true, 1, CallResult.ANSWERED)
    }
}
