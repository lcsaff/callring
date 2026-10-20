package com.greatwall.callring.contracts.runtime

import com.greatwall.callring.contracts.common.*

interface RuntimeApi {
    suspend fun getStatus(): ApiResult<RuntimeStatus>
    suspend fun getDiagnostics(): ApiResult<List<DiagnosticEntry>>
    suspend fun requestCallLogSync(): ApiResult<SyncRequestReceipt>
}

enum class Capability {
    PHONE_STATE, NOTIFICATIONS, CALL_LOG, CONTACTS, MICROPHONE,
    OVERLAY, CALL_SCREENING_ROLE, BATTERY_OPTIMIZATION_EXEMPTION
}

enum class AccessState { GRANTED, DENIED, NOT_REQUIRED, UNAVAILABLE, UNKNOWN }

enum class Audibility { AUDIBLE, SILENT, VIBRATE, SYSTEM_DND, ZERO_VOLUME, UNKNOWN }

enum class PhoneActivity { IDLE, RINGING, ACTIVE, UNKNOWN }

enum class PlaybackMode { NONE, PREVIEW, RINGTONE }

enum class PlaybackState { IDLE, PREPARING, PLAYING, UNKNOWN }

data class PlaybackStatus(
    val mode: PlaybackMode,
    val state: PlaybackState
)

data class RuntimeStatus(
    val capabilities: Map<Capability, AccessState>,
    val audibility: Audibility,
    val phoneActivity: PhoneActivity,
    val playback: PlaybackStatus,
    val pinWidgetSupported: Boolean,
    val checkedAtEpochMillis: Long
)

data class DiagnosticEntry(
    val occurredAtEpochMillis: Long?,
    val legacyTimeText: String?,
    val message: String
)

enum class SyncRequestState { QUEUED, ALREADY_QUEUED }

data class SyncRequestReceipt(
    val state: SyncRequestState,
    val requestedAtEpochMillis: Long
)
