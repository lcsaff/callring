package com.greatwall.callring

import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.domain.CallCorrelation
import com.greatwall.callring.domain.RawCallRecord
import com.greatwall.callring.domain.model.CallDecision
import com.greatwall.callring.domain.model.CallResult
import com.greatwall.callring.domain.model.CallSource
import com.greatwall.callring.domain.model.ClassificationFact
import com.greatwall.callring.domain.model.WorkReason
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
        val calls = linkedMapOf<Long, RawCallRecord>()
        calls[1] = screen(1, 10000)
        calls[2] = screen(2, 11000)
        calls[3] = provider(3, 10500)
        val originalFacts = calls.mapValues { it.value.originalClassification }
        CallCorrelation.reconcile(calls.values.filter { it.number == "30001" }).forEach {
            (_, after) ->
            calls[after.id] = after
        }
        assertNull(calls.getValue(3).linkedScreenId)
        for (id in listOf(1L, 2L)) {
            val call = calls.getValue(id)
            assertFalse(call.hidden)
            assertFalse(call.counted)
            assertEquals(CallResult.UNRESOLVED, call.result)
        }
        assertEquals(originalFacts, calls.mapValues { it.value.originalClassification })
    }

    @Test
    fun unknownScreeningAccountDoesNotChooseBetweenDifferentKnownAccounts() {
        val calls = linkedMapOf<Long, RawCallRecord>()
        calls[1] = screen(1, 10000, null)
        calls[2] = provider(2, 10000, "A")
        calls[3] = provider(3, 10000, "B")
        CallCorrelation.reconcile(calls.values.filter { it.number == "30001" }).forEach {
            (_, after) ->
            calls[after.id] = after
        }
        val unresolved = calls.getValue(1)
        assertEquals(CallResult.UNRESOLVED, unresolved.result)
        assertFalse(unresolved.hidden)
        assertFalse(unresolved.counted)
        assertNull(calls.getValue(2).linkedScreenId)
        assertNull(calls.getValue(3).linkedScreenId)
        assertTrue(calls.getValue(2).counted)
        assertTrue(calls.getValue(3).counted)
    }

    @Test
    fun knownAccountsAllowIndependentMutuallyUniquePairsAtTheSameTime() {
        val calls = linkedMapOf<Long, RawCallRecord>()
        calls[1] = screen(1, 10000, "A")
        calls[2] = screen(2, 10000, "B")
        calls[3] = provider(3, 10000, "A")
        calls[4] = provider(4, 10000, "B")
        CallCorrelation.reconcile(calls.values.filter { it.number == "30001" }).forEach {
            (_, after) ->
            calls[after.id] = after
        }
        assertEquals(1L, calls.getValue(3).linkedScreenId)
        assertEquals(2L, calls.getValue(4).linkedScreenId)
        assertTrue(calls.getValue(1).hidden)
        assertTrue(calls.getValue(2).hidden)
        assertEquals(2, calls.values.count { !it.hidden && it.counted })
    }

    @Test
    fun candidateDegreeIncludesRowsBeyondTheBoundedWriteWindow() {
        val calls = linkedMapOf<Long, RawCallRecord>()
        calls[1] = screen(1, 10000)
        calls[2] = provider(2, 15000)
        calls[3] = screen(3, 20000)
        val untouched = calls.getValue(3)
        // Center 5000 writes through 15000; screen 20000 still contributes to provider degree.
        CallCorrelation.reconcile(calls.values.filter { it.number == "30001" }, listOf(5000))
            .forEach { (_, after) -> calls[after.id] = after }
        assertEquals(CallResult.UNRESOLVED, calls.getValue(1).result)
        assertNull(calls.getValue(2).linkedScreenId)
        assertEquals(untouched, calls.getValue(3))
    }
}
