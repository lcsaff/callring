package com.greatwall.callring.application.conversation.query

import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.contracts.ai.AiSettingsView
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.recording.RecordingSettingsView
import kotlinx.coroutines.*

class RecordingSettingsCompleteSnapshotSource(
    private val recording: suspend () -> ApiResult<RecordingSettingsView>,
    private val ai: suspend () -> ApiResult<AiSettingsView>,
) {
    suspend fun capture(at: Long): Map<String, SnapshotPart> =
        withSnapshotSource("D13,D14") {
            mapOf(
                "D13" to
                    SnapshotPart(
                        safeValue(recording().requiredValue()),
                        scope = "live_probe",
                    ),
                "D14" to
                    SnapshotPart(
                        safeValue(ai().requiredValue()),
                    ),
            )
        }
}
