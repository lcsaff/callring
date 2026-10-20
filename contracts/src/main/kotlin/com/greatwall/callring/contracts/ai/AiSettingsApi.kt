package com.greatwall.callring.contracts.ai

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*

sealed class PatchField<out T> {
    data object Keep : PatchField<Nothing>()
    data class Set<T>(val value: T) : PatchField<T>()
}

sealed class LocalSecretChange {
    data object Keep : LocalSecretChange()
    class Replace(val value: String) : LocalSecretChange() { override fun toString() = "Replace([redacted])" }
    data object Clear : LocalSecretChange()
}

enum class AiSlot { ANALYSIS, TRANSCRIPTION }

data class ConversationBudget(val context: Long = 49152, val output: Long = 4096)

data class AiConnectionView(val protocol: AiProtocol, val url: String, val model: String, val keyConfigured: Boolean, val resolvedProfile: ProviderProfile = if(protocol == AiProtocol.ANTHROPIC) ProviderProfile.ANTHROPIC else ProviderProfile.STANDARD, val thinkingMode: ThinkingMode = ThinkingMode.DISABLED, val thinkingSupport: ThinkingSupport = ThinkingSupport.AVAILABLE, val thinkingNotice: String = "", val copyToAnalysisProfile: ProviderProfile? = null)

data class AiSettingsView(val analysis: AiConnectionView, val transcription: AiConnectionView, val budget: ConversationBudget, val revision: Long, val compatibility: ConversationCompatibility = ConversationCompatibility.AUTO)

data class ConnectionPatch(val protocol: PatchField<AiProtocol> = PatchField.Keep, val url: PatchField<String> = PatchField.Keep, val model: PatchField<String> = PatchField.Keep, val thinkingMode: PatchField<ThinkingMode> = PatchField.Keep)

data class AiSettingsPatch(val analysis: ConnectionPatch = ConnectionPatch(), val transcription: ConnectionPatch = ConnectionPatch(), val budget: PatchField<ConversationBudget> = PatchField.Keep, val compatibility: PatchField<ConversationCompatibility> = PatchField.Keep)

interface AiSettingsApi {
    suspend fun patchCombined(recordingBase: RecordingSettingsView, recording: RecordingSettingsPatch, aiBase: AiSettingsView, ai: AiSettingsPatch, analysisSecret: LocalSecretChange = LocalSecretChange.Keep, transcriptionSecret: LocalSecretChange = LocalSecretChange.Keep): ApiResult<Unit>
    suspend fun getRecordingView(): ApiResult<RecordingSettingsView>
    suspend fun patchRecording(base: RecordingSettingsView, patch: RecordingSettingsPatch): ApiResult<RecordingSettingsView>
    suspend fun getAiView(): ApiResult<AiSettingsView>
    suspend fun patchAi(base: AiSettingsView, patch: AiSettingsPatch, analysisSecret: LocalSecretChange = LocalSecretChange.Keep, transcriptionSecret: LocalSecretChange = LocalSecretChange.Keep): ApiResult<AiSettingsView>
    suspend fun copyConnection(from: AiSlot, to: AiSlot, expectedRevision: Long): ApiResult<AiSettingsView>
    suspend fun testSlot(slot: AiSlot): ApiResult<Unit>
    suspend fun testDraft(base: AiSettingsView, patch: AiSettingsPatch, slot: AiSlot): ApiResult<Unit>
}
