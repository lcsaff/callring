package com.greatwall.callring.ui.overlay

import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.runtime.PhoneActivity
import org.junit.Assert.*
import org.junit.Test

class OverlayVisibilityTest {
    @Test fun unlockWaitsForANewRuntimeReadAndDoesNotRevealEndedCall() {
        val gate = OverlayVisibility()
        assertFalse(gate.visible(false, 1, PhoneActivity.RINGING, false))
        assertFalse(gate.visible(true, 1, PhoneActivity.RINGING, false))
        assertFalse(gate.visible(true, 2, PhoneActivity.IDLE, false))
        assertTrue(gate.visible(true, 3, PhoneActivity.RINGING, false))
        assertFalse(gate.visible(false, 3, PhoneActivity.RINGING, false))
        assertFalse(gate.visible(true, 3, PhoneActivity.RINGING, false))
    }
    @Test fun endedCallEditorCanReturnOnlyAfterFreshRead() {
        val gate = OverlayVisibility()
        assertFalse(gate.visible(true, 0, PhoneActivity.IDLE, true))
        assertTrue(gate.visible(true, 1, PhoneActivity.IDLE, true))
    }
    @Test fun newSessionDoesNotInheritAnOlderModelsReadCounter() {
        val gate = OverlayVisibility()
        gate.session("old")
        assertFalse(gate.visible(true, 30, PhoneActivity.RINGING, false))
        assertTrue(gate.visible(true, 31, PhoneActivity.RINGING, false))
        gate.session("new")
        assertFalse(gate.visible(true, 0, PhoneActivity.UNKNOWN, false))
        assertTrue(gate.visible(true, 1, PhoneActivity.RINGING, false))
    }
}
