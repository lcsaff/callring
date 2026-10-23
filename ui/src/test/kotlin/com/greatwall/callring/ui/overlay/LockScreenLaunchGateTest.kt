package com.greatwall.callring.ui.overlay

import org.junit.Assert.*
import org.junit.Test

class LockScreenLaunchGateTest {
    @Test fun stoppedActivityCanBeRestoredWithoutAnUnboundedLaunchLoop() {
        val gate = LockScreenLaunchGate()
        assertTrue(gate.launch("one", false, 0))
        assertFalse(gate.launch("one", false, 500))
        assertFalse(gate.launch("one", true, 2_000))
        assertTrue(gate.launch("one", false, 2_001))
        assertTrue(gate.launch("one", false, 4_002))
        assertFalse(gate.launch("one", false, 6_003))
        gate.reset()
        assertTrue(gate.launch("one", false, 6_004))
        assertTrue(gate.launch("two", false, 6_005))
    }
}
