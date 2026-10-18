package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.conversation.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ConversationProtocolTest {
    private val connection = AiConnection(url = "https://example.com/v1", model = "fixture", apiKey = "sentinel-secret")
    @Test fun defaultBudgetFitsAll72ToolsAndCompleteEmptyDomainMetadata() {
        val snapshot = AiBusinessSnapshot("snapshot", 1, "UTC", RevisionVector("fixture", mapOf("core" to 0)), (1..21).map { SnapshotDomain("D%02d".format(it), SnapshotDomainStatus.EMPTY, 0, 0, 1, "all_stored", digest("[]"), AiJson.Arr(emptyList())) })
        val body = CompatibleConversationClient().encode(connection, ConversationRequest(snapshot.json(), listOf(ConversationMessage("user", "show settings")), ToolRegistry().definitions, 4096))
        val estimate = ContextBudget.check(body, ConversationBudget())
        println("DEFAULT_CONTEXT_PROOF bytes=${body.size} estimate=$estimate tools=72 domains=21")
        assertEquals(72, JSONObject(String(body)).getJSONArray("tools").length())
        assertFalse(String(body).contains(connection.apiKey))
    }
    @Test fun rejectsWholeMalformedBatchAndTruncatedResponses() {
        val client = CompatibleConversationClient()
        val good = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"one","type":"function","function":{"name":"root_checkNumber","arguments":"{\"raw\":\"31234\"}"}}]}}]}"""
        assertTrue(client.decode(AiProtocol.OPENAI, good) is ConversationReply.ToolBatch)
        assertTrue(runCatching { client.decode(AiProtocol.OPENAI, good.replace("tool_calls\",\"message", "length\",\"message")) }.isFailure)
        assertTrue(runCatching { client.decode(AiProtocol.OPENAI, good.dropLast(2)) }.isFailure)
        assertTrue(runCatching { client.decode(AiProtocol.OPENAI, good.replace("root_checkNumber", "run_sql")) }.isFailure)
    }
    @Test fun anthropicToolResultsAreFirstAndPairedBeforeUserText() {
        val call = ConversationToolCall("one", "root_checkNumber", aiObject("raw" to aiString("31234")))
        val request = ConversationRequest(aiObject(), listOf(ConversationMessage("assistant", "", listOf(call)), ConversationMessage("tool", "", receipts = listOf(ToolReceipt("op", "one", ToolStatus.READ, aiString("ok"))))), ToolRegistry().definitions, 4096)
        val body = JSONObject(String(CompatibleConversationClient().encode(connection.copy(protocol = AiProtocol.ANTHROPIC), request)))
        val messages = body.getJSONArray("messages")
        val result = messages.getJSONObject(messages.length()-1).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("one", result.getString("tool_use_id"))
        assertTrue(CompatibleConversationClient().decode(AiProtocol.ANTHROPIC, """{"stop_reason":"end_turn","content":[{"type":"text","text":"done"}]}""") is ConversationReply.Final)
    }
}
