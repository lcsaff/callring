package com.greatwall.callring.platform.callers

import com.greatwall.callring.application.sameRecordingNumber
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.application.NumberRecordingMetadata

/**
 * 组合删除：录音归档（含音频）→ 档案与通话记录（单事务）→ 小组件绑定。
 * 顺序固定且可重试；任一前置失败时不进入下一步，避免出现"档案已删、录音还在"。
 */
internal class NumberDeletionService(
    private val core: CallersApi,
    private val recordings: RecordingApi,
    private val widgets: WidgetBindingsApi,
    private val archivesForNumber: suspend (String) -> ApiResult<List<NumberRecordingMetadata>>,
) : NumberDeletionApi {

    override suspend fun impact(number: String): ApiResult<NumberDeletionImpact> {
        val coreImpact = when (val result = core.numberDataImpact(number)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value
        }
        val archives = when (val result = archivesForNumber(coreImpact.number)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value
        }
        val bindings = when (val result = widgets.listBindings()) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value.stored.filter { sameRecordingNumber(it.number, coreImpact.number) }
        }
        val busy = archives.any {
            it.status in setOf(ArchiveStatus.QUEUED, ArchiveStatus.TRANSCRIBING, ArchiveStatus.ANALYZING)
        }
        return ApiResult.Success(
            NumberDeletionImpact(
                number = coreImpact.number,
                profileExists = coreImpact.profileExists,
                callCount = coreImpact.callCount,
                archiveCount = archives.size,
                audioBytes = archives.sumOf { it.audioBytes },
                widgetBindingCount = bindings.size,
                busy = busy,
            ),
        )
    }

    override suspend fun delete(command: DeleteNumberCommand): ApiResult<Unit> {
        val current = when (val result = impact(command.number)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value
        }
        if (!current.profileExists && current.callCount == 0 && current.archiveCount == 0 && current.widgetBindingCount == 0) {
            return ApiResult.Failure(ApiError(ErrorCode.NOT_FOUND, "number.not_found"))
        }
        if (current.callCount != command.expectedCallCount ||
            current.archiveCount != command.expectedArchiveCount ||
            current.widgetBindingCount != command.expectedWidgetBindingCount
        ) {
            return ApiResult.Failure(ApiError(ErrorCode.CONFLICT, "number.delete_conflict", retryable = true))
        }
        if (current.busy) return ApiResult.Failure(ApiError(ErrorCode.CONFLICT, "number.delete_busy", retryable = true))

        val archives = when (val result = archivesForNumber(current.number)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value
        }
        for (archive in archives) {
            when (val result = recordings.delete(archive.callId)) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> Unit
            }
        }

        if (current.profileExists || current.callCount > 0) {
            when (val result = core.deleteNumberCore(current.number, command.expectedCallCount)) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> Unit
            }
        }

        val bindings = when (val result = widgets.listBindings()) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value.stored.filter { sameRecordingNumber(it.number, current.number) }
        }
        for (binding in bindings) {
            when (val result = widgets.removeBinding(binding.widgetId)) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> Unit
            }
        }
        return ApiResult.Success(Unit)
    }

}
