package com.greatwall.callring

import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.contracts.callers.OverviewQuery
import com.greatwall.callring.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OutgoingStatisticsTest {
    @Test fun overviewRejectsOutgoingScoreAndQualityEvenWhenStoredEligibilityIsStale() = runBlocking {
        val f = ConversationFixtures()
        f.backend.settings.setStatisticsEnabled(true).requiredValue()
        f.backend.importSystemCalls(listOf(
            SystemCallRecord("incoming", "31234", f.now, 10, CallResult.ANSWERED),
            SystemCallRecord("outgoing", "31234", f.now + 1000, null, CallResult.UNRESOLVED,
                direction = CallDirection.OUTGOING),
        ))
        val outgoing = f.core.calls.values.single { it.logId == "outgoing" }
        f.core.calls[outgoing.id] = outgoing.copy(counted = true,
            classification = outgoing.classification.copy(calendarVerified = false))
        val overview = f.backend.callers.getOverview(OverviewQuery("31234")).requiredValue()
        assertEquals(1L, overview.lifetimeCallCount)
        assertEquals(1L, overview.rollingScore.totalCalls)
        assertFalse(overview.quality.hasUnknownDuration)
        assertFalse(overview.quality.hasUnresolvedRecords)
    }
}
