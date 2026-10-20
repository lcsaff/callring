package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.application.ports.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderProfileTest {
    private val official = AiConnection(url="https://dashscope.aliyuncs.com/compatible-mode/v1",model="qwen3.8-flash",apiKey="fixture-key")
    private fun body(connection: AiConnection) = JSONObject(String(CompatibleConversationClient().encode(connection, ConversationRequest(aiObject(),emptyList(),emptyList(),4096))))
    @Test fun officialQwenUsesFastAtTopLevelWithoutIncreasingOutput() {
        val json=body(official)
        assertTrue(json.has("enable_thinking"))
        assertFalse(json.getBoolean("enable_thinking")); assertFalse(json.getBoolean("preserve_thinking"))
        assertEquals(4096,json.getInt("max_tokens"))
    }
    @Test fun spoofedAndProxyHostsAreNotOfficialButKeepTheDocumentedCloseParameters() {
        listOf("https://dashscope.aliyuncs.com.attacker.invalid/v1","https://proxy.example/v1").forEach { url ->
            val policy = ThinkingPolicyResolver.resolve(AiProtocol.OPENAI, url, official.model)
            assertFalse(policy.officialHost)
            assertEquals(ThinkingSupport.CUSTOM_GATEWAY, policy.support)
            val json=body(official.copy(url=url))
            assertTrue(json.has("enable_thinking"))
            assertFalse(json.getBoolean("enable_thinking")); assertFalse(json.getBoolean("preserve_thinking"))
        }
        assertFalse(body(official.copy(model="another-model")).has("preserve_thinking"))
    }
    @Test fun unhandledReasoningStopsBeforeAnyToolExecution() {
        val text="""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok","reasoning_content":"private thought"}}]}"""
        val error=try { CompatibleConversationClient().decode(AiProtocol.OPENAI,text); error("accepted reasoning") } catch(e: BackendFailure) {e.error}
        assertEquals("ai.provider_mode_mismatch",error.message)
        assertFalse(error.toString().contains("private thought"))
    }
}
