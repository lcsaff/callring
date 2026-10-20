package com.greatwall.callring.platform.ai

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.greatwall.callring.application.AudioReference
import com.greatwall.callring.application.recording.*
import com.greatwall.callring.contracts.common.DocumentRef
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.util.UUID

class AndroidRecordingAudio(
    private val context: Context,
    val directory: File,
    private val metadataReader: ((File) -> Pair<Long, String>)? = null,
) : RecordingAudioFiles {
    private fun owned(reference: AudioReference): File = File(reference.location).canonicalFile.also {
        if (it.parentFile != directory.canonicalFile) throw IOException("recording.audio_not_owned")
    }
    override fun deleteOwned(reference: AudioReference) {
        val file = owned(reference)
        if (file.exists() && !file.delete()) throw IOException("recording.audio_delete_failed")
    }
    override fun prepareImport(document: DocumentRef, cancelled: () -> Boolean): PreparedRecordingAudio {
        val uri = Uri.parse(document.uri)
        require(uri.scheme == "content")
        check(directory.isDirectory || directory.mkdirs())
        val scratch = File(directory, UUID.randomUUID().toString() + ".tmp")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                scratch.outputStream().use { out ->
                    val buffer = ByteArray(16384)
                    var total = 0L
                    while (true) {
                        if (cancelled()) throw CancellationException("recording.cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 256L * 1024 * 1024)
                        out.write(buffer, 0, count)
                    }
                    out.fd.sync()
                }
            }
            if (cancelled()) throw CancellationException("recording.cancelled")
            val metadata = metadata(scratch)
            return Prepared(scratch, File(directory, UUID.randomUUID().toString() + "." + metadata.second), metadata.first)
        } catch (failure: Exception) { scratch.delete(); throw failure }
    }
    override fun prepareCapture(reference: AudioReference): PreparedRecordingAudio {
        val file = owned(reference)
        return Prepared(file, file, metadata(file).first)
    }
    private class Prepared(private val scratch: File, private val target: File, override val durationMillis: Long) : PreparedRecordingAudio {
        private var committed = false
        private var retained = false
        override fun commit(): AudioReference {
            check(!committed)
            check(scratch == target || scratch.renameTo(target)) { "recording.audio_rename_failed" }
            committed = true
            return AudioReference(target.absolutePath)
        }
        override fun retain() { check(committed); retained = true }
        override fun close() { if (!retained) { scratch.delete(); if (committed) target.delete() } }
    }
    private fun metadata(file: File): Pair<Long, String> {
        metadataReader?.let { return it(file) }
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.absolutePath)
            val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(duration > 0)
            val extension = when (reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)) {
                "audio/mpeg" -> "mp3"
                "audio/ogg", "application/ogg" -> "ogg"
                "audio/flac" -> "flac"
                "audio/amr", "audio/3gpp" -> "amr"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/aac" -> "aac"
                "audio/webm", "video/webm" -> "webm"
                else -> "m4a"
            }
            return duration to extension
        } finally { reader.release() }
    }
}
