package com.greatwall.callring.platform

import android.app.NotificationManager
import android.media.AudioManager
import android.telephony.TelephonyManager
import com.greatwall.callring.contracts.runtime.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeSnapshotsTest {
    @Test
    fun audibilityUsesDndThenRingerThenVolumePrecedence() {
        assertEquals(
            Audibility.SYSTEM_DND,
            audibilitySnapshot(
                NotificationManager.INTERRUPTION_FILTER_NONE,
                AudioManager.RINGER_MODE_SILENT,
                0,
            ),
        )
        assertEquals(
            Audibility.SILENT,
            audibilitySnapshot(
                NotificationManager.INTERRUPTION_FILTER_ALL,
                AudioManager.RINGER_MODE_SILENT,
                0,
            ),
        )
        assertEquals(
            Audibility.VIBRATE,
            audibilitySnapshot(
                NotificationManager.INTERRUPTION_FILTER_ALL,
                AudioManager.RINGER_MODE_VIBRATE,
                0,
            ),
        )
        assertEquals(
            Audibility.ZERO_VOLUME,
            audibilitySnapshot(
                NotificationManager.INTERRUPTION_FILTER_ALL,
                AudioManager.RINGER_MODE_NORMAL,
                0,
            ),
        )
        assertEquals(
            Audibility.AUDIBLE,
            audibilitySnapshot(
                NotificationManager.INTERRUPTION_FILTER_ALL,
                AudioManager.RINGER_MODE_NORMAL,
                1,
            ),
        )
    }

    @Test
    fun activeAudioModeOverridesIdleOrRingingTelephonySnapshot() {
        assertEquals(
            PhoneActivity.ACTIVE,
            phoneActivitySnapshot(TelephonyManager.CALL_STATE_RINGING, AudioManager.MODE_IN_CALL),
        )
        assertEquals(
            PhoneActivity.ACTIVE,
            phoneActivitySnapshot(
                TelephonyManager.CALL_STATE_IDLE,
                AudioManager.MODE_IN_COMMUNICATION,
            ),
        )
        assertEquals(
            PhoneActivity.RINGING,
            phoneActivitySnapshot(TelephonyManager.CALL_STATE_RINGING, AudioManager.MODE_NORMAL),
        )
        assertEquals(
            PhoneActivity.IDLE,
            phoneActivitySnapshot(TelephonyManager.CALL_STATE_IDLE, AudioManager.MODE_NORMAL),
        )
    }

    @Test
    fun legacyDiagnosticRetainsOnlyTimeOfDayAndNeverInventsDate() {
        val diagnostic = legacyDiagnostic("13:14:15  a legacy event")!!
        assertNull(diagnostic.occurredAtEpochMillis)
        assertEquals("13:14:15", diagnostic.legacyTimeText)
        assertEquals("diagnostic.legacy_event", diagnostic.message)
        assertNull(legacyDiagnostic("25:01:01  bad time"))
        assertNull(legacyDiagnostic("random unstructured data"))
    }
}
