package com.greatwall.callring.application.ports.recording

data class OfflineTranscript(val text: String, val model: String)

fun interface OfflineTranscriber {
    fun transcribe(audio: AudioReference, cancelled: () -> Boolean, onDecoding: () -> Unit): OfflineTranscript
}
