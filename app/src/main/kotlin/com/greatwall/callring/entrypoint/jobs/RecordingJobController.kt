package com.greatwall.callring.entrypoint.jobs

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class RecordingJobController(
    private val runScheduled: (() -> Boolean) -> Long?,
    private val cancelProcessing: () -> Unit,
    private val now: () -> Long,
    private val schedule: (Long) -> Unit,
    private val worker: Executor,
    private val main: (Runnable) -> Unit,
) {
    private var stopped = AtomicBoolean(false)

    fun start(finish: (Boolean) -> Unit) {
        val token = AtomicBoolean(false)
        stopped = token
        worker.execute {
            var retry = false
            var next: Long? = null
            try { next = runScheduled { token.get() } } catch (_: Exception) { retry = true }
            main(Runnable {
                if (!token.get()) {
                    finish(retry)
                    next?.let { schedule((it - now()).coerceAtLeast(1000)) }
                }
            })
        }
    }

    fun cancel() {
        stopped.set(true)
        try { cancelProcessing() } catch (_: Exception) {}
    }
}
