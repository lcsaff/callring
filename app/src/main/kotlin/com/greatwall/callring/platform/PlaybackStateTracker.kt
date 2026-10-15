package com.greatwall.callring.platform

import com.greatwall.callring.application.api.*

/** A service publishes observed transitions; UI polling never starts or prepares audio. */
class PlaybackStateTracker {
    @Volatile private var current = PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE)

    fun mark(mode: PlaybackMode, state: PlaybackState) {
        current = PlaybackStatus(mode, state)
    }

    fun clear() {
        current = PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE)
    }

    fun snapshot(): PlaybackStatus = current
}
