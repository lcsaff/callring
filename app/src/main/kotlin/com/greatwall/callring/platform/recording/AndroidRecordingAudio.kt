package com.greatwall.callring.platform.recording

import com.greatwall.callring.application.ports.recording.PreparedRecordingAudio
import com.greatwall.callring.application.ports.recording.RecordingAudioFiles

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.greatwall.callring.application.ports.recording.AudioReference
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
) : RecordingAudioFiles, AutoCloseable {
    private val fileAccess = com.greatwall.callring.platform.ownership.OwnedFileAccess()
    override fun ownedBytes(reference: AudioReference): Long? = runCatching { fileAccess.acquire().use {
        val file = owned(reference)
        if (!file.exists()) 0L else { check(file.isFile && file.canRead()); file.length() }
    } }.getOrNull()
    private fun owned(reference: AudioReference): File = File(reference.location).canonicalFile.also {
        if (it.parentFile != directory.canonicalFile) throw IOException("recording.audio_not_owned")
    }
    override fun deleteOwned(reference: AudioReference) = fileAccess.acquire().use {
        val file = owned(reference)
        if (file.exists() && !file.delete()) throw IOException("recording.audio_delete_failed")
    }
    override fun prepareImport(document: DocumentRef, cancelled: () -> Boolean): PreparedRecordingAudio {
        val lease = fileAccess.acquire()
        return try { prepareImportLeased(document, { fileAccess.checkOpen(); cancelled() }, lease) }
        catch (failure: Throwable) { lease.close(); throw failure }
    }
    private fun prepareImportLeased(document: DocumentRef, cancelled: () -> Boolean, lease: java.io.Closeable): PreparedRecordingAudio {
        val uri = Uri.parse(document.uri)
        require(uri.scheme == "content")
        check(directory.isDirectory || directory.mkdirs())
        com.greatwall.callring.platform.storage.StorageSpaceGuard.requireWritable(directory)
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
                        if (total % (1024 * 1024) < count) com.greatwall.callring.platform.storage.StorageSpaceGuard.requireWritable(directory)
                        out.write(buffer, 0, count)
                    }
                    out.fd.sync()
                }
            }
            if (cancelled()) throw CancellationException("recording.cancelled")
            val metadata = metadata(scratch)
            return Prepared(scratch, File(directory, UUID.randomUUID().toString() + "." + metadata.second), metadata.first, lease)
        } catch (failure: Exception) { scratch.delete(); throw failure }
    }
    override fun prepareCapture(reference: AudioReference): PreparedRecordingAudio {
        val lease = fileAccess.acquire()
        return try { val file = owned(reference); Prepared(file, file, metadata(file).first, lease) }
        catch (failure: Throwable) { lease.close(); throw failure }
    }
    private class Prepared(private val scratch: File, private val target: File, override val durationMillis: Long, private val lease: java.io.Closeable) : PreparedRecordingAudio {
        private var committed = false
        private var retained = false
        override fun commit(): AudioReference {
            check(!committed)
            check(scratch == target || scratch.renameTo(target)) { "recording.audio_rename_failed" }
            committed = true
            return AudioReference(target.absolutePath)
        }
        override fun retain() { check(committed); retained = true }
        override fun close() { try { if (!retained) { scratch.delete(); if (committed) target.delete() } } finally { lease.close() } }
    }
    fun seal() = fileAccess.seal()
    override fun close() = fileAccess.close()
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
