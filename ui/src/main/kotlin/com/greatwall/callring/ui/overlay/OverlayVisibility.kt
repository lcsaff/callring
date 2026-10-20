package com.greatwall.callring.ui.overlay

import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.runtime.PhoneActivity

/** A screen becoming usable must not reveal a cached pre-lock ringing snapshot. */
internal class OverlayVisibility {
    private var usable = false
    private var minimumRead = Long.MAX_VALUE
    private var identity: String? = null
    fun session(id: String) {
        if (identity != id) { identity = id; usable = false; minimumRead = Long.MAX_VALUE }
    }
    fun visible(screenUsable: Boolean, runtimeRead: Long, phone: PhoneActivity, editing: Boolean): Boolean {
        if (!screenUsable) { usable = false; return false }
        if (!usable) {
            usable = true
            minimumRead = runtimeRead
            return false
        }
        return runtimeRead > minimumRead && (editing || phone == PhoneActivity.RINGING)
    }
}
