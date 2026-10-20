package com.greatwall.callring.application

import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.application.recording.model.*

data class AudioReference(val location: String)
data class RecordingCandidate(val callId: Long, val generation: Long)
data class RecordingArchiveMetadata(val callId: Long, val generation: Long, val number: String,
    val startedAtEpochMillis: Long, val status: ArchiveStatus, val source: RecordingSource,
    val durationMillis: Long?, val hasAudio: Boolean, val nextAttemptAt: Long)
data class NumberRecordingMetadata(val callId: Long, val generation: Long, val status: ArchiveStatus, val audioBytes: Long, val startedAtEpochMillis: Long)

fun sameRecordingNumber(raw: String, normalized: String): Boolean = raw == normalized ||
    raw.filter(Char::isDigit).let { it.isNotEmpty() && it == normalized.filter(Char::isDigit) }

interface RecordingStore {
    fun isDeleted(callId: Long): Boolean
    fun settings(): RecordingSettings
    fun get(callId: Long): CallArchive?
    fun metadata(callId: Long, cancelled: () -> Boolean): RecordingArchiveMetadata?
    fun generation(callId: Long): Long?
    /** Explicit archive-list capability; ordinary lookups and scheduling must not use it. */
    fun listArchives(): List<CallArchive>
    fun earliestPendingAt(cancelled: () -> Boolean): Long?
    fun dueCandidates(at: Long, afterId: Long?, limit: Int, cancelled: () -> Boolean): List<RecordingCandidate>
    fun recentMetadata(since: Long, cancelled: () -> Boolean): List<RecordingArchiveMetadata>
    fun numberMetadata(number: String, cancelled: () -> Boolean): List<NumberRecordingMetadata>
    /** Expected null inserts only if missing. Generation protects replacement/deletion races. */
    fun compareAndSet(callId: Long, expectedGeneration: Long?, value: CallArchive): Boolean
    fun delete(callId: Long): CallArchive?
}

interface AiClient {
    fun operation(cancelled: () -> Boolean): AiClient
    fun cancel()
    fun transcribe(connection: AiConnection, reference: AudioReference): String
    fun analyze(connection: AiConnection, transcript: String): CallAnalysis
    fun test(connection: AiConnection, transcription: Boolean)
}

class AiFailure(val reason: String, val retryable: Boolean = false) : Exception(reason)
