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
}
