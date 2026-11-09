package com.greatwall.callring.platform.recording

import com.greatwall.callring.application.ports.recording.AiClient
import com.greatwall.callring.application.ports.recording.AiFailure
import com.greatwall.callring.application.recording.model.AiConnection
import java.io.File

/** Decodes system formats (including AMR/AAC) and bounds every upload to five minutes. */
class AndroidAudioTranscriber(private val delegate: AiClient, private val scratchDirectory: File,
    private val cancelled: () -> Boolean = { false },
) : AiClient by delegate {
    override fun operation(cancelled: () -> Boolean): AiClient = AndroidAudioTranscriber(delegate.operation(cancelled), scratchDirectory, cancelled)
    override fun cancel() = delegate.cancel()
    override fun transcribe(connection: AiConnection, reference: com.greatwall.callring.application.ports.recording.AudioReference): String {
        val audio = File(reference.location)
        fun checkCancellation() { if (cancelled()) throw AiFailure("recording.cancelled") }
        checkCancellation()
        val writer = PcmWaveChunks(scratchDirectory)
        var chunks = emptyList<File>()
        try {
            AndroidPcmDecoder().decode(audio, cancelled, 16_000L * 7200, onSample = writer::write)
            chunks = writer.finish()
            if (chunks.isEmpty() || writer.audibleSamples < 160) throw AiFailure("recording.silent_audio")
            val text = StringBuilder()
            chunks.forEach { chunk ->
                checkCancellation()
                val part = delegate.transcribe(connection, com.greatwall.callring.application.ports.recording.AudioReference(chunk.absolutePath))
                if (text.length + part.length > 256_000) throw AiFailure("ai.transcript_too_large")
                if (text.isNotEmpty()) text.append('\n')
                text.append(part)
            }
            return text.toString()
        } catch (failure: AiFailure) { throw failure }
        catch (failure: java.io.InterruptedIOException) { throw failure }
        catch (_: Exception) { throw AiFailure("ai.audio_decode_failed") }
        finally {
            writer.abort(); chunks.forEach { it.delete() }
        }
    }
}
