package com.greatwall.callring.application.recording.policy

import com.greatwall.callring.contracts.ai.*
import java.net.URI
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.contracts.recording.*

fun ConversationBudget.validate() { require(context in 8192..1048576 && output in 1024..16384 && output < context) { "ai.budget_invalid" } }

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

enum class ThinkingContract { QWEN, DEEPSEEK, MIMO, UNCONFIRMED }

/** 一个思考参数；HTTP JSON 请求体顶层字段，值按 JSON 文本保存。 */

data class ThinkingParameter(val key: String, val jsonValue: String)

/** 一次解析的不可变结论，供客户端按开关状态取参数。 */

data class ThinkingPolicy(
    val contract: ThinkingContract,
    val protocol: AiProtocol,
    val support: ThinkingSupport,
    val disable: List<ThinkingParameter>,
    val enable: List<ThinkingParameter>,
    val reason: String,
    val officialHost: Boolean,
) {
    fun parameters(mode: ThinkingMode): List<ThinkingParameter> = when (mode) {
        ThinkingMode.DISABLED -> disable
        ThinkingMode.ENABLED -> enable
    }
}

/**
 * 三家厂商 × 两种协议的思考开关解析。纯函数、无网络、无密钥。
 * 依据：Qwen `enable_thinking`、DeepSeek/MiMo `thinking.type`；Anthropic 协议同样用 body 的 `thinking` 对象。
 */

object ThinkingPolicyResolver {

    private val OFFICIAL_HOSTS = mapOf(
        ThinkingContract.QWEN to setOf(
            "dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com",
        ),
        ThinkingContract.DEEPSEEK to setOf("api.deepseek.com"),
        ThinkingContract.MIMO to setOf("api.xiaomimimo.com"),
    )

    private val MIMO_THINKING_MODELS = setOf(
        "mimo-v2.6-flash", "mimo-v2.6-pro", "mimo-v2.6-pro-ultraspeed", "mimo-v2.5-pro", "mimo-v2.5",
    )
    private val QWEN_FAST = "qwen3.8-flash"
    private val QWEN_FAST_DATED = Regex("^qwen3\\.8-flash-\\d{4}-\\d{2}-\\d{2}$")

    private fun thinkingType(value: String) = listOf(ThinkingParameter("thinking", "{\"type\":\"$value\"}"))
    private val QWEN_DISABLE = listOf(
        ThinkingParameter("enable_thinking", "false"),
        ThinkingParameter("preserve_thinking", "false"),
    )
    private val QWEN_ENABLE = listOf(ThinkingParameter("enable_thinking", "true"))
    private val DISABLE = thinkingType("disabled")
    private val ENABLE = thinkingType("enabled")

    fun contractOf(model: String): ThinkingContract {
        val value = model.trim()
        return when {
            value.startsWith("qwen", ignoreCase = true) -> ThinkingContract.QWEN
            value.startsWith("deepseek", ignoreCase = true) -> ThinkingContract.DEEPSEEK
            value.startsWith("mimo", ignoreCase = true) -> ThinkingContract.MIMO
            else -> ThinkingContract.UNCONFIRMED
        }
    }

    fun resolve(protocol: AiProtocol, url: String, model: String): ThinkingPolicy {
        val contract = contractOf(model)
        if (contract == ThinkingContract.UNCONFIRMED) {
            return policy(contract, protocol, ThinkingSupport.UNRECOGNIZED, emptyList(), emptyList(), "thinking.unrecognized", false)
        }
        val official = isOfficialHost(contract, url)
        val support = if (official) ThinkingSupport.AVAILABLE else ThinkingSupport.CUSTOM_GATEWAY

        if (contract == ThinkingContract.QWEN) {
            if (protocol != AiProtocol.OPENAI) {
                return policy(contract, protocol, ThinkingSupport.UNSUPPORTED, emptyList(), emptyList(), "thinking.unsupported.protocol", official)
            }
            val name = model.trim()
            val known = name == QWEN_FAST || QWEN_FAST_DATED.matches(name)
            return if (known) policy(contract, protocol, support, QWEN_DISABLE, QWEN_ENABLE, "thinking.available", official)
            else policy(contract, protocol, ThinkingSupport.UNSUPPORTED, emptyList(), emptyList(), "thinking.model_not_verified", official)
        }

        if (contract == ThinkingContract.MIMO && model.trim().lowercase() !in MIMO_THINKING_MODELS) {
            return policy(contract, protocol, ThinkingSupport.UNSUPPORTED, emptyList(), emptyList(), "thinking.model_not_verified", official)
        }

        return policy(contract, protocol, support, DISABLE, ENABLE, "thinking.available", official)
    }

    private fun policy(
        contract: ThinkingContract,
        protocol: AiProtocol,
        support: ThinkingSupport,
        disable: List<ThinkingParameter>,
        enable: List<ThinkingParameter>,
        reason: String,
        official: Boolean,
    ) = ThinkingPolicy(contract, protocol, support, disable, enable, reason, official)

    private fun isOfficialHost(contract: ThinkingContract, url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.rawUserInfo != null) return false
        if (uri.port !in listOf(-1, 443)) return false
        val host = uri.host?.lowercase() ?: return false
        return OFFICIAL_HOSTS[contract]?.contains(host) == true
    }
}

/** 门禁助手：只根据解析结论返回可发送状态与原因，供客户端与后台共用。 */

fun ThinkingPolicy.blockReason(): String? = when (support) {
    ThinkingSupport.AVAILABLE, ThinkingSupport.CUSTOM_GATEWAY -> null
    ThinkingSupport.UNSUPPORTED -> reason
    ThinkingSupport.UNRECOGNIZED -> reason
}
