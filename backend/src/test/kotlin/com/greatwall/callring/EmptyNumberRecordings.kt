package com.greatwall.callring

import com.greatwall.callring.application.callers.NumberDeletionRecordings
import com.greatwall.callring.application.NumberRecordingMetadata
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.contracts.common.ApiResult

object EmptyNumberRecordings : NumberDeletionRecordings {
    override suspend fun archives(number: String) = ApiResult.Success(emptyList<NumberRecordingMetadata>())
    override suspend fun delete(callId: Long, expectedGeneration: Long, command: CommandContext): ApiResult<Unit> = error("no archive seeded")
}
