package com.greatwall.callring.coordination

import com.greatwall.callring.application.ports.platform.SyncScheduler

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator

import com.greatwall.callring.application.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.application.settings.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.domain.model.StatisticsInterval
import java.time.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StatisticsServiceTest {
    private class Statistics : StatisticsTransactions {
        var intervals = listOf(StatisticsInterval(10, 200))
        var fail = false; var writes = 0
        var afterOpen: () -> Unit = {}
        override fun <T> writeStatistics(block: (StatisticsWriter) -> T): T {
            val pending = intervals.toMutableList()
            val result = block(object : StatisticsWriter {
                override fun statisticsHead() = StatisticsHead(pending.withIndex().lastOrNull { it.value.endMillis == null }?.let {
                    OpenStatistics(it.index.toLong(), it.value)
                }, pending.mapNotNull { it.endMillis }.maxOrNull())
                override fun openStatistics(at: Long) { writes++; pending += StatisticsInterval(at, null); afterOpen() }
                override fun closeStatistics(id: Long, at: Long) { writes++; pending[id.toInt()] = pending[id.toInt()].copy(endMillis = at) }
            })
            if (fail) error("transaction.failed")
            intervals = pending
            return result
        }
    }
    @Test fun statisticsUsesEventBoundsKeepsHistoryAndSchedulesOutsideFenceAfterRealEnableOnly() = runBlocking {
        val tx = Statistics(); val coordinator = BusinessRevisionCoordinator(); var schedules = 0
        val patches = mutableListOf<StatisticsChange>()
        val service = StatisticsSettingsService(tx, coordinator, Dispatchers.Unconfined,
            Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC), object : SyncScheduler {
                override fun requestSync(atEpochMillis: Long): SyncRequestReceipt {
                    assertFalse(Thread.holdsLock(coordinator.fence)); schedules++; error("scheduler.unavailable")
                }
            }, { patch -> assertTrue(Thread.holdsLock(coordinator.fence)); patches += patch }, operationActive = { true })
        assertTrue(service.set(true) is ApiResult.Success)
        assertEquals(listOf(StatisticsInterval(10, 200), StatisticsInterval(200, null)), tx.intervals)
        assertEquals(1, schedules); assertEquals(1L, coordinator.marker())
        assertTrue(service.set(true) is ApiResult.Success)
        assertEquals(1, schedules); assertEquals(1, tx.writes)
        assertTrue(service.set(false) is ApiResult.Success)
        assertEquals(StatisticsInterval(200, 200), tx.intervals.last())
        assertEquals(2, patches.size); assertEquals(2L, coordinator.marker())
        tx.fail = true
        assertTrue(service.set(true) is ApiResult.Failure)
        assertEquals(2, patches.size); assertEquals(1, schedules)
        assertEquals(2L, coordinator.marker()); assertEquals(2, tx.intervals.size)
    }
    @Test fun cancellationDuringStatisticsCommitStillSchedulesCommittedEnableOutsideFence() = runBlocking {
        val tx = Statistics(); val coordinator = BusinessRevisionCoordinator(); var schedules = 0
        val service = StatisticsSettingsService(tx, coordinator, Dispatchers.Unconfined,
            Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC), object : SyncScheduler {
                override fun requestSync(atEpochMillis: Long): SyncRequestReceipt {
                    assertFalse(Thread.holdsLock(coordinator.fence)); schedules++
                    return SyncRequestReceipt(SyncRequestState.QUEUED, atEpochMillis)
                }
            }, {}, operationActive = { true })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) { service.set(true) }
        tx.afterOpen = { job.cancel() }; job.start(); job.join()
        assertEquals(1L, coordinator.marker())
        assertNotNull(tx.intervals.last().let { it.takeIf { interval -> interval.endMillis == null } })
        assertEquals("a committed enable still schedules after caller cancellation", 1, schedules)
        scope.cancel()
    }
}
