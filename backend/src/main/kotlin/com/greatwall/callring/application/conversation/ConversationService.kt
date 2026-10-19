package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.*
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ConversationService(
    private val api: () -> CallringUiApi,
    private val snapshots: AiSnapshotApi,
    private val settings: () -> RecordingSettings,
    private val coordinator: BusinessRevisionCoordinator,
    private val clientFactory: () -> ConversationClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val resources: ForegroundResourceRegistry = ForegroundResourceRegistry(),
) : ConversationApi, AutoCloseable {
    private val lock = Any()
    override val state = MutableStateFlow(ConversationState(UUID.randomUUID().toString()))
    override val foreground = ForegroundActionService(checkCommand = { command -> command == null || coordinator.committed(command.operationId) != null || runCatching { coordinator.guard(command.copy(mayCommit = { true })) {} }.isSuccess })
    private val generation = AtomicLong()
    private val history = mutableListOf<ConversationMessage>()
    private var turnJob: Job? = null
    @Volatile private var client: ConversationClient? = null
    @Volatile private var connectionChanged = false
    @Volatile private var fingerprint = ""
    private val executor = ToolExecutor(api, coordinator, foreground, resources)
    private val monitor = scope.launch {
        coordinator.changes.collect {
            if(state.value.busy && fingerprint.isNotEmpty()) {
                val changed = runCatching { connectionFingerprint(settings()) != fingerprint }.getOrDefault(true)
                if(changed) { connectionChanged = true; client?.cancel(); foreground.invalidate(); turnJob?.cancel() }
            }
        }
    }
    init {
        scope.launch { foreground.pending.collect { pending ->
            synchronized(lock) { if(state.value.busy && state.value.phase !in setOf(ConversationPhase.STOPPING, ConversationPhase.CAPTURING, ConversationPhase.BUDGETING, ConversationPhase.REQUESTING, ConversationPhase.VALIDATING))
                state.value = state.value.copy(phase = if(pending != null) ConversationPhase.WAITING_FOREGROUND else ConversationPhase.EXECUTING) }
        } }
    }
    override suspend fun send(expectedSessionId: String, text: String) = enqueue(expectedSessionId,text,null)
    override suspend fun sendTo(expectedSessionId: String, text: String, disclosedTarget: AiConnectionView): ApiResult<Unit> {
        val current = runCatching { settings() }.getOrElse { return failed(ErrorCode.STORAGE_FAILURE,"ai.settings_storage_failed") }
        if(!matches(disclosedTarget,current)) return failed(ErrorCode.CONFLICT,"ai.connection_changed")
        return enqueue(expectedSessionId,text,disclosedTarget)
    }
    private fun matches(target: AiConnectionView, configuration: RecordingSettings): Boolean {
        val connection=configuration.analysis
        return target.protocol == connection.protocol && target.url == connection.url && target.model == connection.model && target.resolvedProfile == resolveConversationProfile(connection,configuration.conversationCompatibility)
    }
    private fun enqueue(expectedSessionId: String, text: String, disclosedTarget: AiConnectionView?): ApiResult<Unit> {
        if(text.isBlank() || text.toByteArray(Charsets.UTF_8).size > 32768) return failed(ErrorCode.VALIDATION, "ai.message_invalid")
        synchronized(lock) {
            if(expectedSessionId != state.value.sessionId) return failed(ErrorCode.CONFLICT, "ai.session_changed")
            if(state.value.busy) return failed(ErrorCode.CONFLICT, "ai.busy")
            val token = generation.incrementAndGet()
            connectionChanged = false
            state.value = state.value.copy(phase = ConversationPhase.CAPTURING, error = null)
            turnJob = scope.launch(start = CoroutineStart.LAZY) { runTurn(expectedSessionId, token, text, disclosedTarget) }
            turnJob!!.start()
        }
        return ApiResult.Success(Unit)
    }
    private fun publish(phase: ConversationPhase, error: ApiError? = null, receipts: List<ToolReceipt>? = null, estimate: Long? = null) = synchronized(lock) {
        state.value = state.value.copy(phase = phase, messages = history.toList(), receipts = receipts ?: state.value.receipts, error = error, budgetBytes = estimate ?: state.value.budgetBytes)
    }
    private suspend fun runTurn(session: String, token: Long, text: String, disclosedTarget: AiConnectionView?) {
        val turnId = UUID.randomUUID().toString()
        val ledger = linkedMapOf<String, Pair<String, ToolReceipt>>()
        val turnReceipts = mutableListOf<ToolReceipt>()
        var batch: List<ConversationToolCall> = emptyList()
        var paired = true
        var inFlight: Pair<ConversationToolCall, CommandContext>? = null
        var operationCount = 0
        var activeRedactor: SecretRedactor? = null
        try {
            val configuration = settings()
            if(disclosedTarget != null && !matches(disclosedTarget,configuration)) throw BackendFailure(ApiError(ErrorCode.CONFLICT,"ai.connection_changed"))
            configuration.analysis.validate()
            val currentFingerprint = connectionFingerprint(configuration)
            if(fingerprint.isNotEmpty() && fingerprint != currentFingerprint) synchronized(lock) { history.clear() }
            fingerprint = currentFingerprint
            val redactor = SecretRedactor(listOf(configuration.analysis.apiKey, configuration.transcription.apiKey))
            activeRedactor = redactor
            synchronized(lock) { history += ConversationMessage("user", redactor.text(text)); state.value = state.value.copy(target = redactor.text("${configuration.analysis.url} · ${configuration.analysis.model}")) }
            val connection = configuration.analysis
            val operationClient = clientFactory().also { client = it }
            var snapshot = capture()
            for(requestIndex in 1..16) {
                ensureActive(session, token)
                publish(ConversationPhase.BUDGETING)
                val request = ConversationRequest(snapshot.json(), synchronized(lock) { history.toList() }, executor.registry.definitions, configuration.conversationBudget.output,configuration.conversationCompatibility)
                val encoded = operationClient.encode(connection, request)
                val estimate = ContextBudget.check(encoded, settings().conversationBudget)
                publish(ConversationPhase.REQUESTING, estimate = estimate)
                val reply = operationClient.exchange(connection, encoded)
                ensureActive(session, token)
                coordinator.guard(CommandContext("read", snapshot.revision, token) { generation.get() == token }) {}
                publish(ConversationPhase.VALIDATING)
                if(reply is ConversationReply.Final) {
                    synchronized(lock) { history += ConversationMessage("assistant", redactor.text(reply.text)) }
                    publish(ConversationPhase.COMPLETED, receipts = turnReceipts.toList())
                    return
                }
                reply as ConversationReply.ToolBatch
                batch = reply.calls
                require(batch.isNotEmpty() && batch.size <= 8 && batch.map { it.id }.distinct().size == batch.size && operationCount + batch.size <= 32) { "ai.turn_limit" }
                batch.forEach { call ->
                    executor.registry.validate(call)
                    val old = ledger[call.id]
                    require(old == null || old.first == argumentDigest(call)) { "ai.duplicate_call_mismatch" }
                }
                synchronized(lock) { history += ConversationMessage("assistant", redactor.text(reply.text), batch.map { it.copy(arguments = redactor.redact(it.arguments) as AiJson.Obj) }, blocks = reply.blocks.map { redactAssistantBlock(it, redactor) }) }
                paired = false
                val results = mutableListOf<ToolReceipt>()
                for((index, call) in batch.withIndex()) {
                    ensureActive(session, token)
                    publish(ConversationPhase.EXECUTING)
                    if(executor.registry.validate(call).write) foregroundHost(session, token)
                    val cached = ledger[call.id]
                    val command = CommandContext(UUID.randomUUID().toString(), snapshot.revision, token, mayCommit = { foreground.hasHost }) { generation.get() == token && state.value.sessionId == session }
                    inFlight = call to command
                    var receipt = cached?.second ?: executor.execute(call, ToolExecutionContext(session, turnId, token, command))
                    receipt = receipt.copy(value = redactor.redact(receipt.value), error = receipt.error?.copy(fieldErrors = receipt.error.fieldErrors.mapValues { redactor.text(it.value) }))
                    inFlight = null
                    results += receipt
                    turnReceipts += receipt
                    ledger[call.id] = argumentDigest(call) to receipt
                    operationCount++
                    publish(ConversationPhase.EXECUTING, receipts = turnReceipts.toList())
                    val failed = receipt.error != null || receipt.status in setOf(ToolStatus.FAILED, ToolStatus.UNKNOWN, ToolStatus.CANCELLED, ToolStatus.DENIED)
                    if(failed) {
                        batch.drop(index+1).forEach { remaining -> results += ToolReceipt(UUID.randomUUID().toString(), remaining.id, ToolStatus.SKIPPED, error = ApiError(ErrorCode.SYSTEM_RESTRICTED, "ai.previous_tool_failed")) }
                        synchronized(lock) { history += ConversationMessage("tool", "", receipts = results) }
                        paired = true
                        publish(ConversationPhase.FAILED, receipt.error ?: ApiError(ErrorCode.INTERNAL, "ai.tool_failed"), turnReceipts.toList())
                        return
                    }
                    val expected = coordinator.committed(command.operationId) ?: receipt.commitRevision ?: snapshot.revision
                    if(cached == null && coordinator.capture() != expected) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "business.changed"))
                    snapshot = capture()
                    if(cached == null && snapshot.revision != expected) throw BackendFailure(ApiError(ErrorCode.CONFLICT, "business.changed"))
                    val postHistory = synchronized(lock) { history.toList() } + ConversationMessage("tool", "", receipts = results + batch.drop(index+1).map { ToolReceipt("pending", it.id, ToolStatus.SKIPPED) })
                    ContextBudget.check(operationClient.encode(connection, ConversationRequest(snapshot.json(), postHistory, executor.registry.definitions, configuration.conversationBudget.output,configuration.conversationCompatibility)), settings().conversationBudget)
                }
                synchronized(lock) { history += ConversationMessage("tool", "", receipts = results) }
                paired = true
            }
            throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED, "ai.turn_limit"))
        } catch(e: Exception) {
            val running = inFlight
            if(running != null && turnReceipts.none { it.toolCallId == running.first.id }) {
                val committed = coordinator.committed(running.second.operationId)
                turnReceipts += ToolReceipt(running.second.operationId, running.first.id, if(committed != null) ToolStatus.COMMITTED else if(e is CancellationException) ToolStatus.CANCELLED else ToolStatus.UNKNOWN, commitRevision = committed)
            }
            if(!paired) {
                val currentIds = batch.map { it.id }.toSet()
                val receipts = batch.map { call -> turnReceipts.lastOrNull { it.toolCallId == call.id } ?: ToolReceipt("skipped", call.id, ToolStatus.SKIPPED) }
                synchronized(lock) { history += ConversationMessage("tool", "", receipts = receipts.filter { it.toolCallId in currentIds }) }
            }
            if(connectionChanged) {
                val newKeys = runCatching { settings().let { listOf(it.analysis.apiKey, it.transcription.apiKey) } }.getOrDefault(emptyList())
                val sanitizer = SecretRedactor(newKeys)
                synchronized(lock) { history.clear() }
                for(i in turnReceipts.indices) turnReceipts[i] = turnReceipts[i].copy(value = sanitizer.redact(turnReceipts[i].value))
            }
            val error = when {
                connectionChanged -> ApiError(ErrorCode.CONFLICT, "ai.connection_changed")
                e is CancellationException -> ApiError(ErrorCode.SYSTEM_RESTRICTED, "ai.cancelled")
                e is BackendFailure -> e.error
                e is IllegalArgumentException -> ApiError(ErrorCode.VALIDATION, "ai.invalid_tool_response")
                else -> ApiError(ErrorCode.IO_FAILURE, "ai.conversation_failed", retryable = true)
            }
            publish(if(e is CancellationException || connectionChanged) ConversationPhase.STOPPED else ConversationPhase.FAILED, error, turnReceipts.toList())
        } finally { client = null }
    }
    private suspend fun capture(): AiBusinessSnapshot { publish(ConversationPhase.CAPTURING); return snapshots.captureComplete().requiredValue() }
    private suspend fun foregroundHost(session: String, token: Long) {
        while(!foreground.hasHost) { ensureActive(session, token); publish(ConversationPhase.WAITING_FOREGROUND); delay(100) }
    }
    private fun ensureActive(session: String, token: Long) {
        if (fingerprint.isNotEmpty() && connectionFingerprint(settings()) != fingerprint) connectionChanged = true
        if(generation.get() != token || state.value.sessionId != session || connectionChanged) throw CancellationException("ai.cancelled")
    }
    override suspend fun stop(expectedSessionId: String): ApiResult<Unit> {
        val job = synchronized(lock) {
            if(state.value.sessionId != expectedSessionId) return failed(ErrorCode.CONFLICT, "ai.session_changed")
            generation.incrementAndGet(); client?.cancel(); foreground.invalidate()
            if(state.value.busy) state.value = state.value.copy(phase = ConversationPhase.STOPPING)
            turnJob
        }
        job?.cancelAndJoin()
        if(state.value.busy) publish(ConversationPhase.STOPPED)
        return ApiResult.Success(Unit)
    }
    override suspend fun newSession(expectedSessionId: String): ApiResult<Unit> {
        val stopped = stop(expectedSessionId)
        if(stopped is ApiResult.Failure) return stopped
        synchronized(lock) { history.clear(); resources.reset(); fingerprint = ""; state.value = ConversationState(UUID.randomUUID().toString()) }
        return ApiResult.Success(Unit)
    }
    override fun close() { generation.incrementAndGet(); client?.cancel(); foreground.invalidate(); resources.reset(); scope.cancel() }
    private fun failed(code: ErrorCode, key: String) = ApiResult.Failure(ApiError(code, key))
    private fun argumentDigest(call: ConversationToolCall) = digest(call.name + ":" + call.arguments.encode())
    private fun connectionFingerprint(configuration: RecordingSettings): String {
        val value=configuration.analysis
        return digest("${value.protocol}\n${value.url}\n${value.model}\n${value.apiKey}\n${configuration.conversationCompatibility}")
    }
}
