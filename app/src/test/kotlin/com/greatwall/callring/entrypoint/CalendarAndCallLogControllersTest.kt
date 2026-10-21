package com.greatwall.callring.entrypoint

import com.greatwall.callring.entrypoint.jobs.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executor

class CalendarAndCallLogControllersTest {
    @Test fun shanghaiOctoberBoundaryAndFailedBeginSkipNetwork() = kotlinx.coroutines.runBlocking {
        val events = mutableListOf<String>()
        val controller = CalendarSyncJobController(
            { Clock.fixed(Instant.parse("2026-09-30T17:00:00Z"), ZoneOffset.UTC) },
            { false }, object : CalendarSyncJobController.Attempts {
                override fun read(year: Int) = CalendarSyncJobController.Attempt(0, false)
                override fun begin(year: Int, now: Long): Boolean { events += "begin:$year"; return year == 2027 }
                override fun finish(year: Int, success: Boolean) { events += "finish:$year:$success" }
            }, { events += "sync:$it"; false })
        controller.run { false }
        assertEquals(listOf("begin:2026", "begin:2027", "sync:2027", "finish:2027:false"), events)
    }

    @Test fun returnedFailureContinuesButThrownFailureAbortsYears() = kotlinx.coroutines.runBlocking {
        val synced = mutableListOf<Int>()
        var thrown = false
        val controller = CalendarSyncJobController(
            { Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC) }, { false },
            object : CalendarSyncJobController.Attempts {
                override fun read(year: Int) = CalendarSyncJobController.Attempt(0, false)
                override fun begin(year: Int, now: Long) = true
                override fun finish(year: Int, success: Boolean) {}
            }, { synced += it; if (thrown) error("network"); false })
        controller.run { false }; assertEquals(listOf(2026, 2027), synced)
        synced.clear(); thrown = true; controller.run { false }; assertEquals(listOf(2026), synced)
    }

    @Test fun staleCallLogCompletionCannotRearmAndContentRetryRearmsBeforeSchedule() {
        val worker = mutableListOf<Runnable>(); val main = mutableListOf<Runnable>(); val events = mutableListOf<String>()
        val controller = CallLogSyncJobController<String>({ 1 }, { _, _, _ -> emptyList() }, {}, { true },
            { true }, {}, { events += "rearm" }, { events += "request" }, { 10 }, {},
            Executor { worker += it }, { main += it }, { p, retry -> events += "$p:$retry" })
        controller.start(75, "old"); worker.removeAt(0).run()
        controller.start(75, "new"); worker.removeAt(0).run()
        main.forEach { it.run() }
        assertEquals(listOf("rearm", "request"), events)
    }

    @Test fun callLogExceptionsPreserveRetryAndDiagnosticPolicy() {
        for (failure in listOf(SecurityException(), java.io.InterruptedIOException(), IllegalStateException())) {
            var retry: Boolean? = null; var diagnostics = 0
            val controller = CallLogSyncJobController<Unit>({ throw failure }, { _, _, _ -> emptyList() }, {}, { false },
                { true }, {}, {}, {}, { 0 }, { diagnostics++ }, Executor { it.run() }, { it.run() }, { _, r -> retry = r })
            controller.start(74, Unit)
            assertEquals(failure !is SecurityException, retry)
            assertEquals(if (failure is java.io.InterruptedIOException) 0 else 1, diagnostics)
        }
    }
    @Test fun deniedCallLogStillReadsStatisticsFirstAndCancelsObserver() {
        val events = mutableListOf<String>()
        val controller = CallLogSyncJobController<Unit>({ events += "statistics"; 1 },
            { _, _, _ -> error("not permitted") }, {}, { false }, { events += "permission"; false },
            { events += "cancel" }, {}, {}, { 10 }, {}, Executor { it.run() }, { it.run() },
            { _, retry -> events += "finish:$retry" })
        controller.start(74, Unit)
        assertEquals(listOf("statistics", "permission", "cancel", "finish:false"), events)
    }

    @Test fun stoppingDuringReadDiscardsResultsAndSuppressesCompletion() {
        lateinit var controller: CallLogSyncJobController<Unit>
        val events = mutableListOf<String>()
        controller = CallLogSyncJobController({ 1 }, { _, _, stopped ->
            controller.stop(74); assertTrue(stopped()); emptyList()
        }, { events += "import" }, { events += "pending"; false }, { true }, {}, {}, {},
            { 10 }, {}, Executor { it.run() }, { it.run() }, { _, _ -> events += "finish" })
        controller.start(74, Unit)
        assertTrue(events.isEmpty())
    }

}
