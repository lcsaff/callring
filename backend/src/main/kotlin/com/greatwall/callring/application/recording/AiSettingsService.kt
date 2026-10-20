package com.greatwall.callring.application.recording

import com.greatwall.callring.application.coordination.requireOperationActive

import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.application.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.application.ports.BackendFailure
import kotlinx.coroutines.*
import com.greatwall.callring.application.coordination.ChangeImpact
import com.greatwall.callring.contracts.change.DataDomain

class AiSettingsService(
    private val store: RecordingSettingsStore,
    private val coordinator: BusinessRevisionCoordinator,
    private val microphoneGranted: () -> Boolean,
    private val testConnection: (AiConnection, Boolean) -> Unit,
    private val command: CommandContext? = null,
    private val afterSave: (RecordingSettings) -> Unit = {},
    private val folderView: (String?) -> RecordingFolderView,
    private val operationActive: () -> Boolean,
    private val folderObservations: RecordingFolderObservation = RecordingFolderObservation(coordinator),
) : AiSettingsApi {
    suspend fun reobserveFolder() {
        val caller = currentCoroutineContext()
        folderObservations.reobserve(folderView) { caller.ensureActive(); operationActive() }
    }
    fun bound(command: CommandContext) = AiSettingsService(store, coordinator, microphoneGranted, testConnection, command, afterSave, folderView, operationActive = operationActive, folderObservations = folderObservations)
    private data class CommittedSettings(val settings: RecordingSettings, val recordingRevision: Long, val aiRevision: Long)

    private suspend fun updateSettings(transform: (RecordingSettings) -> RecordingSettings): CommittedSettings {
        val caller = currentCoroutineContext()
        return coordinator.guard(command) {
            requireOperationActive(operationActive)
            caller.ensureActive()
            val old = store.settings()
            val next = transform(old).also { it.validate() }
            val before = recordingSettingsScopeValues(old)
            val after = recordingSettingsScopeValues(next)
            val keys = (before.keys + after.keys).filterTo(linkedSetOf()) { before[it] != after[it] }
            caller.ensureActive()
            if (keys.isNotEmpty()) {
                store.persistSettings(next)
                val resources = buildSet {
                    if (keys.any { it.startsWith("recordingConfig:") }) add("recordingConfig")
                    if (keys.any { it.startsWith("ai:") }) add("aiConfig")
                }
                val domains = resources.mapTo(linkedSetOf()) { if (it == "aiConfig") DataDomain.AI_SETTINGS else DataDomain.RECORDING_SETTINGS }
                coordinator.publishResources(resources, keys, ChangeImpact.wide(domains))
            }
            coordinator.record(command)
            CommittedSettings(next, revision("recordingConfig"), revision("aiConfig"))
        }
    }

    /** Provider permission acquisition is platform preparation; this is its sole settings commit. */
    suspend fun setSystemFolder(uri: String) {
        updateSettings { it.copy(systemFolder = uri) }
    }

    private fun conflict(): Nothing = throw BackendFailure(ApiError(ErrorCode.CONFLICT, "settings.conflict", retryable = true))
    private fun revision(key: String) = coordinator.capture().values.getValue(key)
    private fun AiConnection.view(compatibility: ConversationCompatibility = ConversationCompatibility.STANDARD): AiConnectionView {
        val policy = ThinkingPolicyResolver.resolve(protocol, url, model)
        val notice = when (policy.support) {
            ThinkingSupport.AVAILABLE -> ""
            ThinkingSupport.CUSTOM_GATEWAY -> "thinking.custom_gateway"
            else -> policy.reason
        }
        return AiConnectionView(protocol, url, model, apiKey.isNotEmpty(), resolveConversationProfile(this, compatibility), thinkingMode, policy.support, notice, resolveConversationProfile(this, ConversationCompatibility.AUTO))
    }
    private fun RecordingSettings.aiView(version: Long) = AiSettingsView(analysis.view(conversationCompatibility), transcription.view(), conversationBudget, version, conversationCompatibility)
    private fun RecordingSettings.recordingView(version: Long): RecordingSettingsView {
        val folder = folderObservations.observed(systemFolder, folderView(systemFolder))
        return RecordingSettingsView(recordingEnabled, automaticAnalysis, captureMode, systemFolder != null, version,folder.displayName,folder.access)
    }
    private suspend fun <T> result(block: suspend () -> T): ApiResult<T> = withContext(Dispatchers.IO) {
        try { requireOperationActive(operationActive); ApiResult.Success(block()) }
        catch (e: CancellationException) { throw e }
        catch (e: BackendFailure) { ApiResult.Failure(e.error) }
        catch (error: IllegalArgumentException) { ApiResult.Failure(ApiError(ErrorCode.VALIDATION, "ai.invalid_settings")) }
        catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "ai.settings_storage_failed")) }
    }
    override suspend fun getRecordingView() = result {
        val (settings,version) = coordinator.guard { store.settings() to revision("recordingConfig") }
        settings.recordingView(version)
    }
    override suspend fun getAiView() = result { coordinator.guard { store.settings().aiView(revision("aiConfig")) } }
    private fun <T> field(base: T, current: T, patch: PatchField<T>): T = when(patch) {
        PatchField.Keep -> current
        is PatchField.Set -> { if (base != current) conflict(); patch.value }
    }
    override suspend fun patchRecording(base: RecordingSettingsView, patch: RecordingSettingsPatch) = result {
        val saved = updateSettings { old ->
            val mode = field(base.captureMode, old.captureMode, patch.captureMode)
            if (mode == CaptureMode.MICROPHONE_EXPERIMENTAL && !microphoneGranted()) throw BackendFailure(ApiError(ErrorCode.PERMISSION_REQUIRED, "capability.required", requiredCapabilities = setOf(Capability.MICROPHONE)))
            old.copy(recordingEnabled = field(base.recordingEnabled, old.recordingEnabled, patch.recordingEnabled), automaticAnalysis = field(base.automaticAnalysis, old.automaticAnalysis, patch.automaticAnalysis), captureMode = mode)
        }
        afterSave(saved.settings)
        saved.settings.recordingView(saved.recordingRevision)
    }
    private fun connection(base: AiConnectionView, current: AiConnection, patch: ConnectionPatch, secret: LocalSecretChange, baseRevision: Long,slot:AiSlot): AiConnection {
        val key = when (secret) {
            LocalSecretChange.Keep -> current.apiKey
            else -> { val marker=coordinator.domainMarker("aiConfig",baseRevision)?:conflict();if(!coordinator.unchanged(setOf("ai:$slot:key"),marker))conflict(); if (secret is LocalSecretChange.Replace) secret.value else "" }
        }
        val mode = field(base.thinkingMode, current.thinkingMode, patch.thinkingMode)
        if (slot == AiSlot.TRANSCRIPTION && mode != ThinkingMode.DISABLED) throw BackendFailure(ApiError(ErrorCode.VALIDATION, "ai.transcription_thinking_disabled"))
        return current.copy(protocol = field(base.protocol, current.protocol, patch.protocol), url = field(base.url, current.url, patch.url), model = field(base.model, current.model, patch.model), apiKey = key, thinkingMode = mode)
    }
    override suspend fun patchAi(base: AiSettingsView, patch: AiSettingsPatch, analysisSecret: LocalSecretChange, transcriptionSecret: LocalSecretChange) = result {
        updateSettings { old -> old.copy(
            analysis = connection(base.analysis, old.analysis, patch.analysis, analysisSecret, base.revision,AiSlot.ANALYSIS),
            transcription = connection(base.transcription, old.transcription, patch.transcription, transcriptionSecret, base.revision,AiSlot.TRANSCRIPTION),
            conversationBudget = field(base.budget, old.conversationBudget, patch.budget),
            conversationCompatibility = field(base.compatibility,old.conversationCompatibility,patch.compatibility).normalized(),
        ) }.let { it.settings.aiView(it.aiRevision) }
    }
    override suspend fun copyConnection(from: AiSlot, to: AiSlot, expectedRevision: Long) = result {
        require(from != to)
        updateSettings { old ->
            val marker=coordinator.domainMarker("aiConfig",expectedRevision)?:conflict()
            val keys=listOf(from,to).flatMap{slot->listOf("protocol","url","model","key","thinkingMode").map{"ai:$slot:$it"}}.toSet()+if(to==AiSlot.ANALYSIS)setOf("ai:compatibility")else emptySet()
            if(!coordinator.unchanged(keys,marker))conflict()
            val value = if (from == AiSlot.ANALYSIS) old.analysis else old.transcription
            if (to == AiSlot.ANALYSIS) old.copy(analysis = value.copy(thinkingMode = ThinkingMode.DISABLED), conversationCompatibility = ConversationCompatibility.AUTO) else old.copy(transcription = value.copy(protocol = AiProtocol.OPENAI, thinkingMode = ThinkingMode.DISABLED))
        }.let { it.settings.aiView(it.aiRevision) }
    }
    override suspend fun testSlot(slot: AiSlot) = result { val current = coordinator.guard(command) {
            requireOperationActive(operationActive); store.settings() }; testConnection(if (slot == AiSlot.ANALYSIS) current.analysis else current.transcription, slot == AiSlot.TRANSCRIPTION) }
    override suspend fun testDraft(base: AiSettingsView, patch: AiSettingsPatch, slot: AiSlot) = result {
        val value = coordinator.guard(command) {
            requireOperationActive(operationActive); val current = store.settings();
            if(slot == AiSlot.ANALYSIS) connection(base.analysis,current.analysis,patch.analysis,LocalSecretChange.Keep,base.revision,AiSlot.ANALYSIS).also{resolveConversationProfile(it,field(base.compatibility,current.conversationCompatibility,patch.compatibility))}
            else connection(base.transcription,current.transcription,patch.transcription,LocalSecretChange.Keep,base.revision,AiSlot.TRANSCRIPTION)
        }
        testConnection(value,slot == AiSlot.TRANSCRIPTION)
    }
    override suspend fun patchCombined(recordingBase: RecordingSettingsView, recording: RecordingSettingsPatch, aiBase: AiSettingsView, ai: AiSettingsPatch, analysisSecret: LocalSecretChange, transcriptionSecret: LocalSecretChange): ApiResult<Unit> = result {
        val saved = updateSettings { old ->
            val mode = field(recordingBase.captureMode, old.captureMode, recording.captureMode)
            if(mode == CaptureMode.MICROPHONE_EXPERIMENTAL && !microphoneGranted()) throw BackendFailure(ApiError(ErrorCode.PERMISSION_REQUIRED, "capability.required", requiredCapabilities = setOf(Capability.MICROPHONE)))
            old.copy(recordingEnabled = field(recordingBase.recordingEnabled, old.recordingEnabled, recording.recordingEnabled), automaticAnalysis = field(recordingBase.automaticAnalysis, old.automaticAnalysis, recording.automaticAnalysis), captureMode = mode,
                analysis = connection(aiBase.analysis, old.analysis, ai.analysis, analysisSecret, aiBase.revision,AiSlot.ANALYSIS),
                transcription = connection(aiBase.transcription, old.transcription, ai.transcription, transcriptionSecret, aiBase.revision,AiSlot.TRANSCRIPTION),
                conversationBudget = field(aiBase.budget, old.conversationBudget, ai.budget),
                conversationCompatibility = field(aiBase.compatibility,old.conversationCompatibility,ai.compatibility).normalized())
        }
        runCatching { afterSave(saved.settings) }
        Unit
    }
}
