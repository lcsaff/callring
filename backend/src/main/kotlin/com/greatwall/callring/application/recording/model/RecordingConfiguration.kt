package com.greatwall.callring.application.recording.model

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import java.net.URI
import com.greatwall.callring.application.recording.policy.*

data class AiConnection(
    val protocol: AiProtocol = AiProtocol.OPENAI,
    val url: String = "",
    val apiKey: String = "",
    val model: String = "",
    val thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
) {
    fun validate() {
        val parsed = URI(url.trim())
        require(parsed.scheme == "https" && !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null &&
            parsed.rawQuery == null && parsed.rawFragment == null) { "ai.invalid_url" }
        require(apiKey.isNotBlank() && '\r' !in apiKey && '\n' !in apiKey) { "ai.invalid_key" }
        require(model.isNotBlank() && model.length <= 256) { "ai.invalid_model" }
    }
    fun endpoint(path: String): String = url.trim().trimEnd('/') + "/" + path
    override fun toString() = "AiConnection(protocol=$protocol, url=$url, apiKey=[redacted], model=$model, thinkingMode=$thinkingMode)"
}

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
        require(transcription.protocol == AiProtocol.OPENAI) { "ai.transcription_requires_openai" }
        require(transcription.thinkingMode == ThinkingMode.DISABLED) { "ai.transcription_thinking_disabled" }
        if (analysis.url.isNotBlank() || analysis.apiKey.isNotBlank() || analysis.model.isNotBlank()) analysis.validate()
        if (transcription.url.isNotBlank() || transcription.apiKey.isNotBlank() || transcription.model.isNotBlank()) transcription.validate()
        if (automaticAnalysis) { analysis.validate(); transcription.validate() }
    }
}

/** Internal configuration port; credentials never cross the UI capability boundary. */
interface RecordingConfigurationPort {
    suspend fun getSettings(): ApiResult<RecordingSettings>
    suspend fun saveSettings(settings: RecordingSettings): ApiResult<RecordingSettings>
    suspend fun testConnection(connection: AiConnection, transcription: Boolean): ApiResult<Unit>
}

interface RecordingCapability : RecordingApi, RecordingConfigurationPort
