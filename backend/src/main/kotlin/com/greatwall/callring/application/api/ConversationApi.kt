package com.greatwall.callring.application.api

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class ConversationPhase { IDLE, CAPTURING, BUDGETING, REQUESTING, VALIDATING, EXECUTING, WAITING_FOREGROUND, COMPLETED, STOPPING, STOPPED, FAILED }
enum class ToolStatus { READ, COMMITTED, QUEUED, LAUNCHED, CANCELLED, DENIED, FAILED, UNKNOWN, SKIPPED }
data class ToolReceipt(val operationId: String, val toolCallId: String, val status: ToolStatus, val value: AiJson = AiJson.Null, val error: ApiError? = null, val commitRevision: RevisionVector? = null, val changedDomains: Set<DataDomain> = emptySet(), val observedAt: Long = System.currentTimeMillis())
data class ConversationMessage(val role: String, val text: String, val toolCalls: List<ConversationToolCall> = emptyList(), val receipts: List<ToolReceipt> = emptyList(), val blocks: List<AiJson> = emptyList())
data class ConversationToolCall(val id: String, val name: String, val arguments: AiJson.Obj)
data class ConversationState(val sessionId: String, val phase: ConversationPhase = ConversationPhase.IDLE, val messages: List<ConversationMessage> = emptyList(), val receipts: List<ToolReceipt> = emptyList(), val error: ApiError? = null, val target: String = "", val budgetBytes: Long = 0) {
    val busy get() = phase !in setOf(ConversationPhase.IDLE, ConversationPhase.COMPLETED, ConversationPhase.STOPPED, ConversationPhase.FAILED)
}
interface ConversationApi {
    val state: StateFlow<ConversationState>
    val foreground: ForegroundActionApi
    suspend fun send(expectedSessionId: String, text: String): ApiResult<Unit>
    suspend fun stop(expectedSessionId: String): ApiResult<Unit>
    suspend fun newSession(expectedSessionId: String): ApiResult<Unit>
}
enum class ForegroundActionKind { CAPABILITY, APP_SETTINGS, PIN_WIDGET, TEST_OVERLAY, PREVIEW_CALL, IMPORT_AUDIO, IMPORT_RECORDING, IMPORT_CALENDAR, SELECT_FOLDER, EXPORT_CSV, CONFIRM, CREDENTIAL, COPY_CONNECTION, TEST_CONNECTION }
enum class ForegroundResultStatus { VERIFIED, LAUNCHED, CANCELLED, DENIED, UNAVAILABLE }
data class ForegroundTicket(val requestId: String, val sessionId: String, val turnId: String, val toolCallId: String, val generation: Long, val kind: ForegroundActionKind, val parameters: AiJson.Obj, val createdAt: Long, val expiresAt: Long, val launched: Boolean = false)
data class ForegroundHostResult(val status: ForegroundResultStatus, val document: DocumentRef? = null)
interface ForegroundActionApi {
    val pending: StateFlow<ForegroundTicket?>
    fun attachHost(): String
    fun detachHost(token: String)
    fun beginLaunch(requestId: String, token: String): Boolean
    fun deliver(requestId: String, result: ForegroundHostResult): Boolean
}
object UnavailableForegroundActionApi : ForegroundActionApi {
    override val pending = MutableStateFlow<ForegroundTicket?>(null)
    override fun attachHost() = ""
    override fun detachHost(token: String) = Unit
    override fun beginLaunch(requestId: String, token: String) = false
    override fun deliver(requestId: String, result: ForegroundHostResult) = false
}
object UnavailableConversationApi : ConversationApi {
    override val state = MutableStateFlow(ConversationState("unavailable"))
    override val foreground = UnavailableForegroundActionApi
    override suspend fun send(expectedSessionId: String, text: String) = aiUnavailable()
    override suspend fun stop(expectedSessionId: String) = aiUnavailable()
    override suspend fun newSession(expectedSessionId: String) = aiUnavailable()
}
