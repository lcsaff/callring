package com.greatwall.callring.coordination

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.application.settings.QuietSettingsService
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.application.mapping.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QuietSettingsServiceTest {
    private class Transactions : QuietTransactions {
        var current = QuietConfiguration(QuietSettings(QuietMode.OFF, false, emptyMap()).toDomain(), QuietMode.NON_WORK.toDomain())
        var fail = false
        var afterSave: () -> Unit = {}
        override fun <T> readQuiet(block: (QuietReader) -> T) = block(object : QuietReader { override fun quiet() = current })
        override fun <T> writeQuiet(block: (QuietWriter) -> T): T {
            var candidate = current
            val value = block(object : QuietWriter {
                override fun quiet() = candidate
                override fun saveQuiet(value: QuietConfiguration): Boolean {
                    val changed = candidate != value; candidate = value; afterSave(); return changed
                }
            })
            if (fail) error("commit.failure")
            current = candidate
            return value
        }
    }
    @Test fun commitAdvancesOnceNoOpRecordsCompletionAndHiddenModeChangeIsReal() = runBlocking {
        val transactions = Transactions(); val coordinator = BusinessRevisionCoordinator(); var decisionUpdates = 0
        val service = QuietSettingsService(transactions, coordinator, Dispatchers.Unconfined, { decisionUpdates++ }, operationActive = { true })
        assertTrue(service.enabled(true) is ApiResult.Success)
        assertEquals(1L, coordinator.marker()); assertEquals(1L, coordinator.capture().values["core"])
        val beforeNoOp = coordinator.invalidations.snapshots.value
        val command = CommandContext("noop", coordinator.capture(), 0, expectedScopes = coordinator.expect(setOf("quiet"))) { true }
        val bound = QuietSettingsService(transactions, coordinator, Dispatchers.Unconfined, { decisionUpdates++ }, command, operationActive = { true })
        bound.enabled(true)
        assertEquals(1L, coordinator.marker()); assertSame(beforeNoOp, coordinator.invalidations.snapshots.value)
        assertNotNull(coordinator.committed("noop"))
        transactions.current = transactions.current.copy(lastMode = QuietMode.CUSTOM.toDomain())
        service.enabled(true)
        assertEquals(2L, coordinator.marker()); assertEquals(1, decisionUpdates)
        assertFalse(coordinator.unchanged(setOf("core"), 1))
        assertFalse(coordinator.unchanged(setOf("quiet"), 1))
    }
    @Test fun threeFieldMergeUsesFinalTransactionTruthAndRejectsOnlyDirtyConflicts() = runBlocking {
        val transactions = Transactions(); val coordinator = BusinessRevisionCoordinator()
        val service = QuietSettingsService(transactions, coordinator, Dispatchers.Unconfined, {}, operationActive = { true })
        val base = transactions.current.settings.toContract()
        service.enabled(true)
        val merged = service.edit(base, base.copy(allNumbers = true)) as ApiResult.Success
        assertEquals(QuietMode.NON_WORK, merged.value.mode); assertTrue(merged.value.allNumbers)
        val before = coordinator.marker()
        val conflict = service.edit(base, base.copy(mode = QuietMode.CUSTOM, weeklyWindows = mapOf(1 to listOf(TimeWindow(60, 120))))) as ApiResult.Failure
        assertEquals(ErrorCode.CONFLICT, conflict.error.code); assertTrue(conflict.error.fieldErrors.containsKey("mode"))
        assertEquals(before, coordinator.marker())
        transactions.fail = true
        assertTrue(service.enabled(false) is ApiResult.Failure)
        assertEquals(before, coordinator.marker()); assertEquals(QuietMode.NON_WORK, transactions.current.settings.mode.toContract())
    }
    @Test fun cancellationAfterWriteCannotLosePublicationAndStaleScopesCannotWrite() = runBlocking {
        val transactions = Transactions(); val coordinator = BusinessRevisionCoordinator()
        val service = QuietSettingsService(transactions, coordinator, Dispatchers.Unconfined, {}, operationActive = { true })
        val stale = CommandContext("stale", coordinator.capture(), 0, expectedScopes = coordinator.expect(setOf("quiet"))) { true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) { service.enabled(true) }
        transactions.afterSave = { job.cancel() }
        job.start(); job.join()
        assertEquals(1L, coordinator.marker())
        assertEquals(1L, coordinator.invalidations.snapshots.value.revision)
        val bound = QuietSettingsService(transactions, coordinator, Dispatchers.Unconfined, {}, stale, operationActive = { true })
        assertEquals(ErrorCode.CONFLICT, (bound.enabled(false) as ApiResult.Failure).error.code)
        assertEquals(1L, coordinator.marker())
        scope.cancel()
    }
}
