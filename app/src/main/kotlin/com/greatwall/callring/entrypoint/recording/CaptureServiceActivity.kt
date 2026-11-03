package com.greatwall.callring.entrypoint.recording

import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A capture remains active until its final queued attachment/file cleanup has drained. */
class CaptureServiceActivity {
    private val started = AtomicBoolean()
    private val ending = AtomicBoolean()
    fun started() { if (started.compareAndSet(false, true)) active.incrementAndGet() }
    fun shutdown(worker: ExecutorService) {
        if (!ending.compareAndSet(false, true)) return
        worker.execute { if (started.get()) active.decrementAndGet() }
        worker.shutdown()
    }
    companion object {
        private val active = AtomicInteger()
        fun busy(): Boolean = active.get() > 0
    }
}
