package com.greatwall.callring.application.ports.recording

import com.greatwall.callring.contracts.recording.ArchiveStatus
import com.greatwall.callring.contracts.recording.RecordingSource

data class RecordingCandidate(val callId: Long, val generation: Long)
data class RecordingArchiveMetadata(val callId: Long, val generation: Long, val number: String,
    val startedAtEpochMillis: Long, val status: ArchiveStatus, val source: RecordingSource,
    val durationMillis: Long?, val hasAudio: Boolean, val nextAttemptAt: Long)
data class NumberRecordingMetadata(val callId: Long, val generation: Long, val status: ArchiveStatus, val audioBytes: Long, val startedAtEpochMillis: Long)
