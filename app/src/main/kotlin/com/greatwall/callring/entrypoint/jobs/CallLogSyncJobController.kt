package com.greatwall.callring.entrypoint.jobs

import com.greatwall.callring.domain.model.SystemCallRecord
import com.greatwall.callring.platform.scheduler.JobRuns
import java.util.concurrent.Executor

internal class CallLogSyncJobController<P>(
    private val statisticsStart: () -> Long,
    private val read: (Long, Long, () -> Boolean) -> List<SystemCallRecord>,
    private val import: (List<SystemCallRecord>) -> Unit,
    private val pending: (Long) -> Boolean,
    private val permitted: () -> Boolean,
    private val cancelObserver: () -> Unit,
    private val rearmObserver: () -> Unit,
    private val request: () -> Unit,
    private val now: () -> Long,
    private val diagnostic: () -> Unit,
    private val worker: Executor,
    private val main: (Runnable) -> Unit,
    private val finish: (P, Boolean) -> Unit,
) {
    private val runs = JobRuns<P>()

    fun start(jobId: Int, parameters: P) {
        val run = runs.start(jobId, parameters)
        val stopped = run.stopped
        worker.execute {
            var retry = false
            try {
                if (stopped.get()) return@execute
                val since = statisticsStart()
                if (!permitted()) cancelObserver()
                else if (since > 0 && !stopped.get()) {
                    val current = now()
                    val records = read(since, maxOf(current + 1, since + 1)) { stopped.get() }
                    if (!stopped.get()) {
                        import(records)
                        retry = pending(current)
                    }
                }
            } catch (_: SecurityException) { diagnose() }
            catch (_: java.io.InterruptedIOException) { retry = !stopped.get() }
            catch (_: Exception) { diagnose(); retry = true }
            val again = retry
            main(Runnable {
                if (!runs.complete(run)) return@Runnable
                if (jobId == 75 && permitted()) {
                    try { rearmObserver(); if (again) schedule(permitted, request, cancelObserver) }
                    catch (_: Exception) { finish(parameters, true) }
                } else finish(parameters, again && permitted())
            })
        }
    }

    fun stop(jobId: Int): Boolean { runs.stop(jobId); return permitted() }
    fun close() = runs.close()
    private fun diagnose() { try { diagnostic() } catch (_: Exception) {} }

    companion object {
        fun schedule(permitted: () -> Boolean, request: () -> Unit, cancelObserver: () -> Unit) {
            try { if (permitted()) request() else cancelObserver() } catch (_: Exception) {}
        }
    }
}
