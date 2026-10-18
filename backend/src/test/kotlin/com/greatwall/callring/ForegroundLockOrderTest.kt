package com.greatwall.callring

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.ForegroundActionService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class ForegroundLockOrderTest {
    @Test fun hostProbeDoesNotInvertPublicationAndForegroundLocks() {
        val coordinator = BusinessRevisionCoordinator()
        val fenceHeld = CountDownLatch(1)
        val foregroundHeld = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3) { work -> Thread(work).apply { isDaemon = true } }
        val foreground = ForegroundActionService(checkCommand = {
            foregroundHeld.countDown()
            coordinator.guard { true }
        })
        val host = foreground.attachHost()
        val request = pool.submit { runBlocking { foreground.request("s", "t", "call", 1, ForegroundActionKind.CONFIRM, aiObject()) { true } } }
        try {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while(foreground.pending.value == null && System.nanoTime() < until) Thread.yield()
            val ticket = requireNotNull(foreground.pending.value)
            val publication = pool.submit<Boolean> {
                coordinator.guard(CommandContext("op",coordinator.capture(),1,mayCommit = {
                    fenceHeld.countDown()
                    check(foregroundHeld.await(2,TimeUnit.SECONDS))
                    foreground.hasHost
                }) { true }) { true }
            }
            assertTrue(fenceHeld.await(2,TimeUnit.SECONDS))
            val launch = pool.submit<Boolean> { foreground.beginLaunch(ticket.requestId,host) }
            assertTrue(publication.get(2,TimeUnit.SECONDS))
            assertTrue(launch.get(2,TimeUnit.SECONDS))
            assertTrue(foreground.deliver(ticket.requestId,ForegroundHostResult(ForegroundResultStatus.CANCELLED)))
            request.get(2,TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
    }
}
