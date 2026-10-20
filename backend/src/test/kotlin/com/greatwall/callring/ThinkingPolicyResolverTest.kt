package com.greatwall.callring

import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.ai.AiProtocol
import com.greatwall.callring.application.recording.policy.ThinkingContract
import com.greatwall.callring.application.recording.policy.ThinkingPolicyResolver
import com.greatwall.callring.contracts.ai.ThinkingSupport
import com.greatwall.callring.application.recording.policy.blockReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingPolicyResolverTest {

    @Test fun `官方host识别`() {
        listOf(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            "https://dashscope-us.aliyuncs.com/compatible-mode/v1",
        ).forEach { url ->
            val policy = ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, url, "qwen3.8-flash")
            assertTrue(url, policy.officialHost)
            assertEquals(ThinkingSupport.AVAILABLE, policy.support)
        }
        assertTrue(ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, "https://api.deepseek.com/v1", "deepseek-flash").officialHost)
        assertTrue(ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, "https://api.xiaomimimo.com/v1", "mimo-v2.6-pro").officialHost)
    }

    @Test fun `自定义网关可用但标记`() {
        val policy = ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, "https://gateway.example.com/v1", "qwen3.8-flash")
        assertFalse(policy.officialHost)
        assertEquals(ThinkingSupport.CUSTOM_GATEWAY, policy.support)
        assertNull(policy.blockReason())
        assertTrue(policy.parameters(com.greatwall.callring.contracts.ai.ThinkingMode.DISABLED).isNotEmpty())
    }

    @Test fun `非https或异常端口不算官方`() {
        assertFalse(ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, "http://dashscope.aliyuncs.com/v1", "qwen3.8-flash").officialHost)
        assertFalse(ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, "https://dashscope.aliyuncs.com:8443/v1", "qwen3.8-flash").officialHost)
        assertFalse(ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, "https://user@dashscope.aliyuncs.com/v1", "qwen3.8-flash").officialHost)
    }

    @Test fun `合同前缀识别`() {
        assertEquals(ThinkingContract.QWEN, ThinkingPolicyResolver.contractOf("Qwen3.8-Flash"))
        assertEquals(ThinkingContract.DEEPSEEK, ThinkingPolicyResolver.contractOf("deepseek-flash"))
        assertEquals(ThinkingContract.MIMO, ThinkingPolicyResolver.contractOf("MiMo-v2.6-pro"))
        assertEquals(ThinkingContract.UNCONFIRMED, ThinkingPolicyResolver.contractOf(""))
        assertEquals(ThinkingContract.UNCONFIRMED, ThinkingPolicyResolver.contractOf("claude-sonnet-5"))
    }

    @Test fun `阻断原因`() {
        val unsupported = ThinkingPolicyResolver.resolve(AiProtocol.ANTHROPIC, "https://dashscope.aliyuncs.com/x", "qwen3.8-flash")
        assertEquals("thinking.unsupported.protocol", unsupported.blockReason())
        val unrecognized = ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, "https://example.com/v1", "some-model")
        assertEquals("thinking.unrecognized", unrecognized.blockReason())
        val available = ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, "https://api.deepseek.com/v1", "deepseek-flash")
        assertNull(available.blockReason())
    }
}
