package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationCompatibilityTest {
    private val client = CompatibleConversationClient()
    private fun reply(stop: String = "stop", calls: JSONArray? = null) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("finish_reason", stop).put("message", JSONObject().put("role", "assistant")
            .put("content", if(calls == null) "完成" else JSONObject.NULL).put("function_call", JSONObject.NULL)
            .put("refusal", JSONObject.NULL).put("tool_calls", calls ?: JSONObject.NULL)))).toString()
    private fun calls(count: Int) = JSONArray().also { array -> repeat(count) { i -> array.put(JSONObject()
        .put("id", "call-$i").put("type", "function").put("function", JSONObject()
            .put("name", "settings_saveAppearance").put("arguments", "{\"colorMode\":\"DARK\"}"))) } }

    @Test fun modernFinalAllowsNullLegacyFieldAndEmptyToolArray() {
        assertTrue(client.decode(AiProtocol.OPENAI, reply()) is ConversationReply.Final)
        val root = JSONObject(reply()); root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("tool_calls", JSONArray())
        assertTrue(client.decode(AiProtocol.OPENAI, root.toString()) is ConversationReply.Final)
    }
    @Test fun completeTenCallsAndNullLegacyFieldReachOrchestrator() {
        assertEquals(10, (client.decode(AiProtocol.OPENAI, reply("tool_calls", calls(10))) as ConversationReply.ToolBatch).calls.size)
    }
    @Test fun protocolDecodeDoesNotApplyBusinessSchemaOrToolRegistry() {
        val batch = calls(1); batch.getJSONObject(0).getJSONObject("function").put("name", "future_tool").put("arguments", "{\"darkMode\":true}")
        assertEquals("future_tool", (client.decode(AiProtocol.OPENAI, reply("tool_calls", batch)) as ConversationReply.ToolBatch).calls.single().name)
    }
    @Test fun truncationLegacyAndCountHaveDistinctSafeErrors() {
        fun code(body: String) = try { client.decode(AiProtocol.OPENAI,body); "accepted" } catch(e: BackendFailure) { e.error.message }
        assertEquals("ai.output_truncated", code(reply("length", calls(1))))
        assertEquals("ai.tool_count", code(reply("tool_calls", calls(33))))
        val old = JSONObject(reply()); old.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("function_call", JSONObject().put("name", "legacy"))
        assertEquals("ai.legacy_function_call_unsupported", code(old.toString()))
    }
}
