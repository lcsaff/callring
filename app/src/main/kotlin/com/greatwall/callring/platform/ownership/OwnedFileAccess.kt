package com.greatwall.callring.platform.ownership

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Leases cover complete file access including prepared-object cleanup. Seal never waits behind
 * IO; close then drains outside business/lifetime locks before resource replacement can begin. */
class OwnedFileAccess : Closeable {
    private val monitor = Object()
    @Volatile private var closed = false
    private var active = 0
    fun checkOpen() { check(!closed) { "backend.closed" } }
    fun acquire(): Closeable = synchronized(monitor) {
        checkOpen(); active++
        val released = AtomicBoolean()
        Closeable { if (released.compareAndSet(false, true)) synchronized(monitor) { active--; monitor.notifyAll() } }
    }
    fun seal() { synchronized(monitor) { closed = true } }
    override fun close() {
        var interrupted = false
        synchronized(monitor) {
            closed = true
            while (active > 0) try { monitor.wait() } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
