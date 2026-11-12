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

    @Test fun allHistoryIsVisibleWithStatisticsDisabledAndWithoutRuleMatch() {
        val f = ConversationFixtures()
        val records = listOf(record("old", "13800138000", 1), record("rule", "31234", f.now))
        assertEquals(setOf("13800138000", "31234"), f.backend.importSystemCalls(records))
        assertEquals(2, f.core.calls.size)
        assertTrue(f.core.calls.values.all { !it.hidden && !it.counted })
        assertTrue(f.backend.importSystemCalls(records).isEmpty())
    }

    @Test fun historyOutsideIntervalsOrRulesDoesNotEnterScore() = runBlocking {
        val f = ConversationFixtures()
        f.backend.settings.setStatisticsEnabled(true).requiredValue()
        f.backend.importSystemCalls(listOf(record("old", "31234", f.now - 1),
            record("current", "31234", f.now), record("other", "13800138000", f.now)))
        assertEquals(3, f.core.calls.size)
        assertEquals(listOf("current"), f.core.calls.values.filter { it.counted }.map { it.logId })
        val overview = f.backend.callers.getOverview(OverviewQuery("31234")).requiredValue()
        assertEquals(1L, overview.lifetimeCallCount)
        assertEquals(1L, overview.rollingScore.totalCalls)
    }

    @Test fun existingCountedFactsSurviveRuleChangesAndProviderCorrections() = runBlocking {
        val f = ConversationFixtures()
        f.backend.settings.setStatisticsEnabled(true).requiredValue()
        val records = listOf(record("counted", "31234", f.now), record("uncounted", "13800138000", f.now))
        f.backend.importSystemCalls(records)
        val rule = f.backend.settings.getNumberRule().requiredValue()
        f.backend.settings.saveNumberRule(SaveNumberRule(rule, emptyList())).requiredValue()
        f.backend.importSystemCalls(records.map { it.copy(durationSeconds = 99) })
        assertEquals(mapOf("counted" to true, "uncounted" to false),
            f.core.calls.values.associate { it.logId to it.counted })
        assertTrue(f.core.calls.values.all { it.duration == 99L })
    }

    @Test fun laterProviderKeepsOriginalScreeningEligibilityAfterRuleChange() = runBlocking {
        val f = ConversationFixtures()
        f.backend.settings.setStatisticsEnabled(true).requiredValue()
        val screen = f.backend.recordIncoming("31234", f.now, CallDecision.NORMAL_RINGING)
        val rule = f.backend.settings.getNumberRule().requiredValue()
        f.backend.settings.saveNumberRule(SaveNumberRule(rule, emptyList())).requiredValue()
        f.backend.importSystemCalls(listOf(record("provider", "31234", f.now + 1000)))
        val provider = f.core.calls.values.single { it.logId != null }
        assertEquals(screen, provider.linkedScreenId)
        assertTrue(provider.counted)
        assertTrue(f.core.calls.getValue(screen).hidden)
        assertEquals(1, f.core.calls.values.count { !it.hidden && it.counted })
    }

    @Test fun deletedHistoryNeverRecreatesProfileButNewCallsCan() {
        val f = ConversationFixtures()
        f.core.deletedCallers["13800138000"] = f.now
        val records = listOf(record("old", "13800138000", f.now - 1),
            record("boundary", "13800138000", f.now))
        assertTrue(f.backend.importSystemCalls(records).isEmpty())
        assertTrue(f.core.calls.isEmpty())
        assertTrue(f.core.people.isEmpty())
        f.backend.importSystemCalls(listOf(record("new", "13800138000", f.now + 1)))
        assertEquals("new", f.core.calls.values.single().logId)
        assertTrue(f.core.people.containsKey("13800138000"))
        assertTrue(f.backend.importSystemCalls(records).isEmpty())
        assertEquals(1, f.core.calls.size)
    }

    @Test fun restrictedOrUnknownNumbersSkipOnlyTheirOwnRowsWithoutInvalidProfiles() {
        val f = ConversationFixtures()
        f.backend.importSystemCalls(listOf(record("restricted", "-1", 1), record("unknown", "", 2),
            record("unavailable", "-2", 3), record("payphone", "-3", 4),
            record("nonstandard", "private", 5), record("valid", "13800138000", 6)))
        assertEquals(1, f.core.calls.size)
        assertEquals("13800138000", f.core.calls.values.single().number)
        assertTrue(f.core.calls.values.all { !it.hidden && !it.counted })
        assertEquals(setOf("13800138000"), f.core.people.keys)
    }
}
