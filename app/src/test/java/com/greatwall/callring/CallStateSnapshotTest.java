package com.greatwall.callring;

import com.greatwall.callring.platform.calling.CallStateSnapshot;

import android.media.AudioManager;
import android.telephony.TelephonyManager;
import org.junit.Test;
import static org.junit.Assert.*;

public class CallStateSnapshotTest {
    @Test public void callWaitingMustNotHideAnActiveAudioCall() {
        assertTrue(CallStateSnapshot.active(TelephonyManager.CALL_STATE_RINGING, AudioManager.MODE_IN_CALL));
        assertTrue(CallStateSnapshot.active(TelephonyManager.CALL_STATE_RINGING, AudioManager.MODE_IN_COMMUNICATION));
        assertFalse(CallStateSnapshot.active(TelephonyManager.CALL_STATE_RINGING, AudioManager.MODE_RINGTONE));
        assertTrue(CallStateSnapshot.active(TelephonyManager.CALL_STATE_OFFHOOK, AudioManager.MODE_NORMAL));
    }
}
