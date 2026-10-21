package com.greatwall.callring

import com.greatwall.callring.contracts.runtime.*
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.provider.CallLog
import android.test.InstrumentationTestCase
import com.greatwall.callring.contracts.runtime.SyncRequestState
import com.greatwall.callring.platform.scheduler.AndroidSyncScheduler

/** Tests only this separate QA package's jobs. Never observes or changes system call records. */
class BackendSchedulerDeviceTest : InstrumentationTestCase() {
    fun testPersistedCompletionAndContentObserverAreSeparateAndIdempotent() {
        val context = instrumentation.targetContext
        assertEquals("com.greatwall.callring.backendqa", context.packageName)
        val system = context.getSystemService(JobScheduler::class.java)
        val adapter = AndroidSyncScheduler(context)
        val completionId = AndroidSyncScheduler.COMPLETION_JOB_ID
        val contentId = AndroidSyncScheduler.CONTENT_JOB_ID
        system.cancel(completionId)
        system.cancel(contentId)
        try {
            assertEquals(SyncRequestState.QUEUED, adapter.requestSync(100).state)
            val completion = checkNotNull(system.getPendingJob(completionId))
            assertEquals(ComponentName(context, SyncJobService::class.java), completion.service)
            assertTrue(completion.isPersisted)
            assertEquals(1500L, completion.minLatencyMillis)
            assertEquals(15000L, completion.initialBackoffMillis)
            assertEquals(JobInfo.BACKOFF_POLICY_LINEAR, completion.backoffPolicy)
            assertEquals(SyncRequestState.ALREADY_QUEUED, adapter.requestSync(101).state)
            adapter.rearmObserver()
            val observer = checkNotNull(system.getPendingJob(contentId))
            assertFalse(observer.isPersisted)
            assertEquals(1000L, observer.triggerContentUpdateDelay)
            assertEquals(10000L, observer.triggerContentMaxDelay)
            val uri = checkNotNull(observer.triggerContentUris).single()
            assertEquals(CallLog.Calls.CONTENT_URI, uri.uri)
            assertEquals(JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS, uri.flags)
            adapter.cancelObserver()
            assertNull(system.getPendingJob(contentId))
            assertNotNull(system.getPendingJob(completionId))
        } finally {
            system.cancel(completionId)
            system.cancel(contentId)
        }
    }
}
