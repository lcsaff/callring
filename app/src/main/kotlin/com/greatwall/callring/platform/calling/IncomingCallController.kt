package com.greatwall.callring.platform.calling

import com.greatwall.callring.application.recording.IncomingRecordingSession
import com.greatwall.callring.domain.calling.RecordingCall
import com.greatwall.callring.contracts.callers.IncomingCallUiSink
import java.io.Closeable
import java.time.Clock

/** Connects durable incoming identities with the live recording session and platform recovery. */
class IncomingCallController(
    engine: IncomingCallEngine,
    presentation: IncomingCallUiSink,
    acquireLease: () -> Closeable,
    observeIncoming: (String, String, Long, String?) -> RecordingCall?,
    linkStatistics: (String, Long) -> Unit,
    private val session: IncomingRecordingSession,
    clock: Clock,
    phoneState: () -> Unit,
    recover: () -> Unit,
    failure: () -> Unit,
) {
    val coordinator =
        IncomingCallCoordinator(
            engine,
            presentation,
            workLease = { acquireLease() },
            onIncoming = { sessionId, number, at, account ->
                observeIncoming(sessionId, number, at, account)?.let { call ->
                    session.register(call.id, call.startedAt)
                    // A fast answer can precede background persistence. Recheck this same live
                    // session once its independent identity is durable; IDLE clears it first.
                    if (session.activeCandidate(clock.millis()) == call.id)
                        phoneState()
                }
            },
            onStatisticsRecorded = { sessionId, id -> linkStatistics(sessionId, id) },
            afterCompletion = {
                recover()
            },
        ) {
            failure()
        }
}
