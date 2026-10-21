package com.greatwall.callring.application.conversation.query

import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.widget.WidgetBindingsListing
import kotlinx.coroutines.*

class ObservedCompleteSnapshotSource(
    private val widgets: suspend () -> ApiResult<WidgetBindingsListing>,
    private val runtime: suspend () -> ApiResult<RuntimeStatus>,
    private val playback: () -> PlaybackStatus,
    private val diagnostics: () -> List<DiagnosticEntry>,
) {
    suspend fun capture(at: Long): Map<String, SnapshotPart> =
        withSnapshotSource("D18-D20") {
            mapOf(
                "D18" to
                    SnapshotPart(
                        safeValue(widgets().requiredValue()),
                        scope = "live_probe",
                    ),
                "D19" to
                    SnapshotPart(
                        safeValue(
                            mapOf(
                                "runtime" to runtime().requiredValue(),
                                "recordingPlayback" to playback(),
                            ),
                        ),
                        scope = "live_probe",
                    ),
                "D20" to
                    SnapshotPart(
                        withContext(Dispatchers.IO) {
                            safeValue(diagnostics())
                        },
                    ),
            )
        }
}
