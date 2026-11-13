package com.greatwall.callring.application.recording.model

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.recording.policy.*

data class AiConnection(
    val protocol: AiProtocol = AiProtocol.OPENAI,
    val url: String = "",
    val apiKey: String = "",
    val model: String = "",
    val thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
) {
    fun validate() {
        domainSpec().validate()
    }
    fun endpoint(path: String): String = url.trim().trimEnd('/') + "/" + path
    override fun toString() = "AiConnection(protocol=$protocol, url=$url, apiKey=[redacted], model=$model, thinkingMode=$thinkingMode)"
}

data class RecordingSettings(
    val recordingEnabled: Boolean = false,
    val automaticAnalysis: Boolean = false,
    val analysis: AiConnection = AiConnection(),
    val captureMode: CaptureMode = CaptureMode.SYSTEM_ONLY,
    val systemFolder: String? = null,
    val conversationBudget: ConversationBudget = ConversationBudget(),
    val conversationCompatibility: ConversationCompatibility = ConversationCompatibility.AUTO,
) {
    fun validate() {
        conversationBudget.validate()
        if (analysis.url.isNotBlank() || analysis.apiKey.isNotBlank() || analysis.model.isNotBlank()) analysis.validate()
        if (automaticAnalysis) analysis.validate()
    }
}
