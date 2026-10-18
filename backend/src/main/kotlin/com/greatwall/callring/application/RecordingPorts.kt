package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
import java.io.File

interface RecordingStore {
    fun isDeleted(callId: Long): Boolean = false
    fun settings(): RecordingSettings
    fun saveSettings(value: RecordingSettings)
    fun get(callId: Long): CallArchive?
    fun all(): List<CallArchive>
    /** Expected null inserts only if missing. Generation protects replacement/deletion races. */
    fun compareAndSet(callId: Long, expectedGeneration: Long?, value: CallArchive): Boolean
    fun delete(callId: Long): CallArchive?
}

interface AiClient {
    fun operation(cancelled: () -> Boolean): AiClient = this
    fun cancel() {}
    fun transcribe(connection: AiConnection, audio: File): String
    fun analyze(connection: AiConnection, transcript: String): CallAnalysis
    fun test(connection: AiConnection, transcription: Boolean)
}

class AiFailure(val reason: String, val retryable: Boolean = false) : Exception(reason)
