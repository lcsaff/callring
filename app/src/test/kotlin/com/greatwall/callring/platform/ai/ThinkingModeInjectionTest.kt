package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** S2-A 矩阵：关闭/开启参数的位置与值、不可用组合错误、Anthropic 认证头按厂商。 */
class ThinkingModeInjectionTest {
    private val qwen = AiConnection(AiProtocol.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1", "fixture-key", "qwen3.8-flash")
    private val deepseekOpenAi = AiConnection(AiProtocol.OPENAI, "https://api.deepseek.com/v1", "fixture-key", "deepseek-chat")
    private val deepseekAnthropic = AiConnection(AiProtocol.ANTHROPIC, "https://api.deepseek.com/anthropic", "fixture-key", "deepseek-chat")
    private val mimoOpenAi = AiConnection(AiProtocol.OPENAI, "https://api.xiaomimimo.com/v1", "fixture-key", "mimo-v2.6-flash")
    private val mimoAnthropic = AiConnection(AiProtocol.ANTHROPIC, "https://api.xiaomimimo.com/anthropic/v1", "fixture-key", "mimo-v2.6-pro")
    private val unsupportedThinkingKeys = listOf("temperature", "top_p", "presence_penalty", "frequency_penalty")

    private fun body(connection: AiConnection, mode: ThinkingMode = ThinkingMode.DISABLED): JSONObject =
        JSONObject(String(CompatibleConversationClient().encode(connection, ConversationRequest(aiObject(), emptyList(), emptyList(), 4096), mode)))

    private fun thinking(json: JSONObject): JSONObject = json.getJSONObject("thinking")

    @Test fun fiveSupportedCombosPlaceDisableParametersAtHttpTopLevel() {
        val qwenBody = body(qwen)
        assertFalse(qwenBody.getBoolean("enable_thinking"))
        assertFalse(qwenBody.getBoolean("preserve_thinking"))
        assertFalse(qwenBody.has("thinking"))

        listOf(deepseekOpenAi, deepseekAnthropic, mimoOpenAi, mimoAnthropic).forEach { connection ->
            val json = body(connection)
            assertEquals("disabled", thinking(json).getString("type"))
            assertFalse(json.has("enable_thinking"))
            assertFalse(json.has("preserve_thinking"))
        }
        listOf(qwen, deepseekOpenAi, deepseekAnthropic, mimoOpenAi, mimoAnthropic)
            .map(::body)
            .forEach { json -> unsupportedThinkingKeys.forEach { assertFalse("unexpected $it in $json", json.has(it)) } }
    }

    @Test fun enabledModeUsesEnableParametersWithoutPreserveThinking() {
        val qwenEnabled = body(qwen, ThinkingMode.ENABLED)
        assertTrue(qwenEnabled.getBoolean("enable_thinking"))
        assertFalse(qwenEnabled.has("preserve_thinking"))

        listOf(deepseekOpenAi, deepseekAnthropic, mimoOpenAi, mimoAnthropic).forEach { connection ->
            assertEquals("enabled", thinking(body(connection, ThinkingMode.ENABLED)).getString("type"))
        }
    }

    @Test fun unsupportedAndUnrecognizedCombosFailOnlyWhenExplicitlyEnabled() {
        val qwenAnthropic = qwen.copy(protocol = AiProtocol.ANTHROPIC, url = "https://dashscope.aliyuncs.com/anthropic")
        assertEquals("ai.thinking_unsupported", failure { body(qwenAnthropic, ThinkingMode.ENABLED) })
        assertFalse(body(qwenAnthropic).has("enable_thinking"))

        val unknown = AiConnection(AiProtocol.OPENAI, "https://example.test/v1", "fixture-key", "mystery-model")
        assertEquals("ai.model_unrecognized", failure { body(unknown, ThinkingMode.ENABLED) })
        val disabled = body(unknown)
        assertFalse(disabled.has("enable_thinking"))
        assertFalse(disabled.has("thinking"))
    }

    @Test fun anthropicAuthHeaderDependsOnVendorContract() = runBlocking {
        val mimo = captureExchange(mimoAnthropic)
        assertEquals("fixture-key", mimo.getRequestProperty("api-key"))
        assertNull(mimo.getRequestProperty("x-api-key"))
        assertEquals("2023-06-01", mimo.getRequestProperty("anthropic-version"))

        val deepseek = captureExchange(deepseekAnthropic)
        assertEquals("fixture-key", deepseek.getRequestProperty("x-api-key"))
        assertNull(deepseek.getRequestProperty("api-key"))
        assertEquals("2023-06-01", deepseek.getRequestProperty("anthropic-version"))
    }

    private fun failure(block: () -> Unit): String = try {
        block(); fail("expected failure"); error("unreachable")
    } catch (failure: BackendFailure) { failure.error.message }

    private fun captureExchange(connection: AiConnection): HttpURLConnection {
        lateinit var captured: HttpURLConnection
        val client = CompatibleConversationClient { url ->
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getOutputStream() = ByteArrayOutputStream()
                override fun getResponseCode() = 200
                override fun getInputStream(): java.io.InputStream = ByteArrayInputStream(
                    """{"stop_reason":"end_turn","content":[{"type":"text","text":"ok"}]}""".toByteArray())
            }.also { captured = it }
        }
        runBlocking { client.exchange(connection, "{}".toByteArray()) }
        return captured
    }
}
