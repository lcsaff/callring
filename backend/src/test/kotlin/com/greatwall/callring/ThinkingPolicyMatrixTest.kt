package com.greatwall.callring

import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.ai.AiProtocol
import com.greatwall.callring.application.recording.policy.ThinkingContract
import com.greatwall.callring.contracts.ai.ThinkingMode
import com.greatwall.callring.application.recording.policy.ThinkingPolicyResolver
import com.greatwall.callring.contracts.ai.ThinkingSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingPolicyMatrixTest {

    private val dashscope = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private val deepseek = "https://api.deepseek.com/v1"
    private val deepseekAnthropic = "https://api.deepseek.com/anthropic"
    private val mimo = "https://api.xiaomimimo.com/v1"
    private val mimoAnthropic = "https://api.xiaomimimo.com/anthropic/v1/messages"

    @Test fun `qwen openai关闭与开启参数`() {
        val policy = ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, dashscope, "qwen3.8-flash")
        assertEquals(ThinkingContract.QWEN, policy.contract)
        assertEquals(ThinkingSupport.AVAILABLE, policy.support)
        val disable = policy.parameters(ThinkingMode.DISABLED).associate { it.key to it.jsonValue }
        assertEquals("false", disable["enable_thinking"])
        assertEquals("false", disable["preserve_thinking"])
        val enable = policy.parameters(ThinkingMode.ENABLED).associate { it.key to it.jsonValue }
        assertEquals("true", enable["enable_thinking"])
        assertNull("开启态不发送 preserve_thinking", enable["preserve_thinking"])
    }

    @Test fun `qwen日期后缀模型可用`() {
        val policy = ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, dashscope, "qwen3.8-flash-2026-09-01")
        assertEquals(ThinkingSupport.AVAILABLE, policy.support)
    }

    @Test fun `qwen未列入模型不支持`() {
        val policy = ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, dashscope, "qwen3.8-max")
        assertEquals(ThinkingSupport.UNSUPPORTED, policy.support)
        assertEquals("thinking.model_not_verified", policy.reason)
    }

    @Test fun `qwen anthropic组合不支持`() {
        val policy = ThinkingPolicyResolver.resolve(AiProtocol.ANTHROPIC, "https://dashscope.aliyuncs.com/anthropic", "qwen3.8-flash")
        assertEquals(ThinkingSupport.UNSUPPORTED, policy.support)
        assertEquals("thinking.unsupported.protocol", policy.reason)
    }

    @Test fun `deepseek openai开关参数`() {
        val policy = ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, deepseek, "deepseek-flash")
        assertEquals(ThinkingSupport.AVAILABLE, policy.support)
        assertEquals("{\"type\":\"disabled\"}", policy.parameters(ThinkingMode.DISABLED).single().jsonValue)
        assertEquals("thinking", policy.parameters(ThinkingMode.ENABLED).single().key)
    }

    @Test fun `deepseek anthropic开关参数`() {
        val policy = ThinkingPolicyResolver.resolve(AiProtocol.ANTHROPIC, deepseekAnthropic, "deepseek-flash")
        assertEquals(ThinkingSupport.AVAILABLE, policy.support)
        assertEquals("disabled", typeOf(policy))
    }

    @Test fun `mimo openai开关参数`() {
        val policy = ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, mimo, "mimo-v2.6-pro")
        assertEquals(ThinkingSupport.AVAILABLE, policy.support)
        assertEquals("disabled", typeOf(policy))
        assertEquals("enabled", typeOf(policy, ThinkingMode.ENABLED))
    }

    @Test fun `mimo anthropic开关参数`() {
        val policy = ThinkingPolicyResolver.resolve(AiProtocol.ANTHROPIC, mimoAnthropic, "mimo-v2.5")
        assertEquals(ThinkingSupport.AVAILABLE, policy.support)
        assertEquals("disabled", typeOf(policy))
    }

    @Test fun `mimo未列入模型不支持`() {
        val policy = ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, mimo, "mimo-v1")
        assertEquals(ThinkingSupport.UNSUPPORTED, policy.support)
    }

    @Test fun `未识别模型不可切换`() {
        val policy = ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, "https://example.com/v1", "gpt-5.5")
        assertEquals(ThinkingContract.UNCONFIRMED, policy.contract)
        assertEquals(ThinkingSupport.UNRECOGNIZED, policy.support)
        assertTrue(policy.parameters(ThinkingMode.DISABLED).isEmpty())
    }

    private fun typeOf(policy: com.greatwall.callring.application.recording.policy.ThinkingPolicy, mode: ThinkingMode = ThinkingMode.DISABLED): String {
        val value = policy.parameters(mode).single().jsonValue
        return value.substringAfter("\"type\":\"").substringBefore("\"")
    }
}
