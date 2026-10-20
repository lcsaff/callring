package com.greatwall.callring.contracts.ai

enum class ConversationCompatibility { AUTO, STANDARD, QWEN_FAST }

enum class ProviderProfile { STANDARD, ANTHROPIC, QWEN_FAST }

/**
 * 共享的安全 profile 解析；凭证与任意端点子串不参与判断。
 * QWEN_FAST 已废弃：为兼容旧配置仍接受，但只作为能力提示，不再让任何保存/连接失败。
 */

enum class AiFailureKind { TRANSPORT, TIMEOUT, HTTP, AUTH, RATE, CONTEXT, PROTOCOL_JSON, PROTOCOL_SHAPE, PROTOCOL_STOP, OUTPUT_TRUNCATED, RESPONSE_TOO_LARGE, TOOL_COUNT, TOOL_ID, UNKNOWN_TOOL, NOT_ENABLED, ARGUMENT_JSON, SCHEMA, DOMAIN_VALIDATION, CONFLICT, CAPACITY, PROVIDER_MODE, COMMITTED_UNVERIFIED, UNKNOWN }

data class ConversationFailure(val kind: AiFailureKind, val code: String, val httpStatus: Int? = null, val providerCode: String? = null, val fieldPath: String? = null)

data class ModelUsage(val input: Long?, val output: Long?, val total: Long?,val cachedInput:Long?=null,val reasoningOutput:Long?=null,val cacheWriteInput:Long?=null)

data class ReplyDiagnostics(val finishReason: String = "", val usage: ModelUsage? = null)

data class ExchangeMetrics(val requestBytes: Int, val responseBytes: Int, val httpStatus: Int?, val profile: ProviderProfile, val elapsedMillis: Long, val diagnostics: ReplyDiagnostics? = null, val failure: AiFailureKind? = null,val requestFlags:Map<String,Boolean> = emptyMap(),val schemaCount:Int?=null,val contextMessages:Int?=null)
