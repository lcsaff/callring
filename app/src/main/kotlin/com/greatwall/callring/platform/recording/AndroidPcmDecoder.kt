package com.greatwall.callring.platform.recording

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.greatwall.callring.application.ports.recording.AiFailure
import java.nio.ByteOrder

internal class AndroidPcmDecoder {
    fun decode(audio: java.io.File, cancelled: () -> Boolean, maxSamples: Long,
        lengthError: String = "recording.audio_too_long", onSample: (Short) -> Unit) {
        fun checkCancellation() { if (cancelled()) throw AiFailure("recording.cancelled") }
        checkCancellation()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(audio.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AiFailure("recording.unsupported_audio")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw AiFailure("recording.unsupported_audio")
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
                            throw AiFailure("recording.unsupported_audio")
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
                                    if (outputs >= maxSamples) throw AiFailure(lengthError)
                                    onSample((total / channels).toShort()); outputs++
                                }
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { decoder.releaseOutputBuffer(index, false) }
                        advanced = true
                    }
                }
                stalls = if (advanced) 0 else stalls + 1
                if (stalls > 1000) throw AiFailure("recording.audio_decode_failed")
            }
        } catch (failure: AiFailure) {
            throw failure
        } catch (failure: java.io.InterruptedIOException) {
            throw failure
        } catch (_: Exception) {
            throw AiFailure("recording.audio_decode_failed")
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } finally { extractor.release() }
        }
    }
}
