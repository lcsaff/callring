package com.greatwall.callring.platform.calling

import android.content.Context
import android.os.PowerManager
import java.io.Closeable

/** One bounded lease per actual decision/write task. Construction does not touch the OS. */
class AndroidCallWorkLease(private val context: Context) {
    fun acquire(): Closeable {
        val power =
            context.getSystemService(PowerManager::class.java)
                ?: throw IllegalStateException("call.work_lease_unavailable")
        val lease = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Callring:incoming-work")
        try {
            lease.acquire(5000L)
        } catch (failure: RuntimeException) {
            try {
                if (lease.isHeld) lease.release()
            } catch (_: RuntimeException) {
                /* Acquisition failure remains primary. */
            }
            throw failure
        }
        return Closeable { if (lease.isHeld) lease.release() }
    }
}
