package com.greatwall.callring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.greatwall.callring.bootstrap.BackendProvider
import com.greatwall.callring.entrypoint.runtime.RuntimeEventController
import java.util.concurrent.Executors

/** Decodes platform broadcasts and supplies the process-wide serial runtime worker. */
class RuntimeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> RuntimeEventController.Event.RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> RuntimeEventController.Event.OFFHOOK
                TelephonyManager.EXTRA_STATE_IDLE -> RuntimeEventController.Event.IDLE
                else -> RuntimeEventController.Event.IGNORED
            }
        } else when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED -> RuntimeEventController.Event.RESTORE
            else -> RuntimeEventController.Event.IGNORED
        }
        val app = context.applicationContext
        val graph = try { BackendProvider.from(app).backendGraph } catch (_: IllegalStateException) { return }
        val controller = RuntimeEventController(
            { graph.recordingSession.ringing() },
            { graph.recordingSession.answered() },
            { graph.recordingSession.idle(it) },
            { CallCaptureService.phoneState(app, it, graph) },
            { graph.backend.refreshDecisionSnapshot() },
            { if (graph.acceptsOperations()) SyncJobService.schedule(context) }, { if (graph.acceptsOperations()) RecordingJobService.schedule(app, it) },
            System::currentTimeMillis, worker,
        )
        controller.receive(event) { val pending = goAsync(); { pending.finish() } }
    }

    companion object { private val worker = Executors.newSingleThreadExecutor() }
}
