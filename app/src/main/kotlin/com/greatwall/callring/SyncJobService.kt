package com.greatwall.callring

import android.Manifest
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.greatwall.callring.bootstrap.BackendProvider
import com.greatwall.callring.platform.AndroidSyncScheduler
import com.greatwall.callring.platform.DiagnosticEvent
import com.greatwall.callring.platform.JobRuns
import java.util.concurrent.Executors

class SyncJobService : JobService() {
    private val runs = JobRuns<JobParameters>()
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        val run = runs.start(params.jobId, params)
        val stopped = run.stopped
        worker.execute {
            var retry = false
            try {
                if (stopped.get()) return@execute
                val graph = BackendProvider.from(this).backendGraph
                val since = graph.backend.statisticsStart()
                if (!permitted(this)) graph.syncScheduler.cancelObserver()
                else if (since > 0 && !stopped.get()) {
                    val now = graph.configuration.clock.millis()
                    val records =
                        graph.callLogSource.read(since, maxOf(now + 1, since + 1)) { stopped.get() }
                    if (!stopped.get()) {
                        graph.backend.importSystemCalls(records)
                        retry = graph.backend.hasRecentPending(now)
                    }
                }
            } catch (_: SecurityException) {
                diagnostic(DiagnosticEvent.CALL_SYNC_FAILED)
            } catch (_: java.io.InterruptedIOException) {
                retry = !stopped.get()
            } catch (_: Exception) {
                diagnostic(DiagnosticEvent.CALL_SYNC_FAILED)
                retry = true
            }
            val again = retry
            handler.post {
                if (!runs.complete(run)) return@post
                if (params.jobId == AndroidSyncScheduler.CONTENT_JOB_ID && permitted(this)) {
                    try {
                        BackendProvider.from(this).backendGraph.syncScheduler.rearmObserver()
                        if (again) schedule(this)
                    } catch (_: Exception) {
                        jobFinished(params, true)
                    }
                } else jobFinished(params, again && permitted(this))
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runs.stop(params.jobId)
        return permitted(this)
    }

    override fun onDestroy() {
        runs.close()
        super.onDestroy()
    }

    private fun diagnostic(event: DiagnosticEvent) {
        try {
            BackendProvider.from(this).backendGraph.diagnostics.add(event)
        } catch (_: Exception) {}
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor()

        private fun permitted(context: Context) =
            context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED

        @JvmStatic
        fun schedule(context: Context) {
            try {
                val graph = BackendProvider.from(context).backendGraph
                if (permitted(context))
                    graph.syncScheduler.requestSync(graph.configuration.clock.millis())
                else graph.syncScheduler.cancelObserver()
            } catch (_: Exception) {
                /* The next runtime event or explicit API retry can reschedule. */
            }
        }
    }
}
