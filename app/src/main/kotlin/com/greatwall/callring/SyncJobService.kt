package com.greatwall.callring

import android.Manifest
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.greatwall.callring.bootstrap.BackendProvider
import com.greatwall.callring.platform.diagnostics.DiagnosticEvent
import com.greatwall.callring.entrypoint.jobs.CallLogSyncJobController
import java.util.concurrent.Executors

class SyncJobService : JobService() {
    private val handler = Handler(Looper.getMainLooper())
    private var controller: CallLogSyncJobController<JobParameters>? = null
    private var owner: com.greatwall.callring.bootstrap.BackendGraph? = null
    private fun controller(graph: com.greatwall.callring.bootstrap.BackendGraph) =
        CallLogSyncJobController<JobParameters>(
            { graph.backend.statisticsStart() },
            { since, until, stopped -> graph.callLogSource.read(since, until) { stopped() || !graph.acceptsOperations() } },
            { graph.backend.importSystemCalls(it) },
            { graph.backend.hasRecentPending(it) },
            { permitted(this) },
            { graph.syncScheduler.cancelObserver() },
            { graph.syncScheduler.rearmObserver() },
            { graph.syncScheduler.requestSync(graph.configuration.clock.millis()) },
            { graph.configuration.clock.millis() },
            { graph.diagnostics.add(DiagnosticEvent.CALL_SYNC_FAILED) },
            worker, { handler.post(it) }, { params, retry -> jobFinished(params, retry) },
        )
    override fun onStartJob(params: JobParameters): Boolean {
        val graph = try { BackendProvider.from(this).backendGraph } catch (_: IllegalStateException) { return false }
        if (owner !== graph) { controller?.close(); owner = graph; controller = controller(graph) }
        controller!!.start(params.jobId, params)
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = controller?.stop(params.jobId) ?: permitted(this)
    override fun onDestroy() { controller?.close(); super.onDestroy() }
    companion object {
        private val worker = Executors.newSingleThreadExecutor()

        private fun permitted(context: Context) =
            context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED

        @JvmStatic
        fun schedule(context: Context) {
            try {
                val graph = BackendProvider.from(context).backendGraph
                CallLogSyncJobController.schedule(
                    { permitted(context) },
                    { graph.syncScheduler.requestSync(graph.configuration.clock.millis()) },
                    { graph.syncScheduler.cancelObserver() },
                )
            } catch (_: Exception) {
                /* The next runtime event or explicit API retry can reschedule. */
            }
        }
    }
}
