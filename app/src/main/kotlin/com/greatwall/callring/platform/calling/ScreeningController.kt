package com.greatwall.callring.platform.calling

import com.greatwall.callring.contracts.callers.CallDecision
import com.greatwall.callring.domain.calling.IncomingDecision
import java.util.concurrent.CompletableFuture

fun interface ScreeningCancellation {
    fun cancel()
}

/** post/after execute on one serial event loop; nowMillis is monotonic. */
interface ScreeningScheduler {
    fun nowMillis(): Long

    fun post(task: Runnable)

    fun after(delayMillis: Long, task: Runnable): ScreeningCancellation
}

interface ScreeningPlayback {
    /** Called on the existing decision executor, never the response event loop. */
    fun readEnabled(): Boolean

    /** Fast observed state only: enabled mirror, permission, audibility and phone activity. */
    fun canReplace(): Boolean

    fun prepare(token: String, ready: (Boolean) -> Unit)

    fun commit(token: String): Boolean

    fun cancel(token: String)
}

/** Uses the coordinator's existing executors and persistence queue. */
interface IncomingCallOperations {
    fun execute(task: Runnable)

    fun inspect(sessionId: String, raw: String, at: Long, account: String?): IncomingCallSession

    fun inspectAgain(session: IncomingCallSession): IncomingDecision

    fun complete(session: IncomingCallSession, decision: CallDecision): CompletableFuture<Unit>

    fun show(session: IncomingCallSession, decision: CallDecision, latest: IncomingDecision)
}

/**
 * Deadline orchestration only. begin and all mutable attempt state belong to the scheduler loop.
 */
