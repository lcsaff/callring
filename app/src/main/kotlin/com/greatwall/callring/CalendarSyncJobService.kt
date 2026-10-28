package com.greatwall.callring

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.bootstrap.BackendProvider
import com.greatwall.callring.entrypoint.jobs.CalendarSyncJobController
import com.greatwall.callring.platform.scheduler.JobRuns
import kotlinx.coroutines.*

/** Network work is scheduled independently of incoming-call processing and survives reboot. */
class CalendarSyncJobService : JobService() {
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = JobRuns<JobParameters>()

    override fun onStartJob(params: JobParameters): Boolean {
        val graph = try { BackendProvider.from(this).backendGraph } catch (_: IllegalStateException) { return false }
        val run = runs.start(params.jobId, params)
        lifetime.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (!graph.acceptsOperations()) return@withContext
                    val cache = getSharedPreferences("${graph.configuration.databaseName}.calendar_sync", Context.MODE_PRIVATE)
                    CalendarSyncJobController(
                        { graph.configuration.clock },
                        { year -> (graph.api.settings.getCalendar() as? ApiResult.Success)?.value?.years?.any { it.year == year } == true },
                        object : CalendarSyncJobController.Attempts {
                            override fun read(year: Int) = CalendarSyncJobController.Attempt(
                                cache.getLong("attempt.$year", 0), cache.getBoolean("success.$year", false))
                            override fun begin(year: Int, now: Long) = graph.coordinator.guard {
                                check(graph.acceptsOperations()) { "backend.closed" }
                                cache.edit().putLong("attempt.$year", now)
                                .putBoolean("success.$year", false).commit() }
                            override fun finish(year: Int, success: Boolean) { graph.coordinator.guard {
                                check(graph.acceptsOperations()) { "backend.closed" }; cache.edit().putBoolean("success.$year", success).commit() } }
                        },
                        { graph.api.settings.synchronizeCalendar(it) is ApiResult.Success },
                    ).run { run.stopped.get() || !graph.acceptsOperations() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Cached data remains usable; the periodic job retries later. */ }
            finally { if (runs.complete(run)) jobFinished(params, false) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runs.stop(params.jobId)
        return true
    }

    override fun onDestroy() {
        runs.close()
        lifetime.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PERIODIC_JOB = 76
        private const val STARTUP_JOB = 77

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, CalendarSyncJobService::class.java)
            if (scheduler.getPendingJob(PERIODIC_JOB) == null) {
                scheduler.schedule(JobInfo.Builder(PERIODIC_JOB, component)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(6 * 60 * 60_000L)
                    .build())
            }
            if (scheduler.getPendingJob(STARTUP_JOB) == null) {
                scheduler.schedule(JobInfo.Builder(STARTUP_JOB, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(1000)
                    .build())
            }
        }
    }
}
