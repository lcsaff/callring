package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.BackendFailure

/** Used by both the archive form and the conversation tool; inferred identity is never adopted. */
class SharedBusinessCommands(private val api: CallringUiApi) {
    suspend fun adoptIdentity(callId: Long, expectedGeneration: Long): ApiResult<CallerProfile> {
        val archive = when(val result = api.recordings.getArchive(callId)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value ?: return ApiResult.Failure(ApiError(ErrorCode.NOT_FOUND, "recording.not_found"))
        }
        if (archive.generation != expectedGeneration) return ApiResult.Failure(ApiError(ErrorCode.CONFLICT, "recording.changed"))
        val analysis = archive.analysis ?: return ApiResult.Failure(ApiError(ErrorCode.NOT_FOUND, "recording.analysis_missing"))
        val editor = when(val result = api.callers.getEditor(archive.number)) { is ApiResult.Failure -> return result; is ApiResult.Success -> result.value }
        return api.edits.profile(editor.draft,editor.draft.copy(name=analysis.name.ifBlank{editor.draft.name},department=analysis.organization.ifBlank{editor.draft.department}))
    }
}
fun <T> ApiResult<T>.requiredValue(): T = when(this) { is ApiResult.Success -> value; is ApiResult.Failure -> throw BackendFailure(error) }