class ScreeningController(
    private val scheduler: ScreeningScheduler,
    private val playback: ScreeningPlayback,
    private val calls: IncomingCallOperations,
) {
    fun begin(
        number: String,
        at: Long,
        account: String?,
        sessionId: String,
        token: String,
        respondSilence: (Boolean) -> Unit,
    ) {
        Attempt(number, at, account, sessionId, token, respondSilence).start()
    }

    private enum class Phase {
        INSPECTING,
        PREPARING,
        RECHECKING,
        FINISHED,
    }

    private inner class Attempt(
        val number: String,
        val at: Long,
        val account: String?,
        val sessionId: String,
        val token: String,
        val respondSilence: (Boolean) -> Unit,
    ) {
        var phase = Phase.INSPECTING
        var responded = false
        var responseSucceeded = false
        var audioCommitted = false
        var finalized = false
        var session: IncomingCallSession? = null
        var inspectionTimer: ScreeningCancellation? = null
        var preparationTimer: ScreeningCancellation? = null
        val inspectionDeadline = deadlineAfter(INSPECTION_MILLIS)
        var preparationDeadline = 0L

        fun start() {
            inspectionTimer = scheduler.after(INSPECTION_MILLIS, Runnable { failInspection() })
            try {
                calls.execute {
                    try {
                        val inspected = calls.inspect(sessionId, number, at, account)
                        // Quiet/unmatched calls never load audio preferences or wait for import IO.
                        val enabled =
                            if (inspected.assessment.matched && !inspected.assessment.silence) {
                                try {
                                    playback.readEnabled()
                                } catch (_: Exception) {
                                    false
                                }
                            } else false
                        scheduler.post { onInspected(inspected, enabled) }
                    } catch (_: Exception) {
                        scheduler.post { failInspection() }
                    }
                }
            } catch (_: Exception) {
                failInspection()
            }
        }

        fun failInspection() {
            if (phase != Phase.INSPECTING) return
            phase = Phase.FINISHED
            inspectionTimer?.cancel()
            respond(false)
        }

        fun onInspected(inspected: IncomingCallSession, enabled: Boolean) {
            if (finalized) return
            session = inspected
            if (phase == Phase.FINISHED || scheduler.nowMillis() >= inspectionDeadline) {
                failInspection()
                finalize(inspected, CallDecision.SYSTEM_RINGTONE_FALLBACK, inspected.assessment)
                return
            }
            inspectionTimer?.cancel()
            val assessment = inspected.assessment
            if (assessment.silence) {
                finish(inspected, true, CallDecision.QUIET_SILENCED, assessment, false)
            } else if (!assessment.matched || !enabled || !safeCanReplace()) {
                finish(inspected, false, inspected.initialDecision, assessment, false)
            } else {
                phase = Phase.PREPARING
                preparationDeadline = deadlineAfter(PREPARATION_MILLIS)
                preparationTimer = scheduler.after(PREPARATION_MILLIS, Runnable { fallback() })
                try {
                    playback.prepare(token) { ready -> scheduler.post { onReady(ready) } }
                } catch (_: Exception) {
                    fallback()
                }
            }
        }

        fun onReady(ready: Boolean) {
            if (phase == Phase.FINISHED) {
                if (!audioCommitted) safeCancel()
                return
            }
            if (phase != Phase.PREPARING) return
            if (!ready || scheduler.nowMillis() >= preparationDeadline) {
                fallback()
                return
            }
            phase = Phase.RECHECKING
            val inspected = session ?: return
            try {
                calls.execute {
                    try {
                        val latest = calls.inspectAgain(inspected)
                        scheduler.post { onRechecked(inspected, latest) }
                    } catch (_: Exception) {
                        scheduler.post { fallback() }
                    }
                }
            } catch (_: Exception) {
                fallback()
            }
        }

        fun onRechecked(inspected: IncomingCallSession, latest: IncomingDecision) {
            if (phase == Phase.FINISHED) {
                if (!audioCommitted) safeCancel()
                return
            }
            if (phase != Phase.RECHECKING) return
            if (scheduler.nowMillis() >= preparationDeadline) {
                fallback()
                return
            }
            if (latest.silence) {
                finish(inspected, true, CallDecision.QUIET_SILENCED, latest, true)
                return
            }
            val committed =
                try {
                    safeCanReplace() &&
                        scheduler.nowMillis() < preparationDeadline &&
                        playback.commit(token)
                } catch (_: Exception) {
                    false
                }
            audioCommitted = committed
            val decision =
                when {
                    committed -> CallDecision.CUSTOM_RINGTONE
                    latest.whitelisted -> CallDecision.WHITELIST_ALLOWED
                    else -> CallDecision.SYSTEM_RINGTONE_FALLBACK
                }
            finish(inspected, committed, decision, latest, !committed)
        }

        fun fallback() {
            if (phase != Phase.PREPARING && phase != Phase.RECHECKING) return
            val inspected = session ?: return
            finish(
                inspected,
                false,
                CallDecision.SYSTEM_RINGTONE_FALLBACK,
                inspected.assessment,
                true,
            )
        }

        fun finish(
            inspected: IncomingCallSession,
            silence: Boolean,
            decision: CallDecision,
            latest: IncomingDecision,
            cancel: Boolean,
        ) {
            val ownsAudio = phase == Phase.PREPARING || phase == Phase.RECHECKING
            phase = Phase.FINISHED
            inspectionTimer?.cancel()
            preparationTimer?.cancel()
            if (cancel) {
                audioCommitted = false
                safeCancel()
            }
            val delivered = respond(silence)
            if (!delivered && ownsAudio && !cancel) {
                audioCommitted = false
                safeCancel()
            }
            finalize(
                inspected,
                if (!delivered && silence) CallDecision.SYSTEM_RINGTONE_FALLBACK else decision,
                latest,
            )
        }

        fun respond(silence: Boolean): Boolean {
            if (responded) return responseSucceeded
            responded = true
            responseSucceeded =
                try {
                    respondSilence(silence)
                    true
                } catch (_: Exception) {
                    false
                }
            return responseSucceeded
        }

        fun finalize(
            inspected: IncomingCallSession,
            decision: CallDecision,
            latest: IncomingDecision,
        ) {
            if (finalized) return
            finalized = true
            try {
                calls.complete(inspected, decision)
            } catch (_: Exception) {
                /* Presentation is independent. */
            }
            try {
                calls.show(inspected, decision, latest)
            } catch (_: Exception) {
                /* UI cannot change the call response. */
            }
        }

        fun safeCanReplace(): Boolean =
            try {
                playback.canReplace()
            } catch (_: Exception) {
                false
            }

        fun safeCancel() {
            try {
                playback.cancel(token)
            } catch (_: Exception) {
                /* Retain system ringing. */
            }
        }

        fun deadlineAfter(delay: Long): Long =
            scheduler.nowMillis().let { now ->
                if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
            }
    }

    companion object {
        const val INSPECTION_MILLIS = 800L
        const val PREPARATION_MILLIS = 1800L
    }
}
