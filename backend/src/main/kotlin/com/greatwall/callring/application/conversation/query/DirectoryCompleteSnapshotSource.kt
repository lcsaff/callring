package com.greatwall.callring.application.conversation.query

import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.application.conversation.foreground.ForegroundResourceRegistry
import com.greatwall.callring.contracts.ai.SnapshotDomainStatus
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.AccessState
import com.greatwall.callring.contracts.recording.RecordingFile
import kotlinx.coroutines.*

class DirectoryCompleteSnapshotSource(
    private val configured: () -> Boolean,
    private val access: () -> AccessState,
    private val files: suspend () -> ApiResult<List<RecordingFile>>,
    private val resources: ForegroundResourceRegistry,
) {
    suspend fun capture(at: Long): Map<String, SnapshotPart> =
        withSnapshotSource("D17") {
            val configured =
                withContext(Dispatchers.IO) {
                    configured()
                }
            if (configured && access() != AccessState.GRANTED)
                throw BackendFailure(
                    ApiError(
                        ErrorCode.PERMISSION_REQUIRED,
                        "recording.permission_required",
                    ),
                )
            val files = files().requiredValue()
            mapOf(
                "D17" to
                    resources.snapshotPart(
                        files,
                        if (configured) SnapshotDomainStatus.COMPLETE
                        else SnapshotDomainStatus.NOT_CONFIGURED,
                    ),
            )
        }
}
