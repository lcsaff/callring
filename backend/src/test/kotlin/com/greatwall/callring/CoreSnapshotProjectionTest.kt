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
    @Test fun outgoingOnlyNumbersNeverEnterFullStatisticsSnapshot() = runBlocking {
        val fixture = ConversationFixtures()
        val fact = ClassificationFact(true, WorkReason.WORKING, null, false, "UTC", 1, null, 0).toDomain()
        fixture.core.calls[1] = RawCallRecord(1, "31234", 1000, 1,
            com.greatwall.callring.domain.model.CallResult.ANSWERED,
            com.greatwall.callring.domain.model.CallDecision.SYSTEM_LOG_ONLY,
            com.greatwall.callring.domain.model.CallSource.SYSTEM_CALL_LOG,
            "out", null, true, fact, fact,
            direction = com.greatwall.callring.domain.model.CallDirection.OUTGOING)
        val result = snapshot(fixture)
        val statistics = result.getValue("D05") as AiJson.Obj
        assertTrue((statistics["numbers"] as AiJson.Arr).values.isEmpty())
        assertEquals(1, (result.getValue("D04") as AiJson.Arr).values.size)
    }
    @Test fun recordingImportDaysRemainsLocalWithoutChangingD13Protocol() {
        val view = com.greatwall.callring.contracts.recording.RecordingSettingsView(false, false,
            com.greatwall.callring.contracts.recording.CaptureMode.SYSTEM_ONLY, false, 1)
        assertEquals(com.greatwall.callring.application.conversation.query.safeValue(view),
            com.greatwall.callring.application.conversation.query.safeValue(view.copy(systemImportDays = 30)))
    }

    @Test fun numberRuleSettingsHaveExactSafeProjection() {
        val saved = com.greatwall.callring.contracts.settings.NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("8",6)),17)
        assertEquals(aiObject("rules" to AiJson.Arr(listOf(aiObject("prefix" to aiString("8"), "length" to aiNumber(6)))), "revision" to aiNumber(17)),
            com.greatwall.callring.application.conversation.query.safeValue(saved))
    }

    @Test fun fullConfigurationExposesCapturedRuleRevision() = runBlocking {
        val fixture = ConversationFixtures()
        fixture.core.numberRuleSettings = com.greatwall.callring.contracts.settings.NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("8",6)),1)
        assertEquals(aiObject("rules" to AiJson.Arr(listOf(aiObject("prefix" to aiString("8"), "length" to aiNumber(6)))), "revision" to aiNumber(1)),
            (snapshot(fixture).getValue("D10") as AiJson.Obj)["numberRule"])
    }

    @Test fun saturatedAllTimeStatisticsStillCountBothExtremeTimestamps() = runBlocking {
        val fixture = ConversationFixtures()
        val fact = ClassificationFact(true,WorkReason.WORKING,null,false,"UTC",1,null,0)
        fixture.core.calls[1] = RawCallRecord(1,"999",Long.MIN_VALUE,1,CallResult.ANSWERED.toDomain(),CallDecision.NORMAL_RINGING.toDomain(),CallSource.SCREENING.toDomain(),null,null,true,fact.toDomain(),fact.toDomain())
        fixture.core.calls[2] = RawCallRecord(2,"999",Long.MAX_VALUE,2,CallResult.ANSWERED.toDomain(),CallDecision.NORMAL_RINGING.toDomain(),CallSource.SCREENING.toDomain(),null,null,true,fact.toDomain(),fact.toDomain())
        val snapshot = snapshot(fixture)
        val statistics = snapshot.getValue("D05") as AiJson.Obj
        val number = (statistics["numbers"] as AiJson.Arr).values.single() as AiJson.Obj
        assertEquals(2L,((number["totals"] as AiJson.Obj)["totalCalls"]).long())
        assertEquals(2L,((number["score"] as AiJson.Obj)["totalCalls"]).long())
    }

    private suspend fun snapshot(fixture: ConversationFixtures) = fixture.core.let { s -> com.greatwall.callring.application.conversation.query.projectCoreSnapshot(com.greatwall.callring.application.ports.store.FullSnapshotFacts(s.people,s.roles,s.calls,com.greatwall.callring.application.ports.store.WorkHistory(s.decision.schedules,s.decision.calendarYears,s.decision.holidays,s.decision.makeupDates,s.decision.overrides,s.decision.leaves,s.decision.leaveVersion),s.decision.intervals,s.selectedRole,s.decision.quiet,s.lastMode,s.scoreSettings,emptyList(),s.numberRuleSettings),fixture.now,fixture.backend.runtime.getStatus().requiredValue(),fixture.appearance,fixture.ringtone) }
}
