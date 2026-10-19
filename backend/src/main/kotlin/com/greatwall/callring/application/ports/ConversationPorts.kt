package com.greatwall.callring.application.ports

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.ToolDefinition

data class ConversationRequest(val snapshot: AiJson, val messages: List<ConversationMessage>, val tools: List<ToolDefinition>, val output: Long, val compatibility: ConversationCompatibility = ConversationCompatibility.AUTO)
sealed class ConversationReply {
    data class Final(val text: String, val diagnostics: ReplyDiagnostics = ReplyDiagnostics()) : ConversationReply()
    data class ToolBatch(val text: String, val calls: List<ConversationToolCall>, val blocks: List<AiJson> = emptyList(), val diagnostics: ReplyDiagnostics = ReplyDiagnostics()) : ConversationReply()
}
interface ConversationClient {
    val lastExchange: ExchangeMetrics? get() = null
    fun encode(connection: AiConnection, request: ConversationRequest): ByteArray
    suspend fun exchange(connection: AiConnection, encoded: ByteArray): ConversationReply
    fun cancel()
}
