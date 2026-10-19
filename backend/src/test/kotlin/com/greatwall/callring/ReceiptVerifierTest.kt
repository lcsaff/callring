package com.greatwall.callring

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReceiptVerifierTest {
    private fun queries(value:AiJson.Obj)=QueryService(listOf(object:ScopedReadPort {
        override val domains=AiDomain.entries.toSet()
        override fun version(domain:AiDomain)=0L
        override fun query(spec:QuerySpec,after:String?)=SourcePage(listOf(SourceRow("31234",value)),1)
        override fun get(domain:AiDomain,key:String,projection:String)=SourceRow(key,value)
    }))
    @Test fun actualReadbackMustMatchEveryRequestedField()=runBlocking {
        val call=ConversationToolCall("c","callers_saveProfile",aiObject("number" to aiString("31234"),"name" to aiString("合成测试"),"department" to aiString("test")))
        val receipt=ToolReceipt("op","c",ToolStatus.COMMITTED,commitRevision=RevisionVector("test",emptyMap()))
        val wrong=ReceiptVerifier(queries(aiObject("stored" to aiNumber(1),"number" to aiString("31234"),"name" to aiString("合成测试"),"department" to aiString("wrong")))).verify(7,call,receipt)
        assertEquals(VerificationStatus.COMMITTED_UNVERIFIED,wrong.verification)
        assertEquals(ToolStatus.COMMITTED,wrong.status)
        val right=ReceiptVerifier(queries(aiObject("stored" to aiNumber(1),"number" to aiString("31234"),"name" to aiString("合成测试"),"department" to aiString("test")))).verify(7,call,receipt)
        assertEquals(VerificationStatus.VERIFIED,right.verification)
        assertNotEquals(AiJson.Null,right.verificationEvidence)
    }
    @Test fun queuedWorkCannotBeReportedAsVerifiedCompletion()=runBlocking {
        val r=ReceiptVerifier(queries(aiObject())).verify(46,ConversationToolCall("c","runtime_requestCallLogSync",aiObject()),ToolReceipt("op","c",ToolStatus.QUEUED))
        assertEquals(VerificationStatus.PENDING,r.verification)
    }
    @Test fun failedReadbackNeverTurnsCommitIntoRetryableFailure()=runBlocking {
        val q=QueryService(emptyList())
        val r=ReceiptVerifier(q).verify(37,ConversationToolCall("c","settings_saveAppearance",aiObject("colorMode" to aiString("DARK"))),ToolReceipt("op","c",ToolStatus.COMMITTED))
        assertEquals(ToolStatus.COMMITTED,r.status)
        assertEquals(VerificationStatus.COMMITTED_UNVERIFIED,r.verification)
        assertFalse(r.error!!.retryable)
    }
    @Test fun calendarSyncAndImportCompareActualYearMetadata()=runBlocking {
        val year=aiObject("year" to aiNumber(2026),"source" to aiString("official"),"published" to aiString("2026-01-01"),"version" to aiString("v1"))
        val receipt=ToolReceipt("op","c",ToolStatus.COMMITTED,aiObject("years" to AiJson.Arr(listOf(year))),commitRevision=RevisionVector("test",emptyMap()))
        for(index in listOf(26,27)) {
            val result=ReceiptVerifier(queries(year)).verify(index,ConversationToolCall("c",if(index==26)"settings_synchronizeCalendar"else"settings_importCalendar",if(index==26)aiObject("year" to aiNumber(2026))else aiObject()),receipt)
            assertEquals(VerificationStatus.VERIFIED,result.verification)
        }
    }
    @Test fun weeklyPatchReadbackUsesTheStoredSevenDayWindowRepresentation()=runBlocking {
        val window=aiObject("startMinute" to aiNumber(540),"endMinute" to aiNumber(600))
        val expected=aiObject("mode" to aiString("CUSTOM"),"weeklyWindows" to aiObject("1" to AiJson.Arr(listOf(window))))
        val actual=aiObject("quiet_mode" to aiString("CUSTOM"),"quiet_windows" to AiJson.Obj((1..7).associate{it.toString() to AiJson.Arr(if(it==1)listOf(AiJson.Arr(listOf(aiNumber(540),aiNumber(600))))else emptyList())}))
        val receipt=ToolReceipt("op","c",ToolStatus.COMMITTED,commitRevision=RevisionVector("test",emptyMap()))
        val result=ReceiptVerifier(queries(actual)).verify(15,ConversationToolCall("c","settings_saveQuietHours",expected),receipt)
        assertEquals(VerificationStatus.VERIFIED,result.verification)
    }
    @Test fun externalCapabilityRequiresActualForegroundReceiptAsWellAsReadback()=runBlocking {
        val actual=aiObject("runtime" to aiObject("capabilities" to aiObject("MICROPHONE" to aiString("GRANTED"))))
        val call=ConversationToolCall("c","system_requestCapability",aiObject("capability" to aiString("MICROPHONE")))
        val receipt=ToolReceipt("op","c",ToolStatus.COMMITTED,verificationEvidence=AiJson.Arr(listOf(aiObject("foregroundAction" to aiString("CAPABILITY"),"result" to aiString("VERIFIED")))))
        assertEquals(VerificationStatus.VERIFIED,ReceiptVerifier(queries(actual)).verify(67,call,receipt).verification)
        assertEquals(VerificationStatus.COMMITTED_UNVERIFIED,ReceiptVerifier(queries(actual)).verify(67,call,receipt.copy(verificationEvidence=AiJson.Null)).verification)
    }
    @Test fun failedNoOpReadbackDoesNotInventACommittedMutation()=runBlocking {
        val r=ReceiptVerifier(QueryService(emptyList())).verify(37,ConversationToolCall("c","settings_saveAppearance",aiObject("colorMode" to aiString("DARK"))),ToolReceipt("op","c",ToolStatus.COMMITTED,noOp=true))
        assertEquals(ToolStatus.FAILED,r.status);assertEquals(VerificationStatus.FAILED,r.verification);assertNull(r.commitRevision)
    }
    @Test fun exportNeedsActualFileReadbackAndPlaybackNeedsExactTarget()=runBlocking {
        val q=queries(aiObject("recordingPlayback" to aiObject("mode" to aiString("PREVIEW"),"state" to aiString("PLAYING"),"callId" to aiNumber(12))))
        val verifier=ReceiptVerifier(q)
        val file=ToolReceipt("op","c",ToolStatus.COMMITTED,verificationEvidence=AiJson.Arr(listOf(aiObject("documentReadback" to aiBool(true)))))
        assertEquals(VerificationStatus.VERIFIED,verifier.verify(10,ConversationToolCall("c","reports_exportCsv",aiObject()),file).verification)
        assertEquals(VerificationStatus.COMMITTED_UNVERIFIED,verifier.verify(10,ConversationToolCall("c","reports_exportCsv",aiObject()),file.copy(verificationEvidence=AiJson.Null)).verification)
        val played=ToolReceipt("op","c",ToolStatus.COMMITTED,commitRevision=RevisionVector("test",emptyMap()))
        assertEquals(VerificationStatus.VERIFIED,verifier.verify(58,ConversationToolCall("c","recordings_play",aiObject("callId" to aiNumber(12))),played).verification)
        assertEquals(VerificationStatus.COMMITTED_UNVERIFIED,verifier.verify(58,ConversationToolCall("c","recordings_play",aiObject("callId" to aiNumber(13))),played).verification)
        assertEquals(VerificationStatus.COMMITTED_UNVERIFIED,verifier.verify(59,ConversationToolCall("c","recordings_stop",aiObject()),played).verification)
        val stopped=ReceiptVerifier(queries(aiObject("recordingPlayback" to aiObject("mode" to aiString("NONE"),"state" to aiString("IDLE"),"callId" to AiJson.Null))))
        assertEquals(VerificationStatus.VERIFIED,stopped.verify(59,ConversationToolCall("c","recordings_stop",aiObject()),played).verification)
    }

}
