package com.greatwall.callring;

final class CallStateSnapshot {
    static boolean active(int state, int audioMode) {
        return state == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
                || audioMode == android.media.AudioManager.MODE_IN_CALL
                || audioMode == android.media.AudioManager.MODE_IN_COMMUNICATION;
    }
}
