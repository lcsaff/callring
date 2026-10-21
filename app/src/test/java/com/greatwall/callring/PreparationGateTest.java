package com.greatwall.callring;

import com.greatwall.callring.platform.audio.PreparationGate;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreparationGateTest {
    @Test public void cancelledReadyCannotSilenceACall() {
        PreparationGate gate = new PreparationGate("a", 100);
        gate.ready(); gate.cancel();
        assertFalse(gate.commit("a", 200));
    }
    @Test public void deadlineDoesNotDependOnHandlerQueueOrder() {
        PreparationGate gate = new PreparationGate("a", 100);
        gate.ready();
        assertFalse(gate.commit("a", 1900));
    }
    @Test public void onlyReadyCurrentTokenCanCommitOnce() {
        PreparationGate gate = new PreparationGate("a", 100);
        assertFalse(gate.commit("a", 200));
        gate.ready();
        assertFalse(gate.commit("old", 200));
        assertTrue(gate.commit("a", 200));
        assertFalse(gate.commit("a", 201));
    }
}
