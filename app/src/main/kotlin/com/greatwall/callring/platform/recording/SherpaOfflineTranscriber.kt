package com.greatwall.callring.platform.recording

import android.content.res.AssetManager
import com.greatwall.callring.application.ports.recording.AiFailure
import com.greatwall.callring.application.ports.recording.AudioReference
import com.greatwall.callring.application.ports.recording.OfflineTranscript
import com.greatwall.callring.application.ports.recording.OfflineTranscriber
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import kotlin.math.abs

internal class SherpaOfflineTranscriber(private val assets: AssetManager) : OfflineTranscriber {
    override fun transcribe(audio: AudioReference, cancelled: () -> Boolean, onDecoding: () -> Unit): OfflineTranscript {
        val file = File(audio.location)
        if (!file.isFile) throw AiFailure("recording.missing_audio")
        fun checkActive() {
            if (cancelled()) throw AiFailure("recording.cancelled")
        }
        checkActive()
        val config = OfflineRecognizerConfig()
        config.modelConfig.apply {
            senseVoice.model = "offline/model.int8.onnx"
            senseVoice.language = "zh"
            senseVoice.useInverseTextNormalization = true
            tokens = "offline/tokens.txt"
            numThreads = 2
            modelType = "sense_voice"
        }
        val recognizer = try {
            OfflineRecognizer(assets, config)
        } catch (_: LinkageError) {
            throw AiFailure("recording.offline_model_unavailable")
        } catch (_: Exception) {
            throw AiFailure("recording.offline_model_unavailable")
        }
        try {
            checkActive()
            onDecoding()
            val buffer = FloatArray(16_000 * 15)
            val text = StringBuilder()
            var size = 0
            var quiet = 0
            var audible = 0
            fun flush() {
                checkActive()
                if (audible >= 160) {
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(buffer.copyOf(size), 16_000)
                        recognizer.decode(stream)
                        checkActive()
                        val part = recognizer.getResult(stream).text.trim()
                        if (part.isNotEmpty()) {
                            if (text.length + part.length + (if (text.isEmpty()) 0 else 1) > 256_000) {
                                throw AiFailure("ai.transcript_too_large")
                            }
                            if (text.isNotEmpty()) text.append('\n')
                            text.append(part)
                        }
                    } finally {
                        stream.release()
                    }
                }
                size = 0
                quiet = 0
                audible = 0
            }
            AndroidPcmDecoder().decode(file, cancelled, 16_000L * 600, "recording.offline_audio_too_long") { sample ->
                buffer[size++] = sample / 32768f
                if (abs(sample.toInt()) > 64) {
                    audible++
                    quiet = 0
                } else {
                    quiet++
                }
                if (size == buffer.size || (size >= 16_000 && quiet >= 8_000)) flush()
            }
            flush()
            if (text.isBlank()) throw AiFailure("recording.silent_audio")
            return OfflineTranscript(text.toString(), "sensevoice-int8-2024-07-17")
        } finally {
            recognizer.release()
        }
    }
}
