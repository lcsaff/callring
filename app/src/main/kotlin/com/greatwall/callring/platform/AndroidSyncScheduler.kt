package com.greatwall.callring.platform

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.provider.CallLog
import com.greatwall.callring.SyncJobService
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.application.ports.*

class AndroidSyncScheduler(private val context: Context) : SyncScheduler {
    override fun requestSync(atEpochMillis: Long): SyncRequestReceipt {
        val scheduler =
            context.getSystemService(JobScheduler::class.java)
                ?: throw BackendFailure(
                    ApiError(
                        ErrorCode.SYSTEM_UNAVAILABLE,
                        "sync.scheduler_unavailable",
                        retryable = true,
                    )
                )
        val component = ComponentName(context, SyncJobService::class.java)
        ensureObserver(scheduler, false)
        if (scheduler.getPendingJob(COMPLETION_JOB_ID) != null)
            return SyncRequestReceipt(SyncRequestState.ALREADY_QUEUED, atEpochMillis)
        val job =
            JobInfo.Builder(COMPLETION_JOB_ID, component)
                .setPersisted(true)
                .setMinimumLatency(1500)
                .setBackoffCriteria(15000, JobInfo.BACKOFF_POLICY_LINEAR)
                .build()
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) rejected()
        return SyncRequestReceipt(SyncRequestState.QUEUED, atEpochMillis)
    }

    /**
     * Replace the running content job before finishing it, preserving provider changes queued
     * during the read.
     */
    fun rearmObserver() {
        val scheduler =
            context.getSystemService(JobScheduler::class.java)
                ?: throw BackendFailure(
                    ApiError(
                        ErrorCode.SYSTEM_UNAVAILABLE,
                        "sync.scheduler_unavailable",
                        retryable = true,
                    )
                )
        ensureObserver(scheduler, true)
    }

    fun cancelObserver() {
        context.getSystemService(JobScheduler::class.java)?.cancel(CONTENT_JOB_ID)
    }

    private fun ensureObserver(scheduler: JobScheduler, replace: Boolean) {
        if (!replace && scheduler.getPendingJob(CONTENT_JOB_ID) != null) return
        val observer =
            JobInfo.Builder(CONTENT_JOB_ID, ComponentName(context, SyncJobService::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        CallLog.Calls.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    )
                )
                .setTriggerContentUpdateDelay(1000)
                .setTriggerContentMaxDelay(10000)
                .build()
        if (scheduler.schedule(observer) != JobScheduler.RESULT_SUCCESS) rejected()
    }

    private fun rejected(): Nothing =
        throw BackendFailure(
            ApiError(ErrorCode.SYSTEM_RESTRICTED, "sync.schedule_rejected", retryable = true)
        )

    companion object {
        const val COMPLETION_JOB_ID = 74
        const val CONTENT_JOB_ID = 75
    }
}
