package com.greatwall.callring.ui.feature.setup

import com.greatwall.callring.contracts.CallringUiApi
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.RecordingSettingsView
import com.greatwall.callring.contracts.runtime.RuntimeStatus
import com.greatwall.callring.ui.state.SnapshotViewModel
import kotlinx.coroutines.CoroutineScope

/** These independent platform/configuration samples are observations, not an atomic snapshot. */
data class SetupUiData(
    val status: RuntimeStatus,
    val overlayEnabled: Boolean?,
    val recording: RecordingSettingsView?,
    val overlayError: ApiError? = null,
    val recordingError: ApiError? = null,
)

class SetupViewModel(api: CallringUiApi, testScope: CoroutineScope? = null) : SnapshotViewModel<SetupUiData>(
    api.changes, setOf(DataDomain.RUNTIME, DataDomain.OVERLAY_SETTINGS, DataDomain.RECORDING_SETTINGS),
    load = {
        when (val status = api.runtime.getStatus()) {
            is ApiResult.Failure -> status
            is ApiResult.Success -> {
                val summary = api.settings.getSummary()
                val recording = api.aiSettings.getRecordingView()
                ApiResult.Success(SetupUiData(status.value,
                    (summary as? ApiResult.Success)?.value?.overlayEnabled,
                    (recording as? ApiResult.Success)?.value,
                    (summary as? ApiResult.Failure)?.error,
                    (recording as? ApiResult.Failure)?.error))
            }
        }
    }, testScope = testScope,
    entities = mapOf(DataDomain.RUNTIME to setOf("status", "recordingFolder")),
)
