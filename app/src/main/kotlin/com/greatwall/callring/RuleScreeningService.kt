package com.greatwall.callring

import android.content.Intent
import android.os.*
import android.telecom.Call
import android.telecom.CallScreeningService
import com.greatwall.callring.application.api.CallDecision
import com.greatwall.callring.bootstrap.BackendProvider
import com.greatwall.callring.domain.IncomingDecision
import com.greatwall.callring.platform.DiagnosticEvent
import com.greatwall.callring.platform.calling.AndroidCallEnvironment
import com.greatwall.callring.platform.calling.IncomingCallSession
import java.util.UUID

/**
 * Telecom adapter. Decisions and persistence live behind the graph; presentation is an injected
 * sink.
 */
class RuleScreeningService : CallScreeningService() {
    private val handler = Handler(Looper.getMainLooper())
    private val graph
        get() = BackendProvider.from(this).backendGraph

    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        val at = details.creationTimeMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val account =
            details.accountHandle?.let { "${it.componentName.flattenToString()}|${it.id}" }
        var inspected = false
        val timeout = Runnable {
            if (!inspected) {
                inspected = true
                respond(details, false)
            }
        }
        handler.postDelayed(timeout, 800)
        try {
            graph.incomingCalls.execute {
                try {
                    val session =
                        graph.incomingCalls.inspect(
                            UUID.randomUUID().toString(),
                            number,
                            at,
                            account,
                        )
                    // Quiet decisions do not depend on audio availability or its publication lock.
                    val enabled =
                        if (session.assessment.matched && !session.assessment.silence) {
                            try {
                                graph.audio.getSettings().enabled
                            } catch (_: Exception) {
                                false
                            }
                        } else false
                    handler.post {
                        if (inspected)
                            finish(
                                session,
                                CallDecision.SYSTEM_RINGTONE_FALLBACK,
                                session.assessment,
                            )
                        else {
                            inspected = true
                            handler.removeCallbacks(timeout)
                            screen(details, session, enabled)
                        }
                    }
                } catch (_: Exception) {
                    handler.post {
                        if (!inspected) {
                            inspected = true
                            handler.removeCallbacks(timeout)
                            respond(details, false)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            timeout.run()
        }
    }

    private fun screen(details: Call.Details, session: IncomingCallSession, enabled: Boolean) {
        val assessment = session.assessment
        if (assessment.silence) {
            respond(details, true)
            finish(session, CallDecision.QUIET_SILENCED, assessment)
            return
        }
        val eligible =
            try {
                assessment.matched &&
                    enabled &&
                    graph.audio.enabledForCalls() &&
                    AndroidCallEnvironment.phonePermission(this) &&
                    AndroidCallEnvironment.audible(this) &&
                    !AndroidCallEnvironment.active(this)
            } catch (_: Exception) {
                false
            }
        if (!eligible) {
            respond(details, false)
            finish(session, session.initialDecision, assessment)
            return
        }
        val token = UUID.randomUUID().toString()
        val deadline = SystemClock.elapsedRealtime() + 1800
        var finished = false
        val fallback = Runnable {
            if (!finished) {
                finished = true
                respond(details, false)
                RingPlaybackService.command(this, RingPlaybackService.CANCEL, token)
                finish(session, CallDecision.SYSTEM_RINGTONE_FALLBACK, assessment)
            }
        }
        handler.postDelayed(fallback, 1800)
        val ready =
            object : ResultReceiver(handler) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (finished) {
                        RingPlaybackService.command(
                            this@RuleScreeningService,
                            RingPlaybackService.CANCEL,
                            token,
                        )
                        return
                    }
                    if (resultCode != 1 || SystemClock.elapsedRealtime() >= deadline) {
                        fallback.run()
                        return
                    }
                    try {
                        graph.incomingCalls.execute {
                            try {
                                val latest = graph.incomingCalls.inspectAgain(session)
                                handler.post {
                                    if (finished) {
                                        RingPlaybackService.command(
                                            this@RuleScreeningService,
                                            RingPlaybackService.CANCEL,
                                            token,
                                        )
                                        return@post
                                    }
                                    // READY reservation and Telecom response share the service main
                                    // looper.
                                    val success =
                                        try {
                                            !latest.silence &&
                                                SystemClock.elapsedRealtime() < deadline &&
                                                graph.audio.enabledForCalls() &&
                                                RingPlaybackService.commitPrepared(token)
                                        } catch (_: Exception) {
                                            false
                                        }
                                    finished = true
                                    handler.removeCallbacks(fallback)
                                    respond(details, latest.silence || success)
                                    if (!success)
                                        RingPlaybackService.command(
                                            this@RuleScreeningService,
                                            RingPlaybackService.CANCEL,
                                            token,
                                        )
                                    val decision =
                                        when {
                                            latest.silence -> CallDecision.QUIET_SILENCED
                                            latest.whitelisted -> CallDecision.WHITELIST_ALLOWED
                                            success -> CallDecision.CUSTOM_RINGTONE
                                            else -> CallDecision.SYSTEM_RINGTONE_FALLBACK
                                        }
                                    finish(session, decision, latest)
                                }
                            } catch (_: Exception) {
                                handler.post(fallback)
                            }
                        }
                    } catch (_: Exception) {
                        fallback.run()
                    }
                }
            }
        try {
            startForegroundService(
                Intent(this, RingPlaybackService::class.java)
                    .setAction(RingPlaybackService.PREPARE)
                    .putExtra("token", token)
                    .putExtra("ready", ready)
            )
        } catch (_: Exception) {
            fallback.run()
        }
    }

    private fun finish(
        session: IncomingCallSession,
        decision: CallDecision,
        latest: IncomingDecision,
    ) {
        try {
            graph.incomingCalls.complete(session, decision)
        } catch (_: Exception) {}
        try {
            graph.incomingCalls.show(session, decision, latest)
        } catch (_: Exception) {}
        try {
            graph.diagnostics.enqueue(DiagnosticEvent.CALL_SCREENED)
        } catch (_: Exception) {}
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
