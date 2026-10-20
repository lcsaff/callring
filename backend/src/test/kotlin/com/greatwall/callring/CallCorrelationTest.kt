package com.greatwall.callring

import com.greatwall.callring.domain.model.CallResult
import com.greatwall.callring.domain.model.CallDecision
import com.greatwall.callring.domain.model.CallSource
import com.greatwall.callring.domain.model.ClassificationFact
import com.greatwall.callring.domain.model.WorkReason
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.domain.BackendState
import com.greatwall.callring.domain.CallCorrelation
import com.greatwall.callring.domain.RawCallRecord
import org.junit.Assert.*
import org.junit.Test

class CallCorrelationTest {
    private val fact = ClassificationFact(true, WorkReason.WORKING, null, true, "UTC", 1, "v1", 0)

    private fun screen(id: Long, time: Long, account: String? = null) =
        RawCallRecord(
            id,
            "30001",
            time,
            null,
            CallResult.PENDING,
            CallDecision.CUSTOM_RINGTONE,
            CallSource.SCREENING,
            null,
            "screen-$id",
            true,
            fact,
            fact,
            account,
        )

    private fun provider(id: Long, time: Long, account: String? = null) =
        RawCallRecord(
            id,
            "30001",
            time,
            0,
            CallResult.MISSED,
            CallDecision.SYSTEM_LOG_ONLY,
            CallSource.SYSTEM_CALL_LOG,
            "provider-$id",
            null,
            true,
            fact,
            fact,
            account,
        )

    @Test
    fun oneCandidateIsInsufficientWhenTheReverseSideHasMultipleCandidates() {
        val state = BackendState()
        state.calls[1] = screen(1, 10000)
        state.calls[2] = screen(2, 11000)
        state.calls[3] = provider(3, 10500)
        val originalFacts = state.calls.mapValues { it.value.originalClassification }
        CallCorrelation.reconcile(state, "30001")
        assertNull(state.calls.getValue(3).linkedScreenId)
        for (id in listOf(1L, 2L)) {
            val call = state.calls.getValue(id)
            assertFalse(call.hidden)
            assertFalse(call.counted)
            assertEquals(CallResult.UNRESOLVED, call.result)
        }
        assertEquals(originalFacts, state.calls.mapValues { it.value.originalClassification })
    }

    @Test
    fun unknownScreeningAccountDoesNotChooseBetweenDifferentKnownAccounts() {
        val state = BackendState()
        state.calls[1] = screen(1, 10000, null)
        state.calls[2] = provider(2, 10000, "A")
        state.calls[3] = provider(3, 10000, "B")
        CallCorrelation.reconcile(state, "30001")
        val unresolved = state.calls.getValue(1)
        assertEquals(CallResult.UNRESOLVED, unresolved.result)
        assertFalse(unresolved.hidden)
        assertFalse(unresolved.counted)
        assertNull(state.calls.getValue(2).linkedScreenId)
        assertNull(state.calls.getValue(3).linkedScreenId)
        assertTrue(state.calls.getValue(2).counted)
        assertTrue(state.calls.getValue(3).counted)
    }

    @Test
    fun knownAccountsAllowIndependentMutuallyUniquePairsAtTheSameTime() {
        val state = BackendState()
        state.calls[1] = screen(1, 10000, "A")
        state.calls[2] = screen(2, 10000, "B")
        state.calls[3] = provider(3, 10000, "A")
        state.calls[4] = provider(4, 10000, "B")
        CallCorrelation.reconcile(state, "30001")
        assertEquals(1L, state.calls.getValue(3).linkedScreenId)
        assertEquals(2L, state.calls.getValue(4).linkedScreenId)
        assertTrue(state.calls.getValue(1).hidden)
        assertTrue(state.calls.getValue(2).hidden)
        assertEquals(2, state.calls.values.count { !it.hidden && it.counted })
    }

    @Test
    fun candidateDegreeIncludesRowsBeyondTheBoundedWriteWindow() {
        val state = BackendState()
        state.calls[1] = screen(1, 10000)
        state.calls[2] = provider(2, 15000)
        state.calls[3] = screen(3, 20000)
        val untouched = state.calls.getValue(3)
        // Center 5000 writes through 15000; screen 20000 still contributes to provider degree.
        CallCorrelation.reconcile(state, "30001", listOf(5000))
        assertEquals(CallResult.UNRESOLVED, state.calls.getValue(1).result)
        assertNull(state.calls.getValue(2).linkedScreenId)
        assertEquals(untouched, state.calls.getValue(3))
    }
}
