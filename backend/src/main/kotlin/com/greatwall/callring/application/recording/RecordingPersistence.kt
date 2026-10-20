package com.greatwall.callring.application.recording

import com.greatwall.callring.application.RecordingCall
import com.greatwall.callring.application.RecordingCandidate
import com.greatwall.callring.contracts.recording.CallArchive
import com.greatwall.callring.application.recording.model.RecordingSettings

interface RecordingArchiveState {
    fun get(callId: Long): CallArchive?
    fun generation(callId: Long): Long?
    fun isDeleted(callId: Long): Boolean
}

/** Raw persistence: no authorization, business delta, publication, or command recording. */
interface RecordingArchiveMutations {
    fun compareAndSet(callId: Long, expectedGeneration: Long?, value: CallArchive): Boolean
    fun delete(callId: Long): CallArchive?
}

/** The state machine reads only its target, configuration, and bounded work queue. */
interface RecordingWorkflowReads {
    fun settings(): RecordingSettings
    fun get(callId: Long): CallArchive?
    fun generation(callId: Long): Long?
    fun earliestPendingAt(cancelled: () -> Boolean): Long?
    fun dueCandidates(at: Long, afterId: Long?, limit: Int, cancelled: () -> Boolean): List<RecordingCandidate>
}

data class RecordingRegistration(val call: RecordingCall, val eventInserted: Boolean, val archiveInserted: Boolean)

interface RecordingRegistrationStore {
    /** Event and initial archive insertion, respecting tombstones, share one database transaction. */
    fun insertIncoming(sessionId: String, number: String, at: Long, account: String?, initial: (RecordingCall) -> CallArchive): RecordingRegistration
    fun sessionRecording(sessionId: String): RecordingCall?
    fun insertStatisticsLink(statisticsId: Long, sessionId: String): Boolean
}
