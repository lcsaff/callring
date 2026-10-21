package com.greatwall.callring.platform.recording

import com.greatwall.callring.application.ports.recording.PreparedRecordingPlayback
import com.greatwall.callring.application.ports.recording.RecordingPlayback

import android.media.MediaPlayer
import com.greatwall.callring.application.ports.recording.AudioReference
import com.greatwall.callring.application.recording.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.application.ports.recording.RecordingPlaybackObservation
import java.io.File
import java.io.IOException

class AndroidRecordingPlayback(private val directory: File, private val finished: () -> Unit) : RecordingPlayback, AutoCloseable {
    private val lock = Any()
    private var player: MediaPlayer? = null
    private var callId: Long? = null
    private class Prepared(val player: MediaPlayer) : PreparedRecordingPlayback {
        override fun close() { player.release() }
    }
    override fun prepare(reference: AudioReference): PreparedRecordingPlayback {
        val file = File(reference.location).canonicalFile
        if (file.parentFile != directory.canonicalFile) throw IOException("recording.audio_not_owned")
        val next = MediaPlayer()
        try { next.setDataSource(file.absolutePath); next.prepare(); return Prepared(next) }
        catch (failure: Exception) { next.release(); throw failure }
    }
    override fun activate(prepared: PreparedRecordingPlayback, callId: Long) = synchronized(lock) {
        val next = (prepared as Prepared).player
        next.setOnCompletionListener { finish(it) }
        next.setOnErrorListener { value, _, _ -> finish(value); true }
        next.start()
        player = next
        this.callId = callId
    }
    private fun finish(value: MediaPlayer) {
        val changed = synchronized(lock) { if (player === value) { stop(); true } else false }
        if (changed) finished()
    }
    override fun stop(): Boolean = synchronized(lock) {
        val changed = player != null
        player?.release(); player = null; callId = null
        changed
    }
    override fun status(): PlaybackStatus = synchronized(lock) {
        PlaybackStatus(if (player == null) PlaybackMode.NONE else PlaybackMode.PREVIEW,
            if (player?.isPlaying == true) PlaybackState.PLAYING else PlaybackState.IDLE)
    }
    override fun observation(): RecordingPlaybackObservation = synchronized(lock) {
        RecordingPlaybackObservation(PlaybackStatus(if (player == null) PlaybackMode.NONE else PlaybackMode.PREVIEW,
            if (player?.isPlaying == true) PlaybackState.PLAYING else PlaybackState.IDLE), callId)
    }
    override fun close() { stop() }
}
