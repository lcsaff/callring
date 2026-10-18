package com.greatwall.callring.platform

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.RuntimeProbe
import java.time.Clock

class AndroidRuntimeProbe(
    private val context: Context,
    private val playback: PlaybackStateTracker,
    private val diagnostics: AndroidDiagnosticsStore,
    private val clock: Clock,
    private val installedWidgets: () -> WidgetInstallState = { WidgetInstallState(emptyList(), AccessState.UNKNOWN) },
) : RuntimeProbe {
    override fun getInstalledWidgets(): WidgetInstallState = installedWidgets()
    private fun permission(name: String): AccessState =
        if (context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED)
            AccessState.GRANTED
        else AccessState.DENIED

    private fun probe(block: () -> AccessState): AccessState =
        try {
            block()
        } catch (_: RuntimeException) {
            AccessState.UNAVAILABLE
        }

    override fun getStatus(): RuntimeStatus {
        val capabilities =
            linkedMapOf(
                Capability.PHONE_STATE to
                    probe { permission(Manifest.permission.READ_PHONE_STATE) },
                Capability.MICROPHONE to probe { permission(Manifest.permission.RECORD_AUDIO) },
                Capability.NOTIFICATIONS to
                    probe {
                        if (Build.VERSION.SDK_INT < 33) AccessState.NOT_REQUIRED
                        else if (
                            permission(Manifest.permission.POST_NOTIFICATIONS) !=
                                AccessState.GRANTED
                        )
                            AccessState.DENIED
                        else if (
                            context
                                .getSystemService(NotificationManager::class.java)
                                ?.areNotificationsEnabled() == true
                        )
                            AccessState.GRANTED
                        else AccessState.DENIED
                    },
                Capability.CALL_LOG to probe { permission(Manifest.permission.READ_CALL_LOG) },
                Capability.CONTACTS to probe { permission(Manifest.permission.READ_CONTACTS) },
                Capability.OVERLAY to
                    probe {
                        if (Settings.canDrawOverlays(context)) AccessState.GRANTED
                        else AccessState.DENIED
                    },
                Capability.CALL_SCREENING_ROLE to
                    probe {
                        val manager = context.getSystemService(RoleManager::class.java)
                        when {
                            manager == null ||
                                !manager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) ->
                                AccessState.UNAVAILABLE
                            manager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) ->
                                AccessState.GRANTED
                            else -> AccessState.DENIED
                        }
                    },
                Capability.BATTERY_OPTIMIZATION_EXEMPTION to
                    probe {
                        val manager = context.getSystemService(PowerManager::class.java)
                        if (manager == null) AccessState.UNAVAILABLE
                        else if (manager.isIgnoringBatteryOptimizations(context.packageName))
                            AccessState.GRANTED
                        else AccessState.DENIED
                    },
            )
        val audibility =
            try {
                val notifications = context.getSystemService(NotificationManager::class.java)
                val audio = context.getSystemService(AudioManager::class.java)
                if (notifications == null || audio == null) Audibility.UNKNOWN
                else
                    audibilitySnapshot(
                        notifications.currentInterruptionFilter,
                        audio.ringerMode,
                        audio.getStreamVolume(AudioManager.STREAM_RING),
                    )
            } catch (_: RuntimeException) {
                Audibility.UNKNOWN
            }
        val phone =
            try {
                if (capabilities[Capability.PHONE_STATE] != AccessState.GRANTED)
                    PhoneActivity.UNKNOWN
                else {
                    val telephony = context.getSystemService(TelephonyManager::class.java)
                    val audio = context.getSystemService(AudioManager::class.java)
                    if (telephony == null || audio == null) PhoneActivity.UNKNOWN
                    else phoneActivitySnapshot(telephony.callState, audio.mode)
                }
            } catch (_: RuntimeException) {
                PhoneActivity.UNKNOWN
            }
        val pin =
            try {
                AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
            } catch (_: RuntimeException) {
                false
            }
        return RuntimeStatus(
            capabilities,
            audibility,
            phone,
            playback.snapshot(),
            pin,
            clock.millis(),
        )
    }

    override fun getDiagnostics(): List<DiagnosticEntry> = diagnostics.read()
}

internal fun audibilitySnapshot(interruptionFilter: Int, ringerMode: Int, volume: Int): Audibility =
    when {
        interruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            interruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN ->
            Audibility.SYSTEM_DND
        interruptionFilter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> Audibility.UNKNOWN
        ringerMode == AudioManager.RINGER_MODE_SILENT -> Audibility.SILENT
        ringerMode == AudioManager.RINGER_MODE_VIBRATE -> Audibility.VIBRATE
        ringerMode != AudioManager.RINGER_MODE_NORMAL -> Audibility.UNKNOWN
        volume == 0 -> Audibility.ZERO_VOLUME
        volume < 0 -> Audibility.UNKNOWN
        else -> Audibility.AUDIBLE
    }

internal fun phoneActivitySnapshot(callState: Int, audioMode: Int): PhoneActivity =
    when {
        callState == TelephonyManager.CALL_STATE_OFFHOOK ||
            audioMode == AudioManager.MODE_IN_CALL ||
            audioMode == AudioManager.MODE_IN_COMMUNICATION -> PhoneActivity.ACTIVE
        callState == TelephonyManager.CALL_STATE_RINGING -> PhoneActivity.RINGING
        callState == TelephonyManager.CALL_STATE_IDLE -> PhoneActivity.IDLE
        else -> PhoneActivity.UNKNOWN
    }
