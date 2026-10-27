package com.greatwall.callring;

import com.greatwall.callring.platform.calling.SessionGate;

import org.junit.Test;
import static org.junit.Assert.*;

public class SessionGateTest {
    @Test public void initialIdleIsNotMistakenForHangup() {
        SessionGate gate = new SessionGate("a", false, 1000);
        assertEquals(SessionGate.WAIT, gate.update(false, false, 1100));
        assertEquals(SessionGate.PLAY, gate.update(true, false, 1300));
        assertEquals(SessionGate.PLAY, gate.update(true, false, 1400));
        assertEquals(SessionGate.STOP, gate.update(false, false, 1500));
    }
    @Test public void answerStopsEvenIfAnotherCallIsRinging() {
        SessionGate gate = new SessionGate("a", false, 0);
        gate.update(true, false, 100);
        assertEquals(SessionGate.STOP, gate.update(true, true, 200));
    }
    @Test public void missingCallAndMaximumDurationStop() {
        assertEquals(SessionGate.STOP, new SessionGate("a", false, 0).update(false, false, 4000));
        assertEquals(SessionGate.STOP, new SessionGate("a", false, 0).update(true, false, 90000));
    }
    @Test public void previewContinuesUntilStoppedAndYieldsToCalls() {
        SessionGate preview = new SessionGate("a", true, 0);
        assertEquals(SessionGate.PLAY, preview.update(false, false, 100));
        assertEquals(SessionGate.PLAY, preview.update(false, false, 8000));
        assertEquals(SessionGate.PLAY, preview.update(false, false, 3600000));
        assertEquals(SessionGate.STOP, new SessionGate("b", true, 0).update(true, false, 100));
        assertEquals(SessionGate.STOP, new SessionGate("b", true, 0).update(false, true, 100));
    }
    @Test public void staleCommandsDoNotAffectAnotherSession() {
        SessionGate gate = new SessionGate("new", false, 0);
        assertTrue(gate.owns("new"));
        assertFalse(gate.owns("old"));
        assertFalse(gate.owns(null));
    }
}
