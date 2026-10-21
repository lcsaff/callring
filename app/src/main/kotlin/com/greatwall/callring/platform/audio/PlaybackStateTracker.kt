package com.greatwall.callring.platform.audio

import com.greatwall.callring.contracts.runtime.*

/** Only observed transitions publish; submitting a service intent is not a playback transition. */
class PlaybackStateTracker(private val publish: () -> Unit) {
    @Volatile private var current = PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE)

    @Synchronized fun mark(mode: PlaybackMode, state: PlaybackState) {
        val next = PlaybackStatus(mode, state)
        if (current == next) return
        current = next
        publish()
    }

    fun clear() = mark(PlaybackMode.NONE, PlaybackState.IDLE)
    fun snapshot(): PlaybackStatus = current
}
