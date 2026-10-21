package com.greatwall.callring

import com.greatwall.callring.application.conversation.query.QueryService
import com.greatwall.callring.application.ports.query.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class QueryLifetimeTest {
    @Test fun savedObservationsCannotBeRestoredAfterClose() = runBlocking {
        val source = object : ScopedReadPort {
            override val domains = setOf(AiDomain.D16)
            override fun version(domain: AiDomain) = 0L
            override fun query(spec: QuerySpec, after: String?) = SourcePage(emptyList(), 0)
        }
        val query = QueryService(listOf(source))
        query.query(QuerySpec(AiDomain.D16))
        val saved = query.snapshotObservations()
        assertFalse(saved.isEmpty())
        query.close()
        query.restoreObservations(saved)
        assertNull(query.observedRevision(AiDomain.D16, "*"))
    }

    @Test fun suspendedCoherentCannotReadVersionsOrPublishAfterClose() = runBlocking {
        val versions = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = object : ScopedReadPort {
            override val domains = setOf(AiDomain.D16)
            override fun version(domain: AiDomain): Long { versions.incrementAndGet(); return 0L }
            override fun query(spec: QuerySpec, after: String?) = SourcePage(emptyList(), 0)
        }
        val query = QueryService(listOf(source))
        val read = async {
            runCatching {
                query.coherent(setOf(AiDomain.D16)) {
                    assertTrue(query.query(QuerySpec(AiDomain.D16)) is ApiResult.Success)
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        entered.await()
        query.close()
        val before = versions.get()
        release.complete(Unit)
        assertEquals(ErrorCode.SYSTEM_UNAVAILABLE, (read.await().exceptionOrNull() as com.greatwall.callring.application.ports.BackendFailure).error.code)
        assertNull(query.observedRevision(AiDomain.D16, "*"))
        assertEquals(before, versions.get())
        val afterClose = runCatching { query.coherent(setOf(AiDomain.D16)) { fail("closed coherent block ran") } }
        assertEquals(ErrorCode.SYSTEM_UNAVAILABLE, (afterClose.exceptionOrNull() as com.greatwall.callring.application.ports.BackendFailure).error.code)
        assertEquals(before, versions.get())
    }

    @Test fun resetRemainsUsableWhileCloseIsTerminalAndDrainsRead() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val resets = AtomicInteger()
        var block = false
        val source = object : ScopedReadPort {
            override val domains = setOf(AiDomain.D16)
            override fun version(domain: AiDomain): Long { calls.incrementAndGet(); return 0L }
            override fun query(spec: QuerySpec, after: String?): SourcePage {
                calls.incrementAndGet()
                if (block) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return SourcePage(emptyList(), 0)
            }
            override fun reset() { resets.incrementAndGet() }
        }
        val query = QueryService(listOf(source))
        query.reset()
        assertTrue(query.query(QuerySpec(AiDomain.D16)) is ApiResult.Success)
        block = true
        val read = async(Dispatchers.Default) { query.query(QuerySpec(AiDomain.D16)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val closer = Thread {
            try { query.close() } catch (e: Throwable) { failure.set(e) }
        }.apply { start() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (closer.isAlive && closer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals("close must drain source read", Thread.State.BLOCKED, closer.state)
        } finally { release.countDown(); closer.join(5000) }
        assertTrue(read.await() is ApiResult.Success)
        assertNull(failure.get())
        val count = calls.get()
        query.reset()
        query.close()
        assertEquals(2, resets.get())
        assertTrue(query.query(QuerySpec(AiDomain.D16)) is ApiResult.Failure)
        assertTrue(query.get(AiDomain.D16, "1") is ApiResult.Failure)
        assertTrue(query.aggregate(AggregateSpec(AiDomain.D16)) is ApiResult.Failure)
        assertTrue(query.readText("retained") is ApiResult.Failure)
        assertEquals(count, calls.get())
    }
}
