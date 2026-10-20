package com.greatwall.callring

import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoreSnapshotProjectionTest {
    @Test fun saturatedAllTimeStatisticsStillCountBothExtremeTimestamps() = runBlocking {
        val fixture = ConversationFixtures()
        val fact = ClassificationFact(true,WorkReason.WORKING,null,false,"UTC",1,null,0)
        fixture.state.calls[1] = RawCallRecord(1,"999",Long.MIN_VALUE,1,CallResult.ANSWERED.toDomain(),CallDecision.NORMAL_RINGING.toDomain(),CallSource.SCREENING.toDomain(),null,null,true,fact.toDomain(),fact.toDomain())
        fixture.state.calls[2] = RawCallRecord(2,"999",Long.MAX_VALUE,2,CallResult.ANSWERED.toDomain(),CallDecision.NORMAL_RINGING.toDomain(),CallSource.SCREENING.toDomain(),null,null,true,fact.toDomain(),fact.toDomain())
        val snapshot = fixture.state.let { s -> com.greatwall.callring.application.conversation.query.projectCoreSnapshot(com.greatwall.callring.application.ports.store.FullSnapshotFacts(s.profiles,s.positions,s.calls,com.greatwall.callring.application.ports.store.WorkHistory(s.schedules,s.calendarYears,s.holidays,s.makeupDates,s.overrides,s.leaves,s.leaveVersion),s.statisticsIntervals,s.myPositionId,s.quiet,s.lastQuietMode,s.score,s.overlayEnabled,emptyList()),fixture.now,fixture.backend.runtime.getStatus().requiredValue(),fixture.appearance,fixture.ringtone) }
        val statistics = snapshot.getValue("D05") as AiJson.Obj
        val number = (statistics["numbers"] as AiJson.Arr).values.single() as AiJson.Obj
        assertEquals(2L,((number["totals"] as AiJson.Obj)["totalCalls"]).long())
        assertEquals(2L,((number["score"] as AiJson.Obj)["totalCalls"]).long())
    }
}
