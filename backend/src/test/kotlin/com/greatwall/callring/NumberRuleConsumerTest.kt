package com.greatwall.callring

import com.greatwall.callring.domain.calling.NumberMatcher
import com.greatwall.callring.domain.model.SystemCallRecord
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.application.mapping.requiredValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.calling.*
import com.greatwall.callring.contracts.command.CommandContext
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

class NumberRuleConsumerTest {
    @Test fun injectedStrategiesControlEveryConsumer() = runBlocking {
        for (accept in listOf(false, true)) {
            val f = ConversationFixtures(NumberMatcher { n, _ -> accept && n.all { it in '0'..'9' } })
            f.backend.settings.setStatisticsEnabled(true).requiredValue()
            for ((index, number) in listOf("31234", "812345").withIndex()) {
                assertEquals(accept, f.backend.checkNumber(number).matchesRule)
                assertEquals(accept, f.backend.callers.getOverview(OverviewQuery(number)).requiredValue().numberCheck.matchesRule)
                val assessed = f.backend.refreshDecisionSnapshot().evaluate(number, f.now)
                assertEquals(accept, assessed.matched)
                assertEquals(accept, assessed.statisticsEnabled)
                assertEquals(accept, f.backend.recordIncoming(number, f.now + index, CallDecision.NORMAL_RINGING) > 0)
                assertEquals(if (accept) setOf(number) else emptySet<String>(), f.backend.importSystemCalls(listOf(
                    SystemCallRecord("log$index", number, f.now + 100 + index, 3, com.greatwall.callring.domain.model.CallResult.ANSWERED))))
            }
        }
    }
    @Test fun firstFactSurvivesRuleChangeButOtherDecisionInputsAreLatest() = runBlocking {
        for (originalMatched in listOf(true, false)) {
            val f = ConversationFixtures()
            val number = if (originalMatched) "31234" else "812345"
            f.backend.settings.setStatisticsEnabled(true).requiredValue()
            f.backend.settings.setOverlayEnabled(true).requiredValue()
            val first = f.backend.refreshDecisionSnapshot().evaluate(number, f.now)
            assertEquals(originalMatched, first.matched)
            val base = f.backend.settings.getNumberRule().requiredValue()
            f.backend.settings.saveNumberRule(SaveNumberRule(base,"8",6)).requiredValue()
            val latest = f.backend.decisionSnapshot()
            assertEquals(1L, latest.inputs.numberRule.revision)
            assertEquals(!originalMatched, latest.evaluate(number,f.now).matched)
            assertEquals(first.numberMatch, latest.evaluate(number,f.now,first.numberMatch).numberMatch)
            f.backend.settings.saveQuietHours(QuietSettings(QuietMode.NON_WORK,false,emptyMap())).requiredValue()
            var again = f.backend.decisionSnapshot().evaluate(number,f.now,first.numberMatch)
            assertFalse(again.classification.working)
            assertEquals(originalMatched,again.silence)
            assertEquals(originalMatched,again.overlayEnabled)
            assertEquals(originalMatched,again.statisticsEnabled)
            f.backend.settings.setOverlayEnabled(false).requiredValue()
            again = f.backend.decisionSnapshot().evaluate(number,f.now,first.numberMatch)
            assertFalse(again.overlayEnabled)
            f.backend.settings.saveQuietHours(QuietSettings(QuietMode.NON_WORK,true,emptyMap())).requiredValue()
            assertTrue(f.backend.decisionSnapshot().evaluate(number,f.now,first.numberMatch).silence)
            f.backend.callers.setWhitelisted(number,true).requiredValue()
            again = f.backend.decisionSnapshot().evaluate(number,f.now,first.numberMatch)
            assertTrue(again.whitelisted)
            assertFalse(again.silence)
            assertEquals(first.numberMatch,again.numberMatch)
            // Writer consumes the first fact and rechecks only the event-time statistics interval.
            assertEquals(originalMatched, f.backend.recordIncoming(number,f.now,CallDecision.NORMAL_RINGING,
                numberMatch=first.numberMatch)>0)
        }
    }

    @Test fun matcherSurvivesMirrorPatchesRefreshAndCommandBindings() = runBlocking {
        var invocations = 0
        val f = ConversationFixtures(NumberMatcher { _, _ -> invocations++; false })
        f.backend.refreshDecisionSnapshot()
        val base = f.backend.settings.getNumberRule().requiredValue()
        val bound = f.backend.commandApi(CommandContext("bound-rule",f.coordinator.capture(),0) { true })
        bound.settings.saveNumberRule(SaveNumberRule(base,"8",6)).requiredValue()
        f.backend.settings.setOverlayEnabled(true).requiredValue()
        assertFalse(f.backend.decisionSnapshot().evaluate("812345",f.now).matched)
        assertFalse(f.backend.refreshDecisionSnapshot().evaluate("812345",f.now).matched)
        assertFalse(bound.checkNumber("812345").matchesRule)
        assertFalse(f.backend.callers.getOverview(OverviewQuery("812345")).requiredValue().numberCheck.matchesRule)
        val before = invocations
        val first = NumberMatchFact(0,true)
        assertTrue(f.backend.decisionSnapshot().evaluate("31234",f.now,first).matched)
        assertEquals("same-call re-evaluation must never invoke matcher",before,invocations)
    }

    @Test fun wholeImportUsesOneRuleInsideFenceAndNextBatchUsesCommittedRule() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saveStarted = CountDownLatch(1)
        val pause = AtomicBoolean(true)
        val f = ConversationFixtures(NumberMatcher { n, rule ->
            if (pause.compareAndSet(true,false)) {
                entered.countDown()
                check(release.await(5,TimeUnit.SECONDS))
            }
            PrefixLengthNumberMatcher.matches(n,rule)
        })
        runBlocking { f.backend.settings.setStatisticsEnabled(true).requiredValue() }
        val base = runBlocking { f.backend.settings.getNumberRule().requiredValue() }
        fun batch(prefix: String) = listOf("31234","812345").mapIndexed { i,n ->
            SystemCallRecord("$prefix-$i",n,f.now+i,3,com.greatwall.callring.domain.model.CallResult.ANSWERED)
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val importing = executor.submit<Set<String>> { f.backend.importSystemCalls(batch("old")) }
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            val saving = executor.submit { saveStarted.countDown(); runBlocking {
                f.backend.settings.saveNumberRule(SaveNumberRule(base,"8",6)).requiredValue()
            } }
            assertTrue(saveStarted.await(3,TimeUnit.SECONDS))
            try { saving.get(150,TimeUnit.MILLISECONDS); fail("save bypassed import fence") }
            catch (_: TimeoutException) { }
            assertEquals(base,f.core.numberRuleSettings)
            release.countDown()
            assertEquals(setOf("31234"),importing.get(3,TimeUnit.SECONDS))
            saving.get(3,TimeUnit.SECONDS)
            assertEquals(setOf("812345"),f.backend.importSystemCalls(batch("new")))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS))
        }
    }

}
