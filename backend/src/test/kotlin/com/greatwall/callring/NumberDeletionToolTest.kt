package com.greatwall.callring

import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class NumberDeletionToolTest {
    @Test fun confirmedDeletionRemovesProfileAndCallsAndCancelledConfirmationKeepsData() = runBlocking {
        val f = ConversationFixtures()
        val fact = WorkClassifier.classify(f.core.decision, f.now)
        f.backend.callers.saveProfile(SaveCallerProfile("31234", "name", "dept", null, false)).requiredValue()
        f.core.calls[1] = RawCallRecord(1, "31234", f.now, 0, CallResult.ANSWERED.toDomain(), CallDecision.NORMAL_RINGING.toDomain(), CallSource.SCREENING.toDomain(), null, null, true, fact, fact)
        val foreground = ForegroundActionService(checkCommand = ForegroundCommandValidator(f.coordinator)::check)
        val host = foreground.attachHost()
        val executor = ToolExecutor({ f.backend }, f.coordinator, foreground, ForegroundResourceRegistry(), queries=unusedToolQueries(), documentWriteVerified={false})
        fun scope() = ToolExecutionContext("s", "t", 1, CommandContext(java.util.UUID.randomUUID().toString(), f.coordinator.capture(), 1) { true })
        fun call(id: String) = ConversationToolCall(id, "callers_deleteNumber", aiObject("number" to aiString("31234")))

        val cancelled = async { executor.execute(call("c1"), scope()) }
        val ticket = withTimeout(3000) { foreground.pending.first { it != null } }!!
        val target = ticket.confirmation
        assertTrue(target is ConfirmationTarget.NumberDeletion)
        assertEquals(1, (target as ConfirmationTarget.NumberDeletion).callCount)
        assertTrue(foreground.beginLaunch(ticket.requestId, host))
        foreground.deliver(ticket.requestId, ForegroundHostResult(ForegroundResultStatus.CANCELLED))
        assertEquals(ToolStatus.CANCELLED, cancelled.await().status)
        assertTrue(f.core.people.containsKey("31234"))
        assertTrue(f.core.calls.containsKey(1L))

        val confirmed = async { executor.execute(call("c2"), scope()) }
        val ticket2 = withTimeout(3000) { foreground.pending.first { it != null } }!!
        assertTrue(foreground.beginLaunch(ticket2.requestId, host))
        foreground.deliver(ticket2.requestId, ForegroundHostResult(ForegroundResultStatus.VERIFIED))
        val receipt = confirmed.await()
        assertEquals(ToolStatus.COMMITTED, receipt.status)
        assertFalse(f.core.people.containsKey("31234"))
        assertFalse(f.core.calls.containsKey(1L))
    }
}
