package com.greatwall.callring

import com.greatwall.callring.application.callers.NumberDeletionRecordingReads
import com.greatwall.callring.application.ports.recording.NumberRecordingMetadata
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.ApiResult

object EmptyNumberRecordings : NumberDeletionRecordingReads {
    override suspend fun archives(number: String) = ApiResult.Success(emptyList<NumberRecordingMetadata>())
}
