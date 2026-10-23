package com.greatwall.callring

import com.greatwall.callring.application.calling.*
import com.greatwall.callring.application.coordination.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.application.settings.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.change.DataDomain
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NumberRuleSettingsServiceTest {
    private class Store : NumberRuleTransactions {
        var value = NumberRuleSettings("3", 5, 0)
        var reads = 0; var writes = 0; var fail = false
        override fun <T> readNumberRule(block: (NumberRuleReader) -> T): T = block(object : NumberRuleReader {
            override fun numberRule() = value.also { reads++ }
        })
        override fun <T> writeNumberRule(block: (NumberRuleWriter) -> T): T {
            var pending = value
            val result = block(object : NumberRuleWriter {
                override fun numberRule() = pending.also { reads++ }
                override fun saveNumberRule(value: NumberRuleSettings) { pending = value; writes++ }
            })
            if (fail) error("commit.failed")
            value = pending
            return result
        }
    }
    @Test fun savesNoopStaleAndRollbackPublishOnlyCommittedRule() = runBlocking {
        val tx = Store(); val c = BusinessRevisionCoordinator(); val state = NumberRuleState(c)
        val service = NumberRuleSettingsService(tx, c, state, Dispatchers.Unconfined, operationActive = { true }, afterInstall = {})
        val base = (service.get() as ApiResult.Success).value
        val saved = (service.save(SaveNumberRule(base, "8", 6)) as ApiResult.Success).value
        assertEquals(NumberRuleSettings("8", 6, 1), saved)
        assertEquals(saved, tx.value); assertEquals("8", state.snapshot().rule.prefix)
        assertEquals(setOf(DataDomain.NUMBER_RULES), c.invalidations.snapshots.value.domains.keys)
        assertTrue(service.save(SaveNumberRule(saved, "8", 6)) is ApiResult.Success)
        assertEquals(1, tx.writes); assertEquals(1L, c.marker())
        assertEquals(ErrorCode.CONFLICT, (service.save(SaveNumberRule(base, "7", 6)) as ApiResult.Failure).error.code)
        tx.fail = true
        assertTrue(service.save(SaveNumberRule(saved, "7", 6)) is ApiResult.Failure)
        assertEquals(saved, tx.value); assertEquals("8", state.snapshot().rule.prefix); assertEquals(1L, c.marker())
    }
    @Test fun noopColdSaveInstallsDurableSnapshotWithoutPublication() = runBlocking {
        val tx=Store(); val c=BusinessRevisionCoordinator(); val state=NumberRuleState(c)
        val service=NumberRuleSettingsService(tx,c,state,Dispatchers.Unconfined,operationActive={true},afterInstall={})
        assertTrue(service.save(SaveNumberRule(tx.value,"3",5)) is ApiResult.Success)
        assertEquals("3",state.snapshot().rule.prefix); assertEquals(0L,c.marker()); assertEquals(0,tx.writes)
    }
    @Test fun invalidAndOverflowNeverPublish() = runBlocking {
        val tx = Store(); val c = BusinessRevisionCoordinator(); val state = NumberRuleState(c)
        val service = NumberRuleSettingsService(tx, c, state, Dispatchers.Unconfined, operationActive = { true }, afterInstall = {})
        for (value in listOf(SaveNumberRule(tx.value,"８",6), SaveNumberRule(tx.value,"123",2), SaveNumberRule(tx.value,"",31), SaveNumberRule(tx.value.copy(revision=-1),"",1))) {
            assertEquals(ErrorCode.VALIDATION, (service.save(value) as ApiResult.Failure).error.code)
        }
        assertEquals(0, tx.reads)
        tx.value = tx.value.copy(revision=Long.MAX_VALUE)
        assertTrue(service.save(SaveNumberRule(tx.value,"8",6)) is ApiResult.Failure)
        assertEquals(0, tx.writes); assertEquals(0L, c.marker())
    }
    @Test fun coldConcurrentPreparationLoadsOnceAndCloseDuringReadPreventsInstall() = runBlocking {
        val c = BusinessRevisionCoordinator(); val state = NumberRuleState(c)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val tx = object : NumberRuleTransactions {
            override fun <T> readNumberRule(block: (NumberRuleReader) -> T): T {
                reads.incrementAndGet(); entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return block(object : NumberRuleReader { override fun numberRule() = NumberRuleSettings("8",6,1) })
            }
            override fun <T> writeNumberRule(block: (NumberRuleWriter) -> T): T = error("unexpected")
        }
        val service = NumberRuleSettingsService(tx,c,state,Dispatchers.IO,operationActive={active.get()},afterInstall={})
        val first = async { service.prepare() }
        withContext(Dispatchers.IO) { check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)) }
        val second = async { service.prepare() }
        release.countDown()
        assertTrue(first.await() is ApiResult.Success); assertTrue(second.await() is ApiResult.Success)
        assertEquals(1,reads.get()); assertEquals("8",state.snapshot().rule.prefix)
        val closedState = NumberRuleState(c)
        val closeTx = object : NumberRuleTransactions {
            override fun <T> readNumberRule(block: (NumberRuleReader) -> T): T {
                val result = block(object : NumberRuleReader { override fun numberRule()=NumberRuleSettings("3",5,0) })
                active.set(false)
                return result
            }
            override fun <T> writeNumberRule(block: (NumberRuleWriter) -> T): T = error("unexpected")
        }
        val closeService = NumberRuleSettingsService(closeTx,c,closedState,Dispatchers.Unconfined,operationActive={active.get()},afterInstall={})
        assertTrue(closeService.prepare() is ApiResult.Failure); assertFalse(closedState.initialized())
        assertTrue(closeService.prepare() is ApiResult.Failure)
    }
    @Test fun cancellationDuringReadDoesNotInstallSnapshot() = runBlocking {
        val c=BusinessRevisionCoordinator(); val state=NumberRuleState(c)
        lateinit var caller: Job
        val tx=object : NumberRuleTransactions {
            override fun <T> readNumberRule(block: (NumberRuleReader) -> T): T {
                val result=block(object : NumberRuleReader { override fun numberRule()=NumberRuleSettings("3",5,0) })
                caller.cancel()
                return result
            }
            override fun <T> writeNumberRule(block: (NumberRuleWriter) -> T): T=error("unexpected")
        }
        val service=NumberRuleSettingsService(tx,c,state,Dispatchers.Unconfined,operationActive={true},afterInstall={})
        caller=launch(start=CoroutineStart.LAZY) { service.prepare() }; caller.start(); caller.join()
        assertFalse(state.initialized())
    }
    @Test fun cancelledSaveDoesNotTouchStorageOrPublish() = runBlocking {
        val tx=Store(); val c=BusinessRevisionCoordinator(); val state=NumberRuleState(c)
        val service=NumberRuleSettingsService(tx,c,state,Dispatchers.Unconfined,operationActive={true},afterInstall={})
        val job=launch {
            currentCoroutineContext().cancel()
            try { service.save(SaveNumberRule(tx.value,"8",6)); fail("cancelled save returned") } catch (_: CancellationException) { }
        }
        job.join(); assertEquals(0,tx.reads); assertEquals(0,tx.writes); assertEquals(0L,c.marker())
    }
    @Test fun closedStateCannotLoadAndUnpreparedStateIsExplicit() = runBlocking {
        val tx = Store(); val c = BusinessRevisionCoordinator(); val state = NumberRuleState(c)
        try { state.snapshot(); fail("unprepared") } catch (_: IllegalStateException) { }
        val service = NumberRuleSettingsService(tx,c,state,Dispatchers.Unconfined,operationActive={ false },afterInstall={})
        assertTrue(service.get() is ApiResult.Failure); assertEquals(0,tx.reads)
    }
    @Test fun narrowPreparationCannotInstallAfterOwnerClosesDuringRead() {
        val coordinator=BusinessRevisionCoordinator()
        val state=NumberRuleState(coordinator)
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val active=java.util.concurrent.atomic.AtomicBoolean(true)
        val transactions=object : NumberRuleTransactions {
            override fun <T> readNumberRule(block:(NumberRuleReader)->T):T=block(object:NumberRuleReader {
                override fun numberRule():NumberRuleSettings {
                    entered.countDown()
                    check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
                    return NumberRuleSettings("8",6,1)
                }
            })
            override fun <T> writeNumberRule(block:(NumberRuleWriter)->T):T=error("read only")
        }
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val preparation=executor.submit { state.prepare(transactions,active::get) }
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS))
            active.set(false)
            release.countDown()
            try { preparation.get(3,java.util.concurrent.TimeUnit.SECONDS);fail("closed owner installed rule") }
            catch(e:java.util.concurrent.ExecutionException) { assertTrue(e.cause is com.greatwall.callring.application.ports.BackendFailure) }
            assertThrows(IllegalStateException::class.java) { state.snapshot() }
        } finally { release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS)) }
    }

}
