package com.greatwall.callring.entrypoint

import com.greatwall.callring.entrypoint.runtime.RuntimeEventController
import com.greatwall.callring.entrypoint.jobs.RecordingJobController
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class EntryJobControllersTest {
    @Test fun idleSyncPrecedesAsyncAndFailureStillFinishesOnce() {
        val events = mutableListOf<String>()
        val queue = mutableListOf<Runnable>()
        val controller = RuntimeEventController({}, {}, { events += "idle"; error("failed") }, {}, {},
            { events += "sync" }, { events += "schedule" }, { 42 }, Executor { queue += it })
        controller.receive(RuntimeEventController.Event.IDLE) {
            events += "async"
            { events += "finish"; Unit }
        }
        assertEquals(listOf("sync", "async"), events)
        queue.single().run()
        assertEquals(listOf("sync", "async", "idle", "finish"), events)
    }

    @Test fun cancelledRecordingCompletionCannotFinishOrReschedule() {
        val worker = mutableListOf<Runnable>(); val main = mutableListOf<Runnable>()
        val events = mutableListOf<String>()
        val controller = RecordingJobController({ stopped -> events += "run:${stopped()}"; 5 },
            { events += "cancel" }, { 10 }, { events += "schedule:$it" },
            Executor { worker += it }, { main += it })
        controller.start { events += "finish" }
        controller.cancel()
        worker.single().run(); main.single().run()
        assertEquals(listOf("cancel", "run:true"), events)
    }

    @Test fun replacementRecordingTokenDoesNotCancelPreviousStartAndFinishPrecedesSchedule() {
        val worker = mutableListOf<Runnable>(); val main = mutableListOf<Runnable>()
        val events = mutableListOf<String>()
        val controller = RecordingJobController({ 2 }, {}, { 10 }, { events += "schedule:$it" },
            Executor { worker += it }, { main += it })
        controller.start { events += "old:$it" }; controller.start { events += "new:$it" }
        worker.forEach { it.run() }; controller.cancel(); main.forEach { it.run() }
        assertEquals(listOf("old:false", "schedule:1000"), events)
    }
}
