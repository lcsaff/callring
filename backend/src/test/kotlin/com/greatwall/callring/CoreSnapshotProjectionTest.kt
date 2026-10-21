package com.greatwall.callring

import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.domain.model.RawCallRecord

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
        fixture.core.calls[1] = RawCallRecord(1,"999",Long.MIN_VALUE,1,CallResult.ANSWERED.toDomain(),CallDecision.NORMAL_RINGING.toDomain(),CallSource.SCREENING.toDomain(),null,null,true,fact.toDomain(),fact.toDomain())
        fixture.core.calls[2] = RawCallRecord(2,"999",Long.MAX_VALUE,2,CallResult.ANSWERED.toDomain(),CallDecision.NORMAL_RINGING.toDomain(),CallSource.SCREENING.toDomain(),null,null,true,fact.toDomain(),fact.toDomain())
        val snapshot = fixture.core.let { s -> com.greatwall.callring.application.conversation.query.projectCoreSnapshot(com.greatwall.callring.application.ports.store.FullSnapshotFacts(s.people,s.roles,s.calls,com.greatwall.callring.application.ports.store.WorkHistory(s.decision.schedules,s.decision.calendarYears,s.decision.holidays,s.decision.makeupDates,s.decision.overrides,s.decision.leaves,s.decision.leaveVersion),s.decision.intervals,s.selectedRole,s.decision.quiet,s.lastMode,s.scoreSettings,s.decision.overlay,emptyList()),fixture.now,fixture.backend.runtime.getStatus().requiredValue(),fixture.appearance,fixture.ringtone) }
        val statistics = snapshot.getValue("D05") as AiJson.Obj
        val number = (statistics["numbers"] as AiJson.Arr).values.single() as AiJson.Obj
        assertEquals(2L,((number["totals"] as AiJson.Obj)["totalCalls"]).long())
        assertEquals(2L,((number["score"] as AiJson.Obj)["totalCalls"]).long())
    }
}
