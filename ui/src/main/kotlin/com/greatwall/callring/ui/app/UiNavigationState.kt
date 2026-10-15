package com.greatwall.callring.ui.app

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/** Notification intents are handled once, including across activity/process restoration. */
class UiNavigationState(private val savedState: SavedStateHandle) : ViewModel() {
    fun consume(sequence: Long): Boolean {
        if (savedState.get<Long>("handledRequest") == sequence) return false
        savedState["handledRequest"] = sequence
        return true
    }
}
