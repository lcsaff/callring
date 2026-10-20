package com.greatwall.callring

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.bootstrap.BackendProvider
import com.greatwall.callring.platform.CalendarRefreshPolicy
import com.greatwall.callring.platform.JobRuns
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*

/** Network work is scheduled independently of incoming-call processing and survives reboot. */
class CalendarSyncJobService : JobService() {
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = JobRuns<JobParameters>()

    override fun onStartJob(params: JobParameters): Boolean {
        val run = runs.start(params.jobId, params)
        lifetime.launch {
            try {
                withContext(Dispatchers.IO) {
                    val graph = BackendProvider.from(this@CalendarSyncJobService).backendGraph
                    val clock = graph.configuration.clock
                    // Isolate QA/custom graphs from the production throttle as well as its database.
                    val cache = getSharedPreferences("${graph.configuration.databaseName}.calendar_sync", Context.MODE_PRIVATE)
                    val today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")))
                    for (year in CalendarRefreshPolicy.years(today)) {
                        if (run.stopped.get()) break
                        val now = clock.millis()
                        val info = graph.api.settings.getCalendar()
                        val cached = (info as? ApiResult.Success)?.value?.years?.any { it.year == year } == true
                        val last = cache.getLong("attempt.$year", 0)
                        val succeeded = cached && cache.getBoolean("success.$year", false)
                        if (!CalendarRefreshPolicy.due(now, last, succeeded)) continue
                        // Persist the attempt before IO, preventing restart loops when offline.
                        if (!cache.edit().putLong("attempt.$year", now).putBoolean("success.$year", false).commit()) continue
                        val result = graph.api.settings.synchronizeCalendar(year)
                        cache.edit().putBoolean("success.$year", result is ApiResult.Success).commit()
                    }
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
