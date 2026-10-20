package com.greatwall.callring.application

import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.command.*

/**
 * 非 Android 组合下的核心删除（档案 + 通话 + 小组件绑定）。
 * 录音归档只存在于 app 层，由 `NumberDeletionService` 覆盖；这里要求 archiveCount 为 0。
 */
internal class CoreNumberDeletionApi(
    private val callers: CallersApi,
    private val widgets: WidgetBindingsApi,
) : NumberDeletionApi {

    override suspend fun impact(number: String): ApiResult<NumberDeletionImpact> {
        val core = when (val result = callers.numberDataImpact(number)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value
        }
        val bindings = when (val result = widgets.listBindings()) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value.stored.filter { sameNumber(it.number, core.number) }
        }
        return ApiResult.Success(
            NumberDeletionImpact(
                number = core.number,
                profileExists = core.profileExists,
                callCount = core.callCount,
                archiveCount = 0,
                audioBytes = 0,
                widgetBindingCount = bindings.size,
                busy = false,
            ),
        )
    }

    override suspend fun delete(command: DeleteNumberCommand): ApiResult<Unit> {
        if (command.expectedArchiveCount != 0) {
            return ApiResult.Failure(ApiError(ErrorCode.VALIDATION, "number.delete_unsupported"))
        }
        val current = when (val result = impact(command.number)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value
        }
        if (!current.profileExists && current.callCount == 0 && current.widgetBindingCount == 0) {
            return ApiResult.Failure(ApiError(ErrorCode.NOT_FOUND, "number.not_found"))
        }
        if (current.callCount != command.expectedCallCount || current.widgetBindingCount != command.expectedWidgetBindingCount) {
            return ApiResult.Failure(ApiError(ErrorCode.CONFLICT, "number.delete_conflict", retryable = true))
        }
        if (current.profileExists || current.callCount > 0) {
            when (val result = callers.deleteNumberCore(current.number, command.expectedCallCount)) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> Unit
            }
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
}
