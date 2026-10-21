package com.greatwall.callring.platform.scheduler

import android.app.job.JobScheduler
import android.content.Context
import com.greatwall.callring.application.conversation.query.ScheduledJobObservation
import com.greatwall.callring.application.conversation.query.ScheduledJobsObservation

class AndroidJobObservation(private val context: Context) {
    fun observe(): ScheduledJobsObservation {
        val scheduler = context.getSystemService(JobScheduler::class.java)
            ?: return ScheduledJobsObservation(false, emptyList())
        return ScheduledJobsObservation(true, scheduler.allPendingJobs
            .filter { it.service.packageName == context.packageName }
            .map { ScheduledJobObservation(it.service.className, it.id) })
    }
}
