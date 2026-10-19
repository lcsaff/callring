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
    private val folderView: (String?) -> RecordingFolderView = { RecordingFolderView(null,if(it == null) AccessState.NOT_REQUIRED else AccessState.UNKNOWN) },
) : AiSettingsApi {
    fun bound(command: CommandContext) = AiSettingsService(store, coordinator, microphoneGranted, testConnection, command, afterSave, folderView)
    private fun conflict(): Nothing = throw BackendFailure(ApiError(ErrorCode.CONFLICT, "settings.conflict", retryable = true))
    private fun revision(key: String) = coordinator.capture().values.getValue(key)
    private fun AiConnection.view(compatibility: ConversationCompatibility = ConversationCompatibility.STANDARD) = AiConnectionView(protocol, url, model, apiKey.isNotEmpty(),resolveConversationProfile(this,compatibility))
    private fun RecordingSettings.aiView() = AiSettingsView(analysis.view(conversationCompatibility), transcription.view(), conversationBudget, revision("aiConfig"), conversationCompatibility)
    private fun RecordingSettings.recordingView(version: Long = revision("recordingConfig")): RecordingSettingsView {
        val folder = folderView(systemFolder)
        return RecordingSettingsView(recordingEnabled, automaticAnalysis, captureMode, systemFolder != null, version,folder.displayName,folder.access)
    }
    private suspend fun <T> result(block: () -> T): ApiResult<T> = withContext(Dispatchers.IO) {
        try { ApiResult.Success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: BackendFailure) { ApiResult.Failure(e.error) }
        catch (error: IllegalArgumentException) { ApiResult.Failure(if(error.message=="ai.profile_invalid")ApiError(ErrorCode.VALIDATION,"ai.profile_invalid",fieldErrors=mapOf("analysis.model" to "ai.fast_model_required"))else ApiError(ErrorCode.VALIDATION, "ai.invalid_settings")) }
        catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "ai.settings_storage_failed")) }
    }
    override suspend fun getRecordingView() = result {
        val (settings,version) = coordinator.guard { store.settings() to revision("recordingConfig") }
        settings.recordingView(version)
    }
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
    private fun connection(base: AiConnectionView, current: AiConnection, patch: ConnectionPatch, secret: LocalSecretChange, baseRevision: Long,slot:AiSlot): AiConnection {
        val key = when (secret) {
            LocalSecretChange.Keep -> current.apiKey
            else -> { val marker=coordinator.domainMarker("aiConfig",baseRevision)?:conflict();if(!coordinator.unchanged(setOf("ai:$slot:key"),marker))conflict(); if (secret is LocalSecretChange.Replace) secret.value else "" }
        }
        return current.copy(protocol = field(base.protocol, current.protocol, patch.protocol), url = field(base.url, current.url, patch.url), model = field(base.model, current.model, patch.model), apiKey = key)
    }
    override suspend fun patchAi(base: AiSettingsView, patch: AiSettingsPatch, analysisSecret: LocalSecretChange, transcriptionSecret: LocalSecretChange) = result {
        store.updateSettings(command) { old -> old.copy(
            analysis = connection(base.analysis, old.analysis, patch.analysis, analysisSecret, base.revision,AiSlot.ANALYSIS),
            transcription = connection(base.transcription, old.transcription, patch.transcription, transcriptionSecret, base.revision,AiSlot.TRANSCRIPTION),
            conversationBudget = field(base.budget, old.conversationBudget, patch.budget),
            conversationCompatibility = field(base.compatibility,old.conversationCompatibility,patch.compatibility),
        ) }.aiView()
    }
    override suspend fun copyConnection(from: AiSlot, to: AiSlot, expectedRevision: Long) = result {
        require(from != to)
        store.updateSettings(command) { old ->
            val marker=coordinator.domainMarker("aiConfig",expectedRevision)?:conflict()
            val keys=listOf(from,to).flatMap{slot->listOf("protocol","url","model","key").map{"ai:$slot:$it"}}.toSet()+if(to==AiSlot.ANALYSIS)setOf("ai:compatibility")else emptySet()
            if(!coordinator.unchanged(keys,marker))conflict()
            val value = if (from == AiSlot.ANALYSIS) old.analysis else old.transcription
            if (to == AiSlot.ANALYSIS) old.copy(analysis = value, conversationCompatibility = ConversationCompatibility.AUTO) else old.copy(transcription = value.copy(protocol = AiProtocol.OPENAI))
        }.aiView()
    }
    override suspend fun testSlot(slot: AiSlot) = result { val current = coordinator.guard(command) { store.settings() }; testConnection(if (slot == AiSlot.ANALYSIS) current.analysis else current.transcription, slot == AiSlot.TRANSCRIPTION) }
    override suspend fun testDraft(base: AiSettingsView, patch: AiSettingsPatch, slot: AiSlot) = result {
        val value = coordinator.guard(command) { val current = store.settings();
            if(slot == AiSlot.ANALYSIS) connection(base.analysis,current.analysis,patch.analysis,LocalSecretChange.Keep,base.revision,AiSlot.ANALYSIS).also{resolveConversationProfile(it,field(base.compatibility,current.conversationCompatibility,patch.compatibility))}
            else connection(base.transcription,current.transcription,patch.transcription,LocalSecretChange.Keep,base.revision,AiSlot.TRANSCRIPTION)
        }
        testConnection(value,slot == AiSlot.TRANSCRIPTION)
    }
    override suspend fun patchCombined(recordingBase: RecordingSettingsView, recording: RecordingSettingsPatch, aiBase: AiSettingsView, ai: AiSettingsPatch, analysisSecret: LocalSecretChange, transcriptionSecret: LocalSecretChange): ApiResult<Unit> = result {
        val saved = store.updateSettings(command) { old ->
            val mode = field(recordingBase.captureMode, old.captureMode, recording.captureMode)
            if(mode == CaptureMode.MICROPHONE_EXPERIMENTAL && !microphoneGranted()) throw BackendFailure(ApiError(ErrorCode.PERMISSION_REQUIRED, "capability.required", requiredCapabilities = setOf(Capability.MICROPHONE)))
            old.copy(recordingEnabled = field(recordingBase.recordingEnabled, old.recordingEnabled, recording.recordingEnabled), automaticAnalysis = field(recordingBase.automaticAnalysis, old.automaticAnalysis, recording.automaticAnalysis), captureMode = mode,
                analysis = connection(aiBase.analysis, old.analysis, ai.analysis, analysisSecret, aiBase.revision,AiSlot.ANALYSIS),
                transcription = connection(aiBase.transcription, old.transcription, ai.transcription, transcriptionSecret, aiBase.revision,AiSlot.TRANSCRIPTION),
                conversationBudget = field(aiBase.budget, old.conversationBudget, ai.budget),
                conversationCompatibility = field(aiBase.compatibility,old.conversationCompatibility,ai.compatibility))
        }
        runCatching { afterSave(saved) }
        Unit
    }
}
