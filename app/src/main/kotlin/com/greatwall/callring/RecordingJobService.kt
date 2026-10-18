package com.greatwall.callring

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.greatwall.callring.bootstrap.BackendProvider
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A single durable job owns AI work. No call-screening thread waits for networking. */
class RecordingJobService : JobService() {
    private var stopped = AtomicBoolean(false)
    override fun onStartJob(params: JobParameters): Boolean {
        val token = AtomicBoolean(false); stopped = token
        worker.execute {
            var retry = false
            var next: Long? = null
            try {
                val runtime = BackendProvider.from(this).backendGraph.recordings
                // Folder access can fail independently; already imported work must still run.
                try { if (!token.get()) runtime.discoverSystemRecordings() } catch (_: Exception) {}
                if (!token.get()) runtime.process { token.get() }
                next = runtime.workflow.nextRunAt()
            } catch (_: Exception) { retry = true }
            Handler(Looper.getMainLooper()).post {
                if (!token.get()) {
                    jobFinished(params, retry)
                    next?.let { schedule(this, (it - System.currentTimeMillis()).coerceAtLeast(1000)) }
                }
            }
        }
        return true
    }
    private fun cancelWork() {
        stopped.set(true)
        try { BackendProvider.from(this).backendGraph.recordings.workflow.cancelProcessing() } catch (_: Exception) {}
    }
    override fun onStopJob(params: JobParameters): Boolean { cancelWork(); return true }
    override fun onDestroy() { cancelWork(); super.onDestroy() }
    companion object {
        private const val JOB_ID = 73140
        private val worker = Executors.newSingleThreadExecutor()
        fun schedule(context: Context, delayMillis: Long = 0) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, RecordingJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delayMillis.coerceAtLeast(0)).setPersisted(true)
                .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
            check(scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS)
        }
    }
}
