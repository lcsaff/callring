package com.greatwall.callring.application.ports.recording

import com.greatwall.callring.domain.calling.RecordingCall

import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.common.DocumentRef
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.runtime.PlaybackStatus


interface RecordingIdentityReads {
    fun archiveStamp(): String
    fun ownedAudio(callId: Long, cancelled: () -> Boolean): AudioReference?
    fun findRecording(id: Long): RecordingCall?
    fun linkedRecording(statisticsId: Long): RecordingCall?
    fun metadata(callId: Long, cancelled: () -> Boolean): RecordingArchiveMetadata?
    fun queryArchives(query: ArchiveListQuery, cancelled: () -> Boolean): ArchiveListPage
    fun numberMetadata(number: String, cancelled: () -> Boolean): List<NumberRecordingMetadata>
    fun recentMetadata(since: Long, cancelled: () -> Boolean): List<RecordingArchiveMetadata>
}

/** Prepared audio owns its scratch/new file until retained after a durable attachment. */
interface PreparedRecordingAudio : AutoCloseable {
    val durationMillis: Long
    fun commit(): AudioReference
    fun retain()
}
interface RecordingAudioFiles {
    fun ownedBytes(reference: AudioReference): Long?
    fun prepareImport(document: DocumentRef, cancelled: () -> Boolean): PreparedRecordingAudio
    fun prepareCapture(reference: AudioReference): PreparedRecordingAudio
    fun deleteOwned(reference: AudioReference)
}
interface PreparedRecordingPlayback : AutoCloseable
data class RecordingPlaybackObservation(val status: PlaybackStatus, val callId: Long?)

interface RecordingPlayback {
    fun prepare(reference: AudioReference): PreparedRecordingPlayback
    fun activate(prepared: PreparedRecordingPlayback, callId: Long)
    fun stop(): Boolean
    fun status(): PlaybackStatus
    fun observation(): RecordingPlaybackObservation
}
interface RecordingDirectory {
    fun authorize(document: DocumentRef)
    fun list(folder: String): List<RecordingFile>
    fun inspect(folder: String, document: DocumentRef): RecordingFile?
}
