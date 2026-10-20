package com.greatwall.callring.testing

import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.common.*

import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.widget.*

import com.greatwall.callring.contracts.runtime.*
import kotlinx.coroutines.flow.MutableStateFlow

object FixtureAiSettingsApi : AiSettingsApi {
    override suspend fun patchCombined(recordingBase: RecordingSettingsView, recording: RecordingSettingsPatch, aiBase: AiSettingsView, ai: AiSettingsPatch, analysisSecret: LocalSecretChange, transcriptionSecret: LocalSecretChange): ApiResult<Unit> = throw AssertionError("Unexpected fixture operation: patchCombined")
    override suspend fun testDraft(base: AiSettingsView, patch: AiSettingsPatch, slot: AiSlot): ApiResult<Unit> = throw AssertionError("Unexpected fixture operation: testDraft")

    override suspend fun getRecordingView() = fixtureFailure()
    override suspend fun patchRecording(base: RecordingSettingsView, patch: RecordingSettingsPatch) = fixtureFailure()
    override suspend fun getAiView() = fixtureFailure()
    override suspend fun patchAi(base: AiSettingsView, patch: AiSettingsPatch, analysisSecret: LocalSecretChange, transcriptionSecret: LocalSecretChange) = fixtureFailure()
    override suspend fun copyConnection(from: AiSlot, to: AiSlot, expectedRevision: Long) = fixtureFailure()
    override suspend fun testSlot(slot: AiSlot) = fixtureFailure()
}

object FixtureAiSnapshotApi : AiSnapshotApi { override suspend fun captureComplete() = fixtureFailure() }

object FixtureAppUpdateApi : AppUpdateApi {
    private fun unavailable() = ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "update.unavailable"))
    override suspend fun status() = unavailable()
    override suspend fun check() = unavailable()
    override suspend fun download() = unavailable()
    override suspend fun cancel() = unavailable()
    override suspend fun install() = unavailable()
}

object FixtureForegroundActionApi : ForegroundActionApi {
    override val pending = MutableStateFlow<ForegroundTicket?>(null)
    override fun attachHost() = ""
    override fun detachHost(token: String) = Unit
    override fun beginLaunch(requestId: String, token: String) = false
    override fun deliver(requestId: String, result: ForegroundHostResult) = false
}

object FixtureConversationApi : ConversationApi {
    override suspend fun sendTo(expectedSessionId: String, text: String, disclosedTarget: AiConnectionView): ApiResult<Unit> = throw AssertionError("Unexpected fixture operation: sendTo")
    override suspend fun continueTask(expectedSessionId: String, taskId: String, disclosedTarget: AiConnectionView): ApiResult<Unit> = throw AssertionError("Unexpected fixture operation: continueTask")

    override val state = MutableStateFlow(ConversationState("unavailable"))
    override val foreground = FixtureForegroundActionApi
    override suspend fun send(expectedSessionId: String, text: String) = fixtureFailure()
    override suspend fun stop(expectedSessionId: String) = fixtureFailure()
    override suspend fun newSession(expectedSessionId: String) = fixtureFailure()
}

object FixtureRecordingApi : RecordingApi {
    override suspend fun getIdentity(callId: Long): ApiResult<RecordingIdentity> = throw AssertionError("Unexpected fixture operation: getIdentity")
    override suspend fun listArchives(): ApiResult<List<CallArchive>> = throw AssertionError("Unexpected fixture operation: listArchives")

    override suspend fun inspectSystemRecording(document: DocumentRef): ApiResult<RecordingFile> = throw AssertionError("Unexpected fixture operation: inspectSystemRecording")

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

fun fixtureFailure(): ApiResult.Failure = ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "fixture.unavailable"))
