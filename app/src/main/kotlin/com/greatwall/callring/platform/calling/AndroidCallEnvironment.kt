package com.greatwall.callring.platform.calling

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.telephony.TelephonyManager

/** Platform safety facts used immediately before reserving audio. */
object AndroidCallEnvironment {
    @JvmStatic
    fun phonePermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @JvmStatic
    fun audible(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        return audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            audio.getStreamVolume(AudioManager.STREAM_RING) > 0 &&
            context.getSystemService(NotificationManager::class.java).currentInterruptionFilter ==
                NotificationManager.INTERRUPTION_FILTER_ALL
    }

    @JvmStatic
    fun active(context: Context): Boolean {
        val state = context.getSystemService(TelephonyManager::class.java).callState
        val mode = context.getSystemService(AudioManager::class.java).mode
        return state == TelephonyManager.CALL_STATE_OFFHOOK ||
            mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
    }
}
