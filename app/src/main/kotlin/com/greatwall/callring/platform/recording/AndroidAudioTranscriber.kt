package com.greatwall.callring.platform.recording

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.greatwall.callring.application.AiClient
import com.greatwall.callring.application.AiFailure
import com.greatwall.callring.application.recording.model.AiConnection
import java.io.File
import java.nio.ByteOrder

/** Decodes system formats (including AMR/AAC) and bounds every upload to five minutes. */
class AndroidAudioTranscriber(private val delegate: AiClient, private val scratchDirectory: File,
    private val cancelled: () -> Boolean = { false },
) : AiClient by delegate {
    override fun operation(cancelled: () -> Boolean): AiClient = AndroidAudioTranscriber(delegate.operation(cancelled), scratchDirectory, cancelled)
    override fun cancel() = delegate.cancel()
    override fun transcribe(connection: AiConnection, reference: com.greatwall.callring.application.AudioReference): String {
        val audio = File(reference.location)
        fun checkCancellation() { if (cancelled()) throw AiFailure("recording.cancelled") }
        checkCancellation()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val writer = PcmWaveChunks(scratchDirectory)
        var chunks = emptyList<File>()
        try {
            extractor.setDataSource(audio.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AiFailure("ai.unsupported_audio")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw AiFailure("ai.unsupported_audio")
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcm = AudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false; var outputEnded = false
            val info = MediaCodec.BufferInfo()
            var frames = 0L; var outputs = 0L
            var stalls = 0
            while (!outputEnded) {
                checkCancellation()
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                var advanced = false
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(index))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance()
                        }
                        advanced = true
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcm = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        if (rate !in 8_000..192_000 || channels !in 1..8 || pcm !in setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT))
                            throw AiFailure("ai.unsupported_audio")
                        advanced = true
                    }
                    else -> if (index >= 0) {
                        try {
                            val buffer = requireNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val frameBytes = channels * if (pcm == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            while (buffer.remaining() >= frameBytes) {
                                var total = 0
                                repeat(channels) {
                                    total += if (pcm == AudioFormat.ENCODING_PCM_FLOAT) (buffer.float.coerceIn(-1f, 1f) * 32767).toInt()
                                    else buffer.short.toInt()
                                }
                                frames++
                                val target = frames * 16_000 / rate
                                while (outputs < target) {
                                    if (outputs >= 16_000L * 7200) throw AiFailure("ai.audio_too_long")
                                    writer.write((total / channels).toShort()); outputs++
                                }
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { decoder.releaseOutputBuffer(index, false) }
                        advanced = true
                    }
                }
                stalls = if (advanced) 0 else stalls + 1
                if (stalls > 1000) throw AiFailure("ai.audio_decode_failed")
            }
            chunks = writer.finish()
            if (chunks.isEmpty() || writer.audibleSamples < 160) throw AiFailure("recording.silent_audio")
            val text = StringBuilder()
            chunks.forEach { chunk ->
                checkCancellation()
                val part = delegate.transcribe(connection, com.greatwall.callring.application.AudioReference(chunk.absolutePath))
                if (text.length + part.length > 256_000) throw AiFailure("ai.transcript_too_large")
                if (text.isNotEmpty()) text.append('\n')
                text.append(part)
            }
            return text.toString()
        } catch (failure: AiFailure) { throw failure }
        catch (failure: java.io.InterruptedIOException) { throw failure }
        catch (_: Exception) { throw AiFailure("ai.audio_decode_failed") }
        finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release(); extractor.release()
            writer.abort(); chunks.forEach { it.delete() }
        }
    }
}
