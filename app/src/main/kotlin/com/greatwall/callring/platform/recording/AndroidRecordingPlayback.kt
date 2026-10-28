package com.greatwall.callring.platform.recording

import com.greatwall.callring.application.ports.recording.PreparedRecordingPlayback
import com.greatwall.callring.application.ports.recording.RecordingPlayback
import android.media.MediaPlayer
import com.greatwall.callring.application.ports.recording.AudioReference
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.application.ports.recording.RecordingPlaybackObservation
import com.greatwall.callring.platform.ownership.OwnedFileAccess
import java.io.File
import java.io.IOException
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class AndroidRecordingPlayback(private val directory: File, private val finished: () -> Unit) : RecordingPlayback, AutoCloseable {
    private val lock = Any()
    private val fileAccess = OwnedFileAccess()
    @Volatile private var closed = false
    private var current: Prepared? = null
    private var callId: Long? = null
    private class Prepared(val player: MediaPlayer, private val lease: Closeable) : PreparedRecordingPlayback {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) try { player.release() } finally { lease.close() } }
    }
    override fun prepare(reference: AudioReference): PreparedRecordingPlayback {
        val lease = fileAccess.acquire()
        var next: MediaPlayer? = null
        try {
            val file = File(reference.location).canonicalFile
            if (file.parentFile != directory.canonicalFile) throw IOException("recording.audio_not_owned")
            next = MediaPlayer(); next.setDataSource(file.absolutePath); next.prepare()
            return Prepared(next, lease)
        } catch (failure: Throwable) { try { next?.release() } finally { lease.close() }; throw failure }
    }
    override fun activate(prepared: PreparedRecordingPlayback, callId: Long) = synchronized(lock) {
        fileAccess.checkOpen()
        val next = prepared as Prepared
        next.player.setOnCompletionListener { finish(it) }
        next.player.setOnErrorListener { value, _, _ -> finish(value); true }
        next.player.start(); current = next; this.callId = callId
    }
    private fun finish(value: MediaPlayer) {
        val changed = synchronized(lock) { if (current?.player === value) { stop(); true } else false }
        if (changed && !closed) finished()
    }
    override fun stop(): Boolean = synchronized(lock) {
        val changed = current != null
        current?.close(); current = null; callId = null
        changed
    }
    override fun status(): PlaybackStatus = synchronized(lock) {
        PlaybackStatus(if (current == null) PlaybackMode.NONE else PlaybackMode.PREVIEW,
            if (current?.player?.isPlaying == true) PlaybackState.PLAYING else PlaybackState.IDLE)
    }
    override fun observation(): RecordingPlaybackObservation = synchronized(lock) {
        RecordingPlaybackObservation(status(), callId)
    }
    fun seal() { closed = true; fileAccess.seal() }
    override fun close() { seal(); stop(); fileAccess.close() }
}
