package com.greatwall.callring.application.api

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
data class ConversationBudget(val context: Long = 49152, val output: Long = 4096) {
    fun validate() { require(context in 8192..1048576 && output in 1024..16384 && output < context) { "ai.budget_invalid" } }
}
data class AiConnectionView(val protocol: AiProtocol, val url: String, val model: String, val keyConfigured: Boolean, val resolvedProfile: ProviderProfile = if(protocol == AiProtocol.ANTHROPIC) ProviderProfile.ANTHROPIC else ProviderProfile.STANDARD)
data class AiSettingsView(val analysis: AiConnectionView, val transcription: AiConnectionView, val budget: ConversationBudget, val revision: Long, val compatibility: ConversationCompatibility = ConversationCompatibility.AUTO)
data class RecordingFolderView(val displayName: String?, val access: AccessState)
data class RecordingSettingsView(val recordingEnabled: Boolean, val automaticAnalysis: Boolean, val captureMode: CaptureMode, val folderConfigured: Boolean, val revision: Long, val folderDisplayName: String? = null, val folderAccess: AccessState = if(folderConfigured) AccessState.UNKNOWN else AccessState.NOT_REQUIRED)
data class ConnectionPatch(val protocol: PatchField<AiProtocol> = PatchField.Keep, val url: PatchField<String> = PatchField.Keep, val model: PatchField<String> = PatchField.Keep)
data class AiSettingsPatch(val analysis: ConnectionPatch = ConnectionPatch(), val transcription: ConnectionPatch = ConnectionPatch(), val budget: PatchField<ConversationBudget> = PatchField.Keep, val compatibility: PatchField<ConversationCompatibility> = PatchField.Keep)
data class RecordingSettingsPatch(val recordingEnabled: PatchField<Boolean> = PatchField.Keep, val automaticAnalysis: PatchField<Boolean> = PatchField.Keep, val captureMode: PatchField<CaptureMode> = PatchField.Keep)
interface AiSettingsApi {
    suspend fun patchCombined(recordingBase: RecordingSettingsView, recording: RecordingSettingsPatch, aiBase: AiSettingsView, ai: AiSettingsPatch, analysisSecret: LocalSecretChange = LocalSecretChange.Keep, transcriptionSecret: LocalSecretChange = LocalSecretChange.Keep): ApiResult<Unit> = aiUnavailable()
    suspend fun getRecordingView(): ApiResult<RecordingSettingsView>
    suspend fun patchRecording(base: RecordingSettingsView, patch: RecordingSettingsPatch): ApiResult<RecordingSettingsView>
    suspend fun getAiView(): ApiResult<AiSettingsView>
    suspend fun patchAi(base: AiSettingsView, patch: AiSettingsPatch, analysisSecret: LocalSecretChange = LocalSecretChange.Keep, transcriptionSecret: LocalSecretChange = LocalSecretChange.Keep): ApiResult<AiSettingsView>
    suspend fun copyConnection(from: AiSlot, to: AiSlot, expectedRevision: Long): ApiResult<AiSettingsView>
    suspend fun testSlot(slot: AiSlot): ApiResult<Unit>
    suspend fun testDraft(base: AiSettingsView, patch: AiSettingsPatch, slot: AiSlot): ApiResult<Unit> = aiUnavailable()
}
object UnavailableAiSettingsApi : AiSettingsApi {
    override suspend fun getRecordingView() = aiUnavailable()
    override suspend fun patchRecording(base: RecordingSettingsView, patch: RecordingSettingsPatch) = aiUnavailable()
    override suspend fun getAiView() = aiUnavailable()
    override suspend fun patchAi(base: AiSettingsView, patch: AiSettingsPatch, analysisSecret: LocalSecretChange, transcriptionSecret: LocalSecretChange) = aiUnavailable()
    override suspend fun copyConnection(from: AiSlot, to: AiSlot, expectedRevision: Long) = aiUnavailable()
    override suspend fun testSlot(slot: AiSlot) = aiUnavailable()
}
fun aiUnavailable(): ApiResult.Failure = ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "ai.unavailable"))
