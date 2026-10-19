package com.greatwall.callring.platform.callers

import com.greatwall.callring.application.api.*
import java.io.File

/**
 * 组合删除：录音归档（含音频）→ 档案与通话记录（单事务）→ 小组件绑定。
 * 顺序固定且可重试；任一前置失败时不进入下一步，避免出现"档案已删、录音还在"。
 */
internal class NumberDeletionService(
    private val core: CallersApi,
    private val recordings: RecordingApi,
    private val widgets: WidgetBindingsApi,
) : NumberDeletionApi {

    override suspend fun impact(number: String): ApiResult<NumberDeletionImpact> {
        val coreImpact = when (val result = core.numberDataImpact(number)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value
        }
        val archives = when (val result = recordings.listArchives()) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value.filter { sameNumber(it.number, coreImpact.number) }
        }
        val bindings = when (val result = widgets.listBindings()) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value.stored.filter { sameNumber(it.number, coreImpact.number) }
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
                audioBytes = archives.sumOf { audioSize(it.audioPath) },
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

        val archives = when (val result = recordings.listArchives()) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value.filter { sameNumber(it.number, current.number) }
        }
        for (archive in archives) {
            when (val result = recordings.delete(archive.callId)) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> Unit
            }
        }

        when (val result = core.deleteNumberCore(current.number, command.expectedCallCount)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> Unit
        }

        val bindings = when (val result = widgets.listBindings()) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value.stored.filter { sameNumber(it.number, current.number) }
        }
        for (binding in bindings) {
            when (val result = widgets.removeBinding(binding.widgetId)) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> Unit
            }
        }
        return ApiResult.Success(Unit)
    }

    private fun sameNumber(raw: String, normalized: String): Boolean {
        if (raw == normalized) return true
        val a = raw.filter(Char::isDigit)
        val b = normalized.filter(Char::isDigit)
        return a.isNotEmpty() && a == b
    }

    private fun audioSize(path: String?): Long =
        path?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L
}
