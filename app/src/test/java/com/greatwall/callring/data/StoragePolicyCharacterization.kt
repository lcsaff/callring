package com.greatwall.callring.data

import com.greatwall.callring.data.codec.LegacyValueCodec
import com.greatwall.callring.data.codec.WorkConfigCodec
import com.greatwall.callring.data.database.values

import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.domain.calling.CallCorrelation
import com.greatwall.callring.domain.model.RawCallRecord
import com.greatwall.callring.domain.model.CallDecision
import com.greatwall.callring.domain.model.CallResult
import com.greatwall.callring.domain.model.CallSource
import com.greatwall.callring.domain.model.ClassificationFact
import com.greatwall.callring.domain.model.WorkReason
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class StoragePolicyCharacterization {
    private val fact =
        ClassificationFact(true, WorkReason.WORKING, null, true, "Asia/Shanghai", 1, "2026.1", 0)

    private fun row(id: Long, at: Long, screen: Boolean, account: String? = null) =
        RawCallRecord(
            id,
            "31234",
            at,
            if (screen) null else 5,
            (if (screen) CallResult.PENDING else CallResult.ANSWERED),
            (if (screen) CallDecision.WHITELIST_ALLOWED else CallDecision.SYSTEM_LOG_ONLY),
            (if (screen) CallSource.SCREENING else CallSource.SYSTEM_CALL_LOG),
            if (screen) null else "$id",
            if (screen) "31234:$at" else null,
            true,
            fact,
            fact,
            account,
        )

    @Test
    fun ambiguityOutsideAffectedWindowPreventsAssociation() {
        val calls = linkedMapOf<Long, RawCallRecord>()
        listOf(row(1, 6000, true), row(2, 16000, true), row(3, 11000, false)).forEach {
            calls[it.id] = it
        }
        CallCorrelation.reconcile(calls.values.filter { it.number == "31234" }, listOf(1000))
            .forEach { (_, after) -> calls[after.id] = after }
        assertNull(calls.getValue(3).linkedScreenId)
        assertFalse(calls.getValue(1).counted)
    }

    @Test
    fun unknownAccountCannotChooseBetweenTwoKnownAccounts() {
        val calls = linkedMapOf<Long, RawCallRecord>()
        listOf(row(1, 1000, true), row(2, 1000, false, "a"), row(3, 1000, false, "b")).forEach {
            calls[it.id] = it
        }
        CallCorrelation.reconcile(calls.values.filter { it.number == "31234" }).forEach {
            (_, after) ->
            calls[after.id] = after
        }
        assertFalse(calls.getValue(1).counted)
        assertEquals(CallResult.UNRESOLVED, calls.getValue(1).result)
        assertNull(calls.getValue(2).linkedScreenId)
    }

    @Test
    fun associationPreservesOriginalScreeningFactsAndBecomesReversible() {
        val calls = linkedMapOf<Long, RawCallRecord>()
        val screen = row(1, 1000, true)
        calls[1] = screen
        calls[2] = row(2, 1100, false)
        CallCorrelation.reconcile(calls.values.filter { it.number == "31234" }, listOf(1000))
            .forEach { (_, after) -> calls[after.id] = after }
        assertTrue(calls.getValue(1).hidden)
        assertEquals(1L, calls.getValue(2).linkedScreenId)
        assertEquals(screen.originalClassification, calls.getValue(1).originalClassification)
        calls[2] = calls.getValue(2).copy(time = 8000)
        CallCorrelation.reconcile(calls.values.filter { it.number == "31234" }, listOf(1100))
            .forEach { (_, after) -> calls[after.id] = after }
        assertFalse(calls.getValue(1).hidden)
        assertTrue(calls.getValue(1).counted)
        assertNull(calls.getValue(2).linkedScreenId)
    }

    @Test
    fun legacyValuesDecodeWithoutDiscardingUnknownStrings() {
        assertEquals(CallResult.ANSWERED, LegacyValueCodec.result("已接听"))
        assertEquals(CallResult.UNRESOLVED, LegacyValueCodec.result("待核对·未计入"))
        assertEquals(CallDecision.CUSTOM_RINGTONE, LegacyValueCodec.decision("规则铃声"))
        assertEquals(
            CallDecision.SYSTEM_RINGTONE_FALLBACK,
            LegacyValueCodec.decision("音频准备超时，保留系统铃声"),
        )
        assertEquals(CallSource.MERGED, LegacyValueCodec.source("筛选＋系统通话记录"))
        assertEquals(WorkReason.HOLIDAY, LegacyValueCodec.reason("节假日·国庆节"))
        assertEquals("国庆节", LegacyValueCodec.holidayName("节假日·国庆节"))
        assertEquals("旧版未知结果", LegacyValueCodec.encodeResult(CallResult.UNKNOWN, "旧版未知结果"))
    }

    @Test
    fun multipleCentersShareDegreesAndLeaveUnrelatedRowsUntouched() {
        val calls = linkedMapOf<Long, RawCallRecord>()
        listOf(
                row(1, 1000, true),
                row(2, 1100, false),
                row(3, 61000, true),
                row(4, 61100, false),
                row(5, 121000, true),
            )
            .forEach { calls[it.id] = it }
        val untouched = calls.getValue(5)
        CallCorrelation.reconcile(
                calls.values.filter { it.number == "31234" },
                listOf(1000, 61000),
            )
            .forEach { (_, after) -> calls[after.id] = after }
        assertEquals(1L, calls.getValue(2).linkedScreenId)
        assertEquals(3L, calls.getValue(4).linkedScreenId)
        assertSame(untouched, calls.getValue(5))
    }

    @Test
    fun timestampBoundsDoNotOverflow() {
        val calls = linkedMapOf<Long, RawCallRecord>()
        listOf(
                row(1, Long.MIN_VALUE, true),
                row(2, Long.MIN_VALUE + 5000, false),
                row(3, Long.MAX_VALUE, true),
                row(4, Long.MAX_VALUE - 5000, false),
            )
            .forEach { calls[it.id] = it }
        CallCorrelation.reconcile(
                calls.values.filter { it.number == "31234" },
                listOf(Long.MIN_VALUE, Long.MAX_VALUE),
            )
            .forEach { (_, after) -> calls[after.id] = after }
        assertEquals(1L, calls.getValue(2).linkedScreenId)
        assertEquals(3L, calls.getValue(4).linkedScreenId)
    }

    @Test
    fun calendarReadHonorsByteLimitAndRemovesOnlyLeadingBom() {
        assertEquals(
            "value",
            WorkConfigCodec.readLimited(ByteArrayInputStream("\uFEFFvalue".toByteArray())),
        )
        assertEquals(
            1024 * 1024,
            WorkConfigCodec.readLimited(ByteArrayInputStream(ByteArray(1024 * 1024) { 32 })).length,
        )
        assertThrows(IllegalArgumentException::class.java) {
            WorkConfigCodec.readLimited(ByteArrayInputStream(ByteArray(1024 * 1024 + 1)))
        }
    }

    @Test
    fun calendarReadRejectsMalformedUtf8() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkConfigCodec.readLimited(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
    }
}
