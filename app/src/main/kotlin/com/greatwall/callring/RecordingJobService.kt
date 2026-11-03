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

/** A single durable job owns AI work. No call-screening thread waits for networking. */
class RecordingJobService : JobService() {
    private var controller: com.greatwall.callring.entrypoint.jobs.RecordingJobController? = null
    private var owner: com.greatwall.callring.bootstrap.BackendGraph? = null
    private fun controller(graph: com.greatwall.callring.bootstrap.BackendGraph) =
        com.greatwall.callring.entrypoint.jobs.RecordingJobController(
            { stopped -> graph.recordings.service.runScheduled { stopped() || !graph.acceptsOperations() } },
            { graph.recordings.workflow.cancelProcessing() },
            System::currentTimeMillis, { if (graph.acceptsOperations()) schedule(this, it) }, worker,
            { Handler(Looper.getMainLooper()).post(it) },
        )
    override fun onStartJob(params: JobParameters): Boolean {
        val graph = try { BackendProvider.from(this).backendGraph } catch (_: IllegalStateException) { return false }
        if (owner !== graph) { controller?.cancel(); owner = graph; controller = controller(graph) }
        controller!!.start { jobFinished(params, it) }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { controller?.cancel(); return true }
    override fun onDestroy() { controller?.cancel(); super.onDestroy() }    companion object {
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
