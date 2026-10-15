package com.greatwall.callring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.greatwall.callring.bootstrap.BackendProvider
import java.util.concurrent.Executors

/**
 * Restores platform jobs and refreshes the decision cache; presentation observes its own lifecycle.
 */
class RuntimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            if (
                intent.getStringExtra(TelephonyManager.EXTRA_STATE) ==
                    TelephonyManager.EXTRA_STATE_IDLE
            )
                SyncJobService.schedule(context)
            return
        }
        if (
            intent.action !in
                setOf(
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_DATE_CHANGED,
                )
        )
            return
        val pending = goAsync()
        val app = context.applicationContext
        worker.execute {
            try {
                BackendProvider.from(app).backendGraph.backend.refreshDecisionSnapshot()
                SyncJobService.schedule(app)
            } catch (_: Exception) {} finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor()
    }
}
