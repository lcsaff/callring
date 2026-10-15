package com.greatwall.callring.platform

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** JobParameters crosses Binder by value; only jobId is stable across start and stop callbacks. */
internal class JobRuns<T> : Closeable {
    class Run<T>(val jobId: Int, val parameters: T, val stopped: AtomicBoolean = AtomicBoolean())

    private val current = mutableMapOf<Int, Run<T>>()

    @Synchronized
    fun start(jobId: Int, parameters: T): Run<T> {
        current.remove(jobId)?.stopped?.set(true)
        return Run(jobId, parameters).also { current[jobId] = it }
    }

    @Synchronized
    fun stop(jobId: Int) {
        current.remove(jobId)?.stopped?.set(true)
    }

    @Synchronized
    fun complete(run: Run<T>): Boolean {
        if (current[run.jobId] !== run) return false
        current.remove(run.jobId)
        return !run.stopped.get()
    }

    @Synchronized
    override fun close() {
        current.values.forEach { it.stopped.set(true) }
        current.clear()
    }
}
