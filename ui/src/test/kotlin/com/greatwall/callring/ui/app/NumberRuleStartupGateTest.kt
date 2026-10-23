package com.greatwall.callring.ui.app

import com.greatwall.callring.contracts.callers.NumberCheck
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.NumberRuleSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NumberRuleStartupGateTest {
    @Test fun reportAndEditorWaitForPreparationBeforeChecking() = runTest {
        for (target in listOf(UiDestination.Report("8-12345"), UiDestination.EditCaller("8-12345"))) {
            val ready = CompletableDeferred<ApiResult<NumberRuleSettings>>()
            var checks = 0
            val gate = NumberRuleStartupGate(this, { ready.await() }, { checks++; NumberCheck("812345", true, true) })
            gate.open(target); runCurrent()
            assertTrue(gate.state.value.loading)
            assertEquals(target, gate.state.value.pending)
            assertNull(gate.state.value.destination)
            assertEquals(0, checks)
            ready.complete(ApiResult.Success(NumberRuleSettings("8", 6, 1))); runCurrent()
            assertEquals(1, checks)
            assertEquals(if (target is UiDestination.Report) UiDestination.Report("812345") else UiDestination.EditCaller("812345"), gate.state.value.destination)
            gate.close()
        }
    }

    @Test fun failureRetainsRawTargetAndRetryContinues() = runTest {
        var attempt = 0; var checks = 0
        val gate = NumberRuleStartupGate(this, {
            if (++attempt == 1) ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "storage.failure"))
            else ApiResult.Success(NumberRuleSettings("8", 6, 1))
        }, { checks++; NumberCheck("812345", true, true) })
        val raw = UiDestination.Report("8-12345")
        gate.open(raw); runCurrent()
        assertEquals(raw, gate.state.value.pending)
        assertNotNull(gate.state.value.error)
        assertNull(gate.state.value.destination)
        assertEquals(0, checks)
        gate.retry(); runCurrent()
        assertEquals(UiDestination.Report("812345"), gate.state.value.destination)
        assertEquals(1, checks)
        gate.close()
    }

    @Test fun latestIntentAndCloseRejectLateResults() = runTest {
        val blocked = CompletableDeferred<ApiResult<NumberRuleSettings>>()
        var reads = 0; var checks = 0
        val gate = NumberRuleStartupGate(this, {
            if (++reads == 1) withContext(NonCancellable) { blocked.await() }
            else ApiResult.Success(NumberRuleSettings("8", 6, 1))
        }, { checks++; NumberCheck(it, true, true) })
        gate.open(UiDestination.Report("31234")); runCurrent()
        gate.open(UiDestination.Lookup); runCurrent()
        assertEquals(UiDestination.Lookup, gate.state.value.destination)
        blocked.complete(ApiResult.Success(NumberRuleSettings("3", 5, 0))); runCurrent()
        assertEquals(UiDestination.Lookup, gate.state.value.destination)
        assertEquals(0, checks)
        gate.close()
        gate.open(UiDestination.Report("812345")); runCurrent()
        assertEquals(2, reads)
        assertEquals(0, checks)
    }

    @Test fun closeDuringPreparationNeverChecksOrPublishesDestination() = runTest {
        val blocked = CompletableDeferred<ApiResult<NumberRuleSettings>>()
        var checks = 0
        val gate = NumberRuleStartupGate(this, { withContext(NonCancellable) { blocked.await() } }, { checks++; NumberCheck(it, true, true) })
        gate.open(UiDestination.EditCaller("812345")); runCurrent()
        val before = gate.state.value
        gate.close()
        blocked.complete(ApiResult.Success(NumberRuleSettings("8", 6, 1))); runCurrent()
        assertEquals(before, gate.state.value)
        assertEquals(0, checks)
    }
}
