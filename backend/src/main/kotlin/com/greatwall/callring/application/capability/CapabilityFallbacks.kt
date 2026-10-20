package com.greatwall.callring.application.capability

import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.command.*
import kotlinx.coroutines.flow.MutableStateFlow

object UnavailableAiSettingsApi : AiSettingsApi {
    override suspend fun getRecordingView() = aiUnavailable()
    override suspend fun patchRecording(base: RecordingSettingsView, patch: RecordingSettingsPatch) = aiUnavailable()
    override suspend fun getAiView() = aiUnavailable()
    override suspend fun patchAi(base: AiSettingsView, patch: AiSettingsPatch, analysisSecret: LocalSecretChange, transcriptionSecret: LocalSecretChange) = aiUnavailable()
    override suspend fun copyConnection(from: AiSlot, to: AiSlot, expectedRevision: Long) = aiUnavailable()
    override suspend fun testSlot(slot: AiSlot) = aiUnavailable()
}

object UnavailableAiSnapshotApi : AiSnapshotApi { override suspend fun captureComplete() = aiUnavailable() }

object UnavailableAppUpdateApi : AppUpdateApi {
    private fun unavailable() = ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "update.unavailable"))
    override suspend fun status() = unavailable()
    override suspend fun check() = unavailable()
    override suspend fun download() = unavailable()
    override suspend fun cancel() = unavailable()
    override suspend fun install() = unavailable()
}

object UnavailableForegroundActionApi : ForegroundActionApi {
    override val pending = MutableStateFlow<ForegroundTicket?>(null)
    override fun attachHost() = ""
    override fun detachHost(token: String) = Unit
    override fun beginLaunch(requestId: String, token: String) = false
    override fun deliver(requestId: String, result: ForegroundHostResult) = false
}

object UnavailableConversationApi : ConversationApi {
    override val state = MutableStateFlow(ConversationState("unavailable"))
    override val foreground = UnavailableForegroundActionApi
    override suspend fun send(expectedSessionId: String, text: String) = aiUnavailable()
    override suspend fun stop(expectedSessionId: String) = aiUnavailable()
    override suspend fun newSession(expectedSessionId: String) = aiUnavailable()
}

object UnavailableNumberDeletionApi : NumberDeletionApi {
    private fun unavailable() = ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "number.delete_unavailable"))
    override suspend fun impact(number: String) = unavailable()
    override suspend fun delete(command: DeleteNumberCommand) = unavailable()
}

/** 核心数据（档案 + 通话记录）的删除影响，由 backend 计算。 */

object UnavailableRecordingApi : RecordingApi {
    private fun unavailable() = ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "recording.unavailable"))
    override suspend fun getArchive(callId: Long) = unavailable()
    override suspend fun importRecording(callId: Long, number: String, startedAt: Long, document: DocumentRef, source: RecordingSource) = unavailable()
    override suspend fun retry(callId: Long) = unavailable()
    override suspend fun delete(callId: Long) = unavailable()
    override suspend fun play(callId: Long) = unavailable()
    override suspend fun stop() = unavailable()
    override suspend fun setSystemFolder(document: DocumentRef) = unavailable()
    override suspend fun systemRecordings() = unavailable()
}

object UnavailableUiEditsApi : UiEditsApi
