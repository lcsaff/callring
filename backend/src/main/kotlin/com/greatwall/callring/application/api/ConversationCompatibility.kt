package com.greatwall.callring.application.api

import java.net.URI

enum class ConversationCompatibility { AUTO, STANDARD, QWEN_FAST }
enum class ProviderProfile { STANDARD, ANTHROPIC, QWEN_FAST }

/**
 * 共享的安全 profile 解析；凭证与任意端点子串不参与判断。
 * QWEN_FAST 已废弃：为兼容旧配置仍接受，但只作为能力提示，不再让任何保存/连接失败。
 */
fun resolveConversationProfile(connection: AiConnection, compatibility: ConversationCompatibility = ConversationCompatibility.AUTO): ProviderProfile {
    if (connection.protocol == AiProtocol.ANTHROPIC) return ProviderProfile.ANTHROPIC
    if (compatibility == ConversationCompatibility.STANDARD) return ProviderProfile.STANDARD
    val supported = connection.model == "qwen3.8-flash" || connection.model.matches(Regex("qwen3\\.8-flash-\\d{4}-\\d{2}-\\d{2}"))
    val uri = runCatching { URI(connection.url.trim()) }.getOrNull()
    val official = uri?.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1,443) &&
        uri.host?.lowercase() in setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com")
    return if(supported && official) ProviderProfile.QWEN_FAST else ProviderProfile.STANDARD
}

/** 旧“高级兼容”已废弃：QWEN_FAST 一律归一为 AUTO，读取、写入与补丁都走这里。 */
fun ConversationCompatibility.normalized(): ConversationCompatibility =
    if (this == ConversationCompatibility.QWEN_FAST) ConversationCompatibility.AUTO else this

enum class AiFailureKind { TRANSPORT, TIMEOUT, HTTP, AUTH, RATE, CONTEXT, PROTOCOL_JSON, PROTOCOL_SHAPE, PROTOCOL_STOP, OUTPUT_TRUNCATED, RESPONSE_TOO_LARGE, TOOL_COUNT, TOOL_ID, UNKNOWN_TOOL, NOT_ENABLED, ARGUMENT_JSON, SCHEMA, DOMAIN_VALIDATION, CONFLICT, CAPACITY, PROVIDER_MODE, COMMITTED_UNVERIFIED, UNKNOWN }
data class ConversationFailure(val kind: AiFailureKind, val code: String, val httpStatus: Int? = null, val providerCode: String? = null, val fieldPath: String? = null)
data class ModelUsage(val input: Long?, val output: Long?, val total: Long?,val cachedInput:Long?=null,val reasoningOutput:Long?=null,val cacheWriteInput:Long?=null)
data class ReplyDiagnostics(val finishReason: String = "", val usage: ModelUsage? = null)
data class ExchangeMetrics(val requestBytes: Int, val responseBytes: Int, val httpStatus: Int?, val profile: ProviderProfile, val elapsedMillis: Long, val diagnostics: ReplyDiagnostics? = null, val failure: AiFailureKind? = null,val requestFlags:Map<String,Boolean> = emptyMap(),val schemaCount:Int?=null,val contextMessages:Int?=null)
