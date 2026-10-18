package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.BackendFailure
import kotlinx.coroutines.*

class AiSettingsService(
    private val store: AndroidRecordingStore,
    private val coordinator: BusinessRevisionCoordinator,
    private val microphoneGranted: () -> Boolean,
    private val testConnection: (AiConnection, Boolean) -> Unit,
    private val command: CommandContext? = null,
    private val afterSave: (RecordingSettings) -> Unit = {},
) : AiSettingsApi {
    fun bound(command: CommandContext) = AiSettingsService(store, coordinator, microphoneGranted, testConnection, command, afterSave)
    private fun conflict(): Nothing = throw BackendFailure(ApiError(ErrorCode.CONFLICT, "settings.conflict", retryable = true))
    private fun revision(key: String) = coordinator.capture().values.getValue(key)
    private fun AiConnection.view() = AiConnectionView(protocol, url, model, apiKey.isNotEmpty())
    private fun RecordingSettings.aiView() = AiSettingsView(analysis.view(), transcription.view(), conversationBudget, revision("aiConfig"))
    private fun RecordingSettings.recordingView() = RecordingSettingsView(recordingEnabled, automaticAnalysis, captureMode, systemFolder != null, revision("recordingConfig"))
    private suspend fun <T> result(block: () -> T): ApiResult<T> = withContext(Dispatchers.IO) {
        try { ApiResult.Success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: BackendFailure) { ApiResult.Failure(e.error) }
        catch (_: IllegalArgumentException) { ApiResult.Failure(ApiError(ErrorCode.VALIDATION, "ai.invalid_settings")) }
        catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "ai.settings_storage_failed")) }
    }
    override suspend fun getRecordingView() = result { coordinator.guard { store.settings().recordingView() } }
    override suspend fun getAiView() = result { coordinator.guard { store.settings().aiView() } }
    private fun <T> field(base: T, current: T, patch: PatchField<T>): T = when(patch) {
        PatchField.Keep -> current
        is PatchField.Set -> { if (base != current) conflict(); patch.value }
    }
    override suspend fun patchRecording(base: RecordingSettingsView, patch: RecordingSettingsPatch) = result {
        val saved = store.updateSettings(command) { old ->
            val mode = field(base.captureMode, old.captureMode, patch.captureMode)
            if (mode == CaptureMode.MICROPHONE_EXPERIMENTAL && !microphoneGranted()) throw BackendFailure(ApiError(ErrorCode.PERMISSION_REQUIRED, "capability.required", requiredCapabilities = setOf(Capability.MICROPHONE)))
            old.copy(recordingEnabled = field(base.recordingEnabled, old.recordingEnabled, patch.recordingEnabled), automaticAnalysis = field(base.automaticAnalysis, old.automaticAnalysis, patch.automaticAnalysis), captureMode = mode)
        }
        afterSave(saved)
        saved.recordingView()
    }
    private fun connection(base: AiConnectionView, current: AiConnection, patch: ConnectionPatch, secret: LocalSecretChange, baseRevision: Long): AiConnection {
        val key = when (secret) {
            LocalSecretChange.Keep -> current.apiKey
            else -> { if (revision("aiConfig") != baseRevision) conflict(); if (secret is LocalSecretChange.Replace) secret.value else "" }
        }
        return current.copy(protocol = field(base.protocol, current.protocol, patch.protocol), url = field(base.url, current.url, patch.url), model = field(base.model, current.model, patch.model), apiKey = key)
    }
    override suspend fun patchAi(base: AiSettingsView, patch: AiSettingsPatch, analysisSecret: LocalSecretChange, transcriptionSecret: LocalSecretChange) = result {
        store.updateSettings(command) { old -> old.copy(
            analysis = connection(base.analysis, old.analysis, patch.analysis, analysisSecret, base.revision),
            transcription = connection(base.transcription, old.transcription, patch.transcription, transcriptionSecret, base.revision),
            conversationBudget = field(base.budget, old.conversationBudget, patch.budget),
        ) }.aiView()
    }
    override suspend fun copyConnection(from: AiSlot, to: AiSlot, expectedRevision: Long) = result {
        require(from != to)
        store.updateSettings(command) { old ->
            if (revision("aiConfig") != expectedRevision) conflict()
            val value = if (from == AiSlot.ANALYSIS) old.analysis else old.transcription
            if (to == AiSlot.ANALYSIS) old.copy(analysis = value) else old.copy(transcription = value.copy(protocol = AiProtocol.OPENAI))
        }.aiView()
    }
    override suspend fun testSlot(slot: AiSlot) = result { val current = coordinator.guard(command) { store.settings() }; testConnection(if (slot == AiSlot.ANALYSIS) current.analysis else current.transcription, slot == AiSlot.TRANSCRIPTION) }
    override suspend fun patchCombined(recordingBase: RecordingSettingsView, recording: RecordingSettingsPatch, aiBase: AiSettingsView, ai: AiSettingsPatch): ApiResult<Unit> = result {
        val saved = store.updateSettings(command) { old ->
            val mode = field(recordingBase.captureMode, old.captureMode, recording.captureMode)
            if(mode == CaptureMode.MICROPHONE_EXPERIMENTAL && !microphoneGranted()) throw BackendFailure(ApiError(ErrorCode.PERMISSION_REQUIRED, "capability.required", requiredCapabilities = setOf(Capability.MICROPHONE)))
            old.copy(recordingEnabled = field(recordingBase.recordingEnabled, old.recordingEnabled, recording.recordingEnabled), automaticAnalysis = field(recordingBase.automaticAnalysis, old.automaticAnalysis, recording.automaticAnalysis), captureMode = mode,
                analysis = connection(aiBase.analysis, old.analysis, ai.analysis, LocalSecretChange.Keep, aiBase.revision),
                transcription = connection(aiBase.transcription, old.transcription, ai.transcription, LocalSecretChange.Keep, aiBase.revision),
                conversationBudget = field(aiBase.budget, old.conversationBudget, ai.budget))
        }
        runCatching { afterSave(saved) }
        Unit
    }
}
