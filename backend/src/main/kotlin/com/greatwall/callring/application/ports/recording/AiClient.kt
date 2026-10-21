package com.greatwall.callring.application.ports.recording

import com.greatwall.callring.contracts.recording.CallAnalysis
import com.greatwall.callring.application.recording.model.AiConnection

data class AudioReference(val location: String)
interface AiClient {
    fun operation(cancelled: () -> Boolean): AiClient
    fun cancel()
    fun transcribe(connection: AiConnection, reference: AudioReference): String
    fun analyze(connection: AiConnection, transcript: String): CallAnalysis
    fun test(connection: AiConnection, transcription: Boolean)
}

class AiFailure(val reason: String, val retryable: Boolean = false) : Exception(reason)
