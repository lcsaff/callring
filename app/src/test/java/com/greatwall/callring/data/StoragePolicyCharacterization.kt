package com.greatwall.callring.data

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.CallCorrelation
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class StoragePolicyCharacterization {
    private val fact = ClassificationFact(true, WorkReason.WORKING, null, true, "Asia/Shanghai", 1, "2026.1", 0)
    private fun row(id: Long, at: Long, screen: Boolean, account: String? = null) = RawCallRecord(
        id, "31234", at, if (screen) null else 5, if (screen) CallResult.PENDING else CallResult.ANSWERED,
        if (screen) CallDecision.WHITELIST_ALLOWED else CallDecision.SYSTEM_LOG_ONLY,
        if (screen) CallSource.SCREENING else CallSource.SYSTEM_CALL_LOG, if (screen) null else "$id",
        if (screen) "31234:$at" else null, true, fact, fact, account)

    @Test fun ambiguityOutsideAffectedWindowPreventsAssociation() {
        val state = BackendState()
        listOf(row(1, 6000, true), row(2, 16000, true), row(3, 11000, false)).forEach { state.calls[it.id] = it }
        CallCorrelation.reconcile(state, "31234", 1000)
        assertNull(state.calls.getValue(3).linkedScreenId)
        assertFalse(state.calls.getValue(1).counted)
    }

    @Test fun unknownAccountCannotChooseBetweenTwoKnownAccounts() {
        val state = BackendState()
        listOf(row(1, 1000, true), row(2, 1000, false, "a"), row(3, 1000, false, "b")).forEach { state.calls[it.id] = it }
        CallCorrelation.reconcile(state, "31234")
        assertFalse(state.calls.getValue(1).counted)
        assertEquals(CallResult.UNRESOLVED, state.calls.getValue(1).result)
        assertNull(state.calls.getValue(2).linkedScreenId)
    }

    @Test fun associationPreservesOriginalScreeningFactsAndBecomesReversible() {
        val state = BackendState()
        val screen = row(1, 1000, true)
        state.calls[1] = screen; state.calls[2] = row(2, 1100, false)
        CallCorrelation.reconcile(state, "31234", 1000)
        assertTrue(state.calls.getValue(1).hidden)
        assertEquals(1L, state.calls.getValue(2).linkedScreenId)
        assertEquals(screen.originalClassification, state.calls.getValue(1).originalClassification)
        state.calls[2] = state.calls.getValue(2).copy(time = 8000)
        CallCorrelation.reconcile(state, "31234", 1100)
        assertFalse(state.calls.getValue(1).hidden)
        assertTrue(state.calls.getValue(1).counted)
        assertNull(state.calls.getValue(2).linkedScreenId)
    }

    @Test fun legacyValuesDecodeWithoutDiscardingUnknownStrings() {
        assertEquals(CallResult.ANSWERED, LegacyValueCodec.result("已接听"))
        assertEquals(CallResult.UNRESOLVED, LegacyValueCodec.result("待核对·未计入"))
        assertEquals(CallDecision.CUSTOM_RINGTONE, LegacyValueCodec.decision("规则铃声"))
        assertEquals(CallDecision.SYSTEM_RINGTONE_FALLBACK, LegacyValueCodec.decision("音频准备超时，保留系统铃声"))
        assertEquals(CallSource.MERGED, LegacyValueCodec.source("筛选＋系统通话记录"))
        assertEquals(WorkReason.HOLIDAY, LegacyValueCodec.reason("节假日·国庆节"))
        assertEquals("国庆节", LegacyValueCodec.holidayName("节假日·国庆节"))
        assertEquals("旧版未知结果", LegacyValueCodec.encodeResult(CallResult.UNKNOWN, "旧版未知结果"))
    }

    @Test fun multipleCentersShareDegreesAndLeaveUnrelatedRowsUntouched() {
        val state = BackendState()
        listOf(row(1, 1000, true), row(2, 1100, false), row(3, 61000, true), row(4, 61100, false),
            row(5, 121000, true)).forEach { state.calls[it.id] = it }
        val untouched = state.calls.getValue(5)
        CallCorrelation.reconcile(state, "31234", listOf(1000, 61000))
        assertEquals(1L, state.calls.getValue(2).linkedScreenId)
        assertEquals(3L, state.calls.getValue(4).linkedScreenId)
        assertSame(untouched, state.calls.getValue(5))
    }

    @Test fun timestampBoundsDoNotOverflow() {
        val state = BackendState()
        listOf(row(1, Long.MIN_VALUE, true), row(2, Long.MIN_VALUE + 5000, false),
            row(3, Long.MAX_VALUE, true), row(4, Long.MAX_VALUE - 5000, false)).forEach { state.calls[it.id] = it }
        CallCorrelation.reconcile(state, "31234", listOf(Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(1L, state.calls.getValue(2).linkedScreenId)
        assertEquals(3L, state.calls.getValue(4).linkedScreenId)
    }

    @Test fun calendarReadHonorsByteLimitAndRemovesOnlyLeadingBom() {
        assertEquals("value", WorkConfigCodec.readLimited(ByteArrayInputStream("\uFEFFvalue".toByteArray())))
        assertEquals(1024 * 1024, WorkConfigCodec.readLimited(ByteArrayInputStream(ByteArray(1024 * 1024) { 32 })).length)
        assertThrows(IllegalArgumentException::class.java) { WorkConfigCodec.readLimited(ByteArrayInputStream(ByteArray(1024 * 1024 + 1))) }
    }

    @Test fun calendarReadRejectsMalformedUtf8() {
        assertThrows(IllegalArgumentException::class.java) { WorkConfigCodec.readLimited(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
    }
}
