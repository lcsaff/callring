package com.greatwall.callring

import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.contracts.callers.CallDecision
import com.greatwall.callring.contracts.callers.OverviewQuery
import com.greatwall.callring.contracts.settings.SaveNumberRule
import com.greatwall.callring.domain.model.CallResult
import com.greatwall.callring.domain.model.SystemCallRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IncomingHistoryImportTest {
    private fun record(id: String, number: String, at: Long) =
        SystemCallRecord(id, number, at, 12, CallResult.ANSWERED)

    @Test fun onlyMatchedHistoryIsAdmittedWithoutAnyAgeLimit() {
        val f = ConversationFixtures()
        val records = listOf(record("old", "31234", 1), record("rejected", "13800138000", f.now))
        assertEquals(setOf("31234"), f.backend.importSystemCalls(records))
        assertEquals(1, f.core.calls.size)
        assertEquals(setOf("31234"), f.core.people.keys)
        assertEquals(1L, f.core.calls.values.single().time)
        assertFalse(f.core.calls.values.single().counted)
    }

    @Test fun emptyRulesAdmitNoNewProviderFactsAndKeepStoredFacts() = runBlocking {
        val f = ConversationFixtures()
        f.backend.importSystemCalls(listOf(record("admitted", "31234", 1)))
        val rule = f.backend.settings.getNumberRule().requiredValue()
        f.backend.settings.saveNumberRule(SaveNumberRule(rule, emptyList())).requiredValue()
        assertTrue(f.backend.importSystemCalls(listOf(record("new", "31234", 2))).isEmpty())
        assertEquals(listOf("admitted"), f.core.calls.values.map { it.logId })
    }

    @Test fun outgoingFactsAreVisibleUncountedAndNeverMergeIncomingScreening() = runBlocking {
        val f = ConversationFixtures()
        f.backend.settings.setStatisticsEnabled(true).requiredValue()
        val screen = f.backend.recordIncoming("31234", f.now, CallDecision.NORMAL_RINGING)
        f.backend.importSystemCalls(listOf(record("out", "31234", f.now).copy(direction = com.greatwall.callring.domain.model.CallDirection.OUTGOING)))
        val out = f.core.calls.values.single { it.logId == "out" }
        assertFalse(out.counted)
        assertNull(out.linkedScreenId)
        assertFalse(f.core.calls.getValue(screen).hidden)
        assertEquals(com.greatwall.callring.contracts.callers.CallDirection.OUTGOING,
            f.backend.callers.getCallDetail(out.id).requiredValue().direction)
    }
    @Test fun historyOutsideIntervalsOrRulesDoesNotEnterScore() = runBlocking {
        val f = ConversationFixtures()
        f.backend.settings.setStatisticsEnabled(true).requiredValue()
        f.backend.importSystemCalls(listOf(record("old", "31234", f.now - 1),
            record("current", "31234", f.now), record("other", "13800138000", f.now)))
        assertEquals(2, f.core.calls.size)
        assertEquals(listOf("current"), f.core.calls.values.filter { it.counted }.map { it.logId })
        val overview = f.backend.callers.getOverview(OverviewQuery("31234")).requiredValue()
        assertEquals(1L, overview.lifetimeCallCount)
        assertEquals(1L, overview.rollingScore.totalCalls)
    }

    @Test fun existingFactsStayUnchangedWhenCurrentRulesRejectProviderCorrections() = runBlocking {
        val f = ConversationFixtures()
        f.backend.settings.setStatisticsEnabled(true).requiredValue()
        val records = listOf(record("counted", "31234", f.now), record("uncounted", "31235", f.now - 1))
        f.backend.importSystemCalls(records)
        val rule = f.backend.settings.getNumberRule().requiredValue()
        f.backend.settings.saveNumberRule(SaveNumberRule(rule, emptyList())).requiredValue()
        f.backend.importSystemCalls(records.map { it.copy(durationSeconds = 99) })
        assertEquals(mapOf("counted" to true, "uncounted" to false),
            f.core.calls.values.associate { it.logId to it.counted })
        assertTrue(f.core.calls.values.all { it.duration == 12L })
    }

    @Test fun earlierScreeningDoesNotAdmitNewProviderFactsAfterRuleChange() = runBlocking {
        val f = ConversationFixtures()
        f.backend.settings.setStatisticsEnabled(true).requiredValue()
        val screen = f.backend.recordIncoming("31234", f.now, CallDecision.NORMAL_RINGING)
        val rule = f.backend.settings.getNumberRule().requiredValue()
        f.backend.settings.saveNumberRule(SaveNumberRule(rule, emptyList())).requiredValue()
        f.backend.importSystemCalls(listOf(record("provider", "31234", f.now + 1000)))
        assertFalse(f.core.calls.values.any { it.logId != null })
        assertFalse(f.core.calls.getValue(screen).hidden)
        assertTrue(f.core.calls.getValue(screen).counted)
    }

    @Test fun deletedHistoryNeverRecreatesProfileButNewCallsCan() {
        val f = ConversationFixtures()
        f.core.deletedCallers["31234"] = f.now
        val records = listOf(record("old", "31234", f.now - 1),
            record("boundary", "31234", f.now))
        assertTrue(f.backend.importSystemCalls(records).isEmpty())
        assertTrue(f.core.calls.isEmpty())
        assertTrue(f.core.people.isEmpty())
        f.backend.importSystemCalls(listOf(record("new", "31234", f.now + 1)))
        assertEquals("new", f.core.calls.values.single().logId)
        assertTrue(f.core.people.containsKey("31234"))
        assertTrue(f.backend.importSystemCalls(records).isEmpty())
        assertEquals(1, f.core.calls.size)
    }

    @Test fun restrictedOrUnknownNumbersSkipOnlyTheirOwnRowsWithoutInvalidProfiles() {
        val f = ConversationFixtures()
        f.backend.importSystemCalls(listOf(record("restricted", "-1", 1), record("unknown", "", 2),
            record("unavailable", "-2", 3), record("payphone", "-3", 4),
            record("nonstandard", "private", 5), record("valid", "31234", 6)))
        assertEquals(1, f.core.calls.size)
        assertEquals("31234", f.core.calls.values.single().number)
        assertTrue(f.core.calls.values.all { !it.hidden && !it.counted })
        assertEquals(setOf("31234"), f.core.people.keys)
    }
}
