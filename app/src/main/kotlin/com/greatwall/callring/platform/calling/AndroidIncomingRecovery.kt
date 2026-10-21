package com.greatwall.callring.platform.calling

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.greatwall.callring.application.ports.platform.SyncScheduler
import java.time.Clock

/** Recovery scheduler binder calls share only the shutdown gate, never the executor drain. */
internal class AndroidIncomingRecovery(
    private val application: Context,
    private val clock: Clock,
    private val scheduler: () -> SyncScheduler,
    private val active: () -> Boolean,
    private val lifetime: Any,
) {
    fun recover() = synchronized(lifetime) {
        if (active() && application.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED)
            scheduler().requestSync(clock.millis())
    }
}
