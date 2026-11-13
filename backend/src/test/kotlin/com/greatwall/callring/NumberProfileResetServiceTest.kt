package com.greatwall.callring

import com.greatwall.callring.application.ports.storage.NumberProfileResetPort
import com.greatwall.callring.application.storage.NumberProfileResetService
import com.greatwall.callring.contracts.storage.*
import com.greatwall.callring.contracts.common.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NumberProfileResetServiceTest {
    @Test fun successfulReplacementMayCloseOldOwnerBeforeReceiptReturns()=runBlocking {
        var active=true
        val expected=NumberProfileResetReceipt(2,3,4,5,6,true,true)
        val port=object:NumberProfileResetPort {
            override fun preview(checkCancellation:()->Unit)=error("unused")
            override fun confirm(token:String,keepRecordings:Boolean,checkCancellation:()->Unit):NumberProfileResetReceipt {
                checkCancellation();assertEquals("preview",token);assertTrue(keepRecordings);active=false;return expected
            }
            override fun discard(token:String)=error("unused")
        }
        val service=NumberProfileResetService(port,Dispatchers.Unconfined,{active})
        assertEquals(ApiResult.Success(expected),service.confirm("preview",true))
    }
    @Test fun aSealedOwnerCannotPrepareOrCommitReset()=runBlocking {
        val port=object:NumberProfileResetPort {
            override fun preview(checkCancellation:()->Unit)=error("must not prepare")
            override fun confirm(token:String,keepRecordings:Boolean,checkCancellation:()->Unit)=error("must not mutate")
            override fun discard(token:String)=error("must not discard")
        }
        val service=NumberProfileResetService(port,Dispatchers.Unconfined,{false})
        assertTrue(runCatching {service.preview()}.exceptionOrNull() is CancellationException)
        assertTrue(runCatching {service.confirm("x",false)}.exceptionOrNull() is CancellationException)
    }
}
