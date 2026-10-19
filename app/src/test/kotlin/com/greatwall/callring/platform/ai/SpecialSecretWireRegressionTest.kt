package com.greatwall.callring.platform.ai
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.ConversationRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],manifest=Config.NONE)
class SpecialSecretWireRegressionTest {
    @Test fun encodedSpecialSecretMatrix() {
        val outcomes=mutableListOf<Boolean>()
        for((label,key) in listOf("ordinary" to "SYNTHETIC-ordinary", "quote" to "SYNTHETIC-\"quoted\"", "slash" to "SYNTHETIC-\\slash", "tab" to "SYNTHETIC-\ttab", "newline-invalid-config" to "SYNTHETIC-\nnewline")) {
            val connection=AiConnection(url="https://example.com/v1",apiKey=key,model="fixture")
            val raw=aiObject("value" to aiString(key)).encode()
            for(protocol in AiProtocol.entries) {
                val request=ConversationRequest(messages=listOf(ConversationMessage("assistant","",toolCalls=listOf(ConversationToolCall("c","fixture",aiObject("value" to aiString(key)),wireArguments=raw))),ConversationMessage("tool","",receipts=listOf(ToolReceipt("o","c",ToolStatus.READ,aiObject("value" to aiString(key)))))),tools=emptyList(),output=128)
                val root=JSONObject(String(CompatibleConversationClient().encode(connection.copy(protocol=protocol),request)))
                val messages=root.getJSONArray("messages")
                val args=if(protocol==AiProtocol.OPENAI)messages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments") else messages.getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("input").toString()
                val receipt=if(protocol==AiProtocol.OPENAI)messages.getJSONObject(2).getString("content") else messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("content")
                val argumentLeak=JSONObject(args).getString("value")==key
                val receiptLeak=JSONObject(receipt).getJSONObject("value").getString("value")==key
                val malformed=raw.dropLast(1)
                val malformedLeak=SecretRedactor(listOf(key)).text(malformed)==malformed
                val sanitizer=SecretRedactor(listOf(key))
                val replayCall=ConversationToolCall("c","fixture",sanitizer.redact(aiObject("value" to aiString(key))) as AiJson.Obj,wireArguments=sanitizer.text(raw))
                val replayRequest=request.copy(messages=listOf(ConversationMessage("assistant","",toolCalls=listOf(replayCall))))
                val replayRoot=JSONObject(String(CompatibleConversationClient().encode(connection.copy(protocol=protocol),replayRequest)))
                val replayMessages=replayRoot.getJSONArray("messages")
                val replayArgs=if(protocol==AiProtocol.OPENAI)replayMessages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments") else replayMessages.getJSONObject(0).getJSONArray("content").getJSONObject(0).getJSONObject("input").toString()
                val serviceWireLeak=JSONObject(replayArgs).getString("value")==key
                if(label!="newline-invalid-config")outcomes+=argumentLeak||receiptLeak||malformedLeak||serviceWireLeak
            }
        }
        assertFalse("Supported special-character credentials survive in encoded tool messages; see boolean matrix",outcomes.any{it})
    }
}
