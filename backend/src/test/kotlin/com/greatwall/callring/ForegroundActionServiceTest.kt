package com.greatwall.callring

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ForegroundActionServiceTest {
    @Test fun hostClaimsOnceAndLateResultCannotCompleteNewTicket() = runBlocking {
        val service = ForegroundActionService(Clock.systemUTC())
        val result = async { service.request("s", "t", "call", 1, ForegroundActionKind.APP_SETTINGS, aiObject()) { true } }
        yield()
        val old = service.pending.value!!
        val host = service.attachHost()
        assertTrue(service.beginLaunch(old.requestId, host))
        assertFalse(service.beginLaunch(old.requestId, host))
        service.invalidate()
        assertEquals(ForegroundResultStatus.CANCELLED, result.await().status)
        val next = async { service.request("s2", "t2", "call2", 2, ForegroundActionKind.APP_SETTINGS, aiObject()) { true } }
        yield()
        assertFalse(service.deliver(old.requestId, ForegroundHostResult(ForegroundResultStatus.VERIFIED)))
        val current = service.pending.value!!
        assertTrue(service.beginLaunch(current.requestId, host))
        assertTrue(service.deliver(current.requestId, ForegroundHostResult(ForegroundResultStatus.DENIED)))
        assertFalse(service.deliver(current.requestId, ForegroundHostResult(ForegroundResultStatus.VERIFIED)))
        assertEquals(ForegroundResultStatus.DENIED, next.await().status)
    }
}
