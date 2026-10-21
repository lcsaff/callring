package com.greatwall.callring

import com.greatwall.callring.application.conversation.execution.s
import com.greatwall.callring.application.conversation.foreground.ForegroundActionService

import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.conversation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ForegroundActionServiceTest {
    @Test fun noHostCannotLaunchAndExpiredOrInvalidatedCommandsCannotConsume() = runBlocking {
        var now = 0L
        val clock = object : Clock() { override fun getZone() = ZoneOffset.UTC; override fun withZone(zone: ZoneId) = this; override fun instant() = Instant.ofEpochMilli(now) }
        var active = true
        val service = ForegroundActionService(clock, checkCommand = { _, _ -> true })
        val result = async { service.request("s", "t", "call", 1, ForegroundActionKind.APP_SETTINGS, aiObject()) { active } }
        yield()
        val ticket = service.pending.value!!
        assertFalse(service.beginLaunch(ticket.requestId, "fabricated-host"))
        val host = service.attachHost()
        now = ticket.expiresAt
        assertFalse(service.beginLaunch(ticket.requestId, host))
        assertEquals(ForegroundResultStatus.EXPIRED, result.await().status)
        now = 0
        val next = async { service.request("s", "t2", "call2", 2, ForegroundActionKind.APP_SETTINGS, aiObject()) { active } }
        yield()
        val current = service.pending.value!!
        assertTrue(service.beginLaunch(current.requestId, host))
        active = false
        assertFalse(service.deliver(current.requestId, ForegroundHostResult(ForegroundResultStatus.VERIFIED)))
        assertEquals(ForegroundResultStatus.STALE, next.await().status)
    }
    @Test fun hostClaimsOnceAndLateResultCannotCompleteNewTicket() = runBlocking {
        val service = ForegroundActionService(Clock.systemUTC(), checkCommand = { _, _ -> true })
        val result = async { service.request("s", "t", "call", 1, ForegroundActionKind.APP_SETTINGS, aiObject()) { true } }
        yield()
        val old = service.pending.value!!
        val host = service.attachHost()
        assertTrue(service.beginLaunch(old.requestId, host))
        assertFalse(service.beginLaunch(old.requestId, host))
        service.detachHost(host)
        val replacementHost = service.attachHost()
        assertFalse(service.beginLaunch(old.requestId, replacementHost))
        assertTrue(service.pending.value!!.launched)
        service.invalidate()
        assertEquals(ForegroundResultStatus.CANCELLED, result.await().status)
        val next = async { service.request("s2", "t2", "call2", 2, ForegroundActionKind.APP_SETTINGS, aiObject()) { true } }
        yield()
        assertFalse(service.deliver(old.requestId, ForegroundHostResult(ForegroundResultStatus.VERIFIED)))
        val current = service.pending.value!!
        assertTrue(service.beginLaunch(current.requestId, replacementHost))
        assertTrue(service.deliver(current.requestId, ForegroundHostResult(ForegroundResultStatus.DENIED)))
        assertFalse(service.deliver(current.requestId, ForegroundHostResult(ForegroundResultStatus.VERIFIED)))
        assertEquals(ForegroundResultStatus.DENIED, next.await().status)
    }
}
