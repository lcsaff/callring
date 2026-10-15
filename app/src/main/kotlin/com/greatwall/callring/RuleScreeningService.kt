package com.greatwall.callring

import android.os.*
import android.telecom.Call
import android.telecom.CallScreeningService
import com.greatwall.callring.bootstrap.BackendProvider
import com.greatwall.callring.platform.DiagnosticEvent
import com.greatwall.callring.platform.calling.*
import java.util.UUID

/** Android bindings only; the deadline workflow has no framework or presentation dependencies. */
class RuleScreeningService : CallScreeningService() {
    private val handler = Handler(Looper.getMainLooper())
    private val graph
        get() = BackendProvider.from(this).backendGraph

    private val scheduler =
        object : ScreeningScheduler {
            override fun nowMillis() = SystemClock.elapsedRealtime()

            override fun post(task: Runnable) {
                handler.post(task)
            }

            override fun after(delayMillis: Long, task: Runnable): ScreeningCancellation {
                handler.postDelayed(task, delayMillis)
                return ScreeningCancellation { handler.removeCallbacks(task) }
            }
        }
    private val playback =
        object : ScreeningPlayback {
            override fun readEnabled() = graph.audio.getSettings().enabled

            override fun canReplace() =
                graph.audio.enabledForCalls() &&
                    AndroidCallEnvironment.phonePermission(this@RuleScreeningService) &&
                    AndroidCallEnvironment.audible(this@RuleScreeningService) &&
                    !AndroidCallEnvironment.active(this@RuleScreeningService)

            override fun prepare(token: String, ready: (Boolean) -> Unit) {
                graph.playbackCommands.prepare(
                    token,
                    object : ResultReceiver(handler) {
                        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                            ready(resultCode == 1)
                        }
                    },
                )
            }

            override fun commit(token: String) = graph.playbackCommands.commitPrepared(token)

            override fun cancel(token: String) {
                graph.playbackCommands.cancel(token)
            }
        }

    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val at = details.creationTimeMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val account =
            details.accountHandle?.let { "${it.componentName.flattenToString()}|${it.id}" }
        try {
            ScreeningController(scheduler, playback, graph.incomingCalls).begin(
                number,
                at,
                account,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
            ) { silence ->
                respond(details, silence)
                try {
                    graph.diagnostics.enqueue(DiagnosticEvent.CALL_SCREENED)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            // A composition/startup failure still leaves Telecom's default ringing available.
            respond(details, false)
        }
    }

    private fun respond(details: Call.Details, silence: Boolean) =
        respondToCall(
            details,
            CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSilenceCall(silence)
                .build(),
        )
}
