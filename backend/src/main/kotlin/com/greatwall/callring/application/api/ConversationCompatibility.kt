package com.greatwall.callring.application.api

import java.net.URI

enum class ConversationCompatibility { AUTO, STANDARD, QWEN_FAST }
enum class ProviderProfile { STANDARD, ANTHROPIC, QWEN_FAST }

/** Shared safe profile resolution; credentials and arbitrary endpoint substrings play no part. */
fun resolveConversationProfile(connection: AiConnection, compatibility: ConversationCompatibility = ConversationCompatibility.AUTO): ProviderProfile {
    if (connection.protocol == AiProtocol.ANTHROPIC) {
        require(compatibility != ConversationCompatibility.QWEN_FAST) { "ai.profile_invalid" }
        return ProviderProfile.ANTHROPIC
    }
    if (compatibility == ConversationCompatibility.STANDARD) return ProviderProfile.STANDARD
    val supported = connection.model == "qwen3.8-flash" || connection.model.matches(Regex("qwen3\\.8-flash-\\d{4}-\\d{2}-\\d{2}"))
    val uri = runCatching { URI(connection.url.trim()) }.getOrNull()
    val official = uri?.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1,443) &&
        uri.host?.lowercase() in setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com")
    if(compatibility == ConversationCompatibility.QWEN_FAST) require(supported) { "ai.profile_invalid" }
    return if(supported && (official || compatibility == ConversationCompatibility.QWEN_FAST)) ProviderProfile.QWEN_FAST else ProviderProfile.STANDARD
}

enum class AiFailureKind { TRANSPORT, TIMEOUT, HTTP, AUTH, RATE, CONTEXT, PROTOCOL_JSON, PROTOCOL_SHAPE, PROTOCOL_STOP, OUTPUT_TRUNCATED, RESPONSE_TOO_LARGE, TOOL_COUNT, TOOL_ID, UNKNOWN_TOOL, NOT_ENABLED, ARGUMENT_JSON, SCHEMA, DOMAIN_VALIDATION, CONFLICT, CAPACITY, PROVIDER_MODE, COMMITTED_UNVERIFIED, UNKNOWN }
data class ConversationFailure(val kind: AiFailureKind, val code: String, val httpStatus: Int? = null, val providerCode: String? = null, val fieldPath: String? = null)
data class ModelUsage(val input: Long?, val output: Long?, val total: Long?)
data class ReplyDiagnostics(val finishReason: String = "", val usage: ModelUsage? = null)
data class ExchangeMetrics(val requestBytes: Int, val responseBytes: Int, val httpStatus: Int?, val profile: ProviderProfile, val elapsedMillis: Long, val diagnostics: ReplyDiagnostics? = null, val failure: AiFailureKind? = null)
