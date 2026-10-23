package com.greatwall.callring.ui.overlay

/** Allow bounded recovery if the system closes/backgrounds the card; never fight it forever. */
internal class LockScreenLaunchGate {
    private var session: String? = null
    private var attempts = 0
    private var lastAttempt = 0L
    fun launch(id: String, resumed: Boolean, now: Long): Boolean {
        if (session != id) { reset(); session = id }
        if (resumed || attempts >= 3 || (attempts > 0 && now - lastAttempt < 2_000)) return false
        attempts++
        lastAttempt = now
        return true
    }
    fun reset() { session = null; attempts = 0; lastAttempt = 0 }
}
