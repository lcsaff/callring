package com.greatwall.callring.domain.ai

import java.net.URI

enum class AiProtocol { OPENAI, ANTHROPIC }
enum class ThinkingMode { ENABLED, DISABLED }
enum class ThinkingSupport { AVAILABLE, CUSTOM_GATEWAY, UNSUPPORTED, UNRECOGNIZED }
enum class ConversationCompatibility { AUTO, STANDARD, QWEN_FAST }
enum class ProviderProfile { STANDARD, ANTHROPIC, QWEN_FAST }
data class ConversationBudget(val context: Long = 49152, val output: Long = 4096)

/** Pure connection validation; HTTP endpoint construction remains in the adapter boundary. */
data class ConnectionSpec(val protocol: AiProtocol, val url: String, val apiKey: String, val model: String) {
    fun validate() {
        val parsed = URI(url.trim())
        require(parsed.scheme == "https" && !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null &&
            parsed.rawQuery == null && parsed.rawFragment == null) { "ai.invalid_url" }
        require(apiKey.isNotBlank() && '\r' !in apiKey && '\n' !in apiKey) { "ai.invalid_key" }
        require(model.isNotBlank() && model.length <= 256) { "ai.invalid_model" }
    }
    override fun toString() = "ConnectionSpec(protocol=$protocol, url=$url, apiKey=[redacted], model=$model)"
}
