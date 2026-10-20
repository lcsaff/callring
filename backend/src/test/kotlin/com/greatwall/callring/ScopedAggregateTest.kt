package com.greatwall.callring

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
}
