package com.greatwall.callring.platform

import com.greatwall.callring.platform.scheduler.JobRuns

import org.junit.Assert.*
import org.junit.Test

class JobRunsTest {
    @Test
    fun stopUsesStableJobIdAcrossDifferentParameterInstances() {
        val jobs = JobRuns<Any>()
        val run = jobs.start(74, Any())
        jobs.stop(74)
        assertTrue(run.stopped.get())
        assertFalse(jobs.complete(run))
    }

    @Test
    fun obsoleteCompletionCannotRemoveReplacement() {
        val jobs = JobRuns<Any>()
        val first = jobs.start(75, Any())
        val second = jobs.start(75, Any())
        assertTrue(first.stopped.get())
        assertFalse(jobs.complete(first))
        assertTrue(jobs.complete(second))
        assertFalse(jobs.complete(second))
    }

    @Test
    fun closingStopsEveryOutstandingRun() {
        val jobs = JobRuns<Any>()
        val first = jobs.start(74, Any())
        val second = jobs.start(75, Any())
        jobs.close()
        assertTrue(first.stopped.get())
        assertTrue(second.stopped.get())
        assertFalse(jobs.complete(first))
        assertFalse(jobs.complete(second))
    }
}
