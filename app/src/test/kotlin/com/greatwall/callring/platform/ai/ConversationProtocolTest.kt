package com.greatwall.callring.platform.ai

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.conversation.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.io.*
import java.net.*

class ConversationProtocolTest {
    @Test fun httpErrorsRemainTypedAndGatewayBodiesNeverEnterErrors() = runBlocking {
        val cases = listOf(
            Triple(400, """{"error":{"code":"context_length_exceeded","message":"sentinel-secret"}}""", "ai.provider_context_rejected"),
            Triple(400, """{"error":{"code":"other","message":"context_length_exceeded sentinel-secret"}}""", "ai.protocol_unsupported"),
            Triple(413, "sentinel-secret", "ai.provider_context_rejected"),
            Triple(401, "sentinel-secret", "ai.authentication_failed"),
            Triple(302, "sentinel-secret", "ai.redirect_rejected"))
        for ((status, body, expected) in cases) {
            var opens = 0; var disconnected = false
            val output = ByteArrayOutputStream()
            lateinit var http: HttpURLConnection
            val client = CompatibleConversationClient { url ->
                opens++
                object : HttpURLConnection(url) {
                    override fun connect() = Unit
                    override fun usingProxy() = false
                    override fun disconnect() { disconnected = true }
                    override fun getOutputStream() = output
                    override fun getResponseCode() = status
                    override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
                }.also { http = it }
            }
            val encoded = client.encode(connection, ConversationRequest(aiObject("text" to aiString(connection.apiKey)), emptyList(), ToolRegistry().definitions, 4096))
            val error = try { client.exchange(connection, encoded); error("expected rejection") } catch (e: BackendFailure) { e.error }
            assertEquals(expected, error.message)
            assertFalse(error.toString().contains(connection.apiKey))
            assertFalse(output.toString("UTF-8").contains(connection.apiKey))
            assertEquals("Bearer ${connection.apiKey}", http.getRequestProperty("Authorization"))
            assertEquals(1, opens); assertTrue(disconnected); assertFalse(http.instanceFollowRedirects)
        }
    }
    @Test fun oversizedHttpResponseIsRejectedBeforeDecode() = runBlocking {
        val client = CompatibleConversationClient { url -> object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = ByteArrayInputStream(ByteArray(ContextBudget.RESPONSE_BYTES + 1))
        } }
        val error = try { client.exchange(connection, byteArrayOf()); error("expected rejection") } catch (e: BackendFailure) { e.error }
        assertEquals("ai.response_too_large", error.message)
    }
    @Test fun shortKeyRedactsDataWithoutChangingAnthropicProtocolFields() {
        val call = ConversationToolCall("call-one", "root_checkNumber", aiObject("raw" to aiString("one")))
        val blocks = listOf(aiObject("type" to aiString("tool_use"), "id" to aiString(call.id), "name" to aiString(call.name), "input" to call.arguments))
        val request = ConversationRequest(aiObject(), listOf(ConversationMessage("assistant", "", listOf(call), blocks = blocks)), ToolRegistry().definitions, 4096)
        val body = JSONObject(String(CompatibleConversationClient().encode(connection.copy(protocol = AiProtocol.ANTHROPIC, apiKey = "one"), request)))
        val block = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0)
        assertEquals("call-one", block.getString("id"))
        assertEquals("root_checkNumber", block.getString("name"))
        assertEquals("[redacted]", block.getJSONObject("input").getString("raw"))
    }
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
