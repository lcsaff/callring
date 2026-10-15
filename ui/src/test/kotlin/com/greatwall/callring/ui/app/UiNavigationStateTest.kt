package com.greatwall.callring.ui.app

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class UiNavigationStateTest {
    @Test fun restoredNavigationDoesNotReplayAlreadyHandledNotification() {
        val saved = SavedStateHandle()
        val first = UiNavigationState(saved)
        assertTrue(first.consume(3))
        assertFalse(first.consume(3))
        val restored = UiNavigationState(saved)
        assertFalse(restored.consume(3))
        assertTrue(restored.consume(4))
    }
}
