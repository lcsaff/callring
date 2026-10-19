package com.greatwall.callring.application.api

import java.net.URI

enum class AiProtocol { OPENAI, ANTHROPIC }

data class AiConnection(
    val protocol: AiProtocol = AiProtocol.OPENAI,
    val url: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    fun validate() {
        val parsed = URI(url.trim())
        require(parsed.scheme == "https" && !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null &&
            parsed.rawQuery == null && parsed.rawFragment == null) { "ai.invalid_url" }
        require(apiKey.isNotBlank() && '\r' !in apiKey && '\n' !in apiKey) { "ai.invalid_key" }
        require(model.isNotBlank() && model.length <= 256) { "ai.invalid_model" }
    }
    fun endpoint(path: String): String = url.trim().trimEnd('/') + "/" + path
    override fun toString() = "AiConnection(protocol=$protocol, url=$url, apiKey=[redacted], model=$model)"
}

enum class CaptureMode { SYSTEM_ONLY, MICROPHONE_EXPERIMENTAL }
data class RecordingSettings(
    val recordingEnabled: Boolean = false,
    val automaticAnalysis: Boolean = false,
    val analysis: AiConnection = AiConnection(),
    val transcription: AiConnection = AiConnection(),
    val captureMode: CaptureMode = CaptureMode.SYSTEM_ONLY,
    val systemFolder: String? = null,
    val conversationBudget: ConversationBudget = ConversationBudget(),
    val conversationCompatibility: ConversationCompatibility = ConversationCompatibility.AUTO,
) {
    fun validate() {
        conversationBudget.validate()
        resolveConversationProfile(analysis, conversationCompatibility)
        require(transcription.protocol == AiProtocol.OPENAI) { "ai.transcription_requires_openai" }
        if (analysis.url.isNotBlank() || analysis.apiKey.isNotBlank() || analysis.model.isNotBlank()) analysis.validate()
        if (transcription.url.isNotBlank() || transcription.apiKey.isNotBlank() || transcription.model.isNotBlank()) transcription.validate()
        if (automaticAnalysis) { analysis.validate(); transcription.validate() }
    }
}

enum class ArchiveStatus { WAITING_RECORDING, READY, QUEUED, TRANSCRIBING, ANALYZING, COMPLETE, FAILED }
enum class RecordingSource { SYSTEM, IMPORTED, MICROPHONE }
data class CallAnalysis(
    val name: String = "", val organization: String = "", val position: String = "",
    val purpose: String = "", val evidence: String = "", val inferredIdentity: String = "",
    val summary: String = "", val actionItems: List<String> = emptyList(),
)
data class CallArchive(
    val callId: Long, val number: String, val startedAtEpochMillis: Long,
    val generation: Long = 0, val status: ArchiveStatus = ArchiveStatus.WAITING_RECORDING,
    val source: RecordingSource = RecordingSource.IMPORTED, val audioPath: String? = null,
    val audioQuality: String = "recording.unverified", val durationMillis: Long? = null,
    val transcript: String = "", val analysis: CallAnalysis? = null,
    val error: String? = null, val attempts: Int = 0, val nextAttemptAt: Long = 0,
    val analyzedAt: Long? = null, val analysisModel: String = "", val transcriptionModel: String = "",
    val analysisProtocol: AiProtocol? = null,
    val manualAnalysis: Boolean = false,
)
data class RecordingFile(val uri: String, val name: String, val modifiedAt: Long, val size: Long? = null, val modifiedKnown: Boolean = true)

interface RecordingApi {
    suspend fun getIdentity(callId: Long): ApiResult<RecordingIdentity> = when(val result = getArchive(callId)) {
        is ApiResult.Failure -> result
        is ApiResult.Success -> result.value?.let { ApiResult.Success(RecordingIdentity(it.callId, it.number, it.startedAtEpochMillis)) } ?: ApiResult.Failure(ApiError(ErrorCode.NOT_FOUND, "recording.not_found"))
    }
    suspend fun listArchives(): ApiResult<List<CallArchive>> =
        ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "recording.unavailable"))
    suspend fun getSettings(): ApiResult<RecordingSettings>
    suspend fun saveSettings(settings: RecordingSettings): ApiResult<RecordingSettings>
    suspend fun testConnection(connection: AiConnection, transcription: Boolean): ApiResult<Unit>
    suspend fun getArchive(callId: Long): ApiResult<CallArchive?>
    suspend fun importRecording(callId: Long, number: String, startedAt: Long, document: DocumentRef, source: RecordingSource = RecordingSource.IMPORTED): ApiResult<CallArchive>
    suspend fun retry(callId: Long): ApiResult<Unit>
    suspend fun delete(callId: Long): ApiResult<Unit>
    suspend fun play(callId: Long): ApiResult<Unit>
    suspend fun stop(): ApiResult<Unit>
    suspend fun setSystemFolder(document: DocumentRef): ApiResult<RecordingSettings>
    suspend fun systemRecordings(): ApiResult<List<RecordingFile>>
    suspend fun inspectSystemRecording(document:DocumentRef):ApiResult<RecordingFile> = aiUnavailable()
}

data class RecordingIdentity(val callId: Long, val number: String, val startedAtEpochMillis: Long)

object UnavailableRecordingApi : RecordingApi {
    private fun unavailable() = ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "recording.unavailable"))
    override suspend fun getSettings() = unavailable()
    override suspend fun saveSettings(settings: RecordingSettings) = unavailable()
    override suspend fun testConnection(connection: AiConnection, transcription: Boolean) = unavailable()
    override suspend fun getArchive(callId: Long) = unavailable()
    override suspend fun importRecording(callId: Long, number: String, startedAt: Long, document: DocumentRef, source: RecordingSource) = unavailable()
    override suspend fun retry(callId: Long) = unavailable()
    override suspend fun delete(callId: Long) = unavailable()
    override suspend fun play(callId: Long) = unavailable()
    override suspend fun stop() = unavailable()
    override suspend fun setSystemFolder(document: DocumentRef) = unavailable()
    override suspend fun systemRecordings() = unavailable()
}
