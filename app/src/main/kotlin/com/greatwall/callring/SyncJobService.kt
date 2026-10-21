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
    private val controller by lazy {
        CallLogSyncJobController<JobParameters>(
            { BackendProvider.from(this).backendGraph.backend.statisticsStart() },
            { since, until, stopped -> BackendProvider.from(this).backendGraph.callLogSource.read(since, until, stopped) },
            { BackendProvider.from(this).backendGraph.backend.importSystemCalls(it) },
            { BackendProvider.from(this).backendGraph.backend.hasRecentPending(it) },
            { permitted(this) },
            { BackendProvider.from(this).backendGraph.syncScheduler.cancelObserver() },
            { BackendProvider.from(this).backendGraph.syncScheduler.rearmObserver() },
            { val graph = BackendProvider.from(this).backendGraph; graph.syncScheduler.requestSync(graph.configuration.clock.millis()) },
            { BackendProvider.from(this).backendGraph.configuration.clock.millis() },
            { BackendProvider.from(this).backendGraph.diagnostics.add(DiagnosticEvent.CALL_SYNC_FAILED) },
            worker, { handler.post(it) }, { params, retry -> jobFinished(params, retry) },
        )
    }
    override fun onStartJob(params: JobParameters): Boolean { controller.start(params.jobId, params); return true }
    override fun onStopJob(params: JobParameters): Boolean = controller.stop(params.jobId)
    override fun onDestroy() { controller.close(); super.onDestroy() }

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
