package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.application.recording.model.*
import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.application.ports.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ConversationService(
    private val api:()->CallringUiApi,
    private val queries:QueryService,
    private val settings:()->RecordingSettings,
    private val coordinator:BusinessRevisionCoordinator,
    private val clientFactory:()->ConversationClient,
    private val scope:CoroutineScope=CoroutineScope(SupervisorJob()+Dispatchers.Default),
    val resources:ForegroundResourceRegistry=ForegroundResourceRegistry(),
    documentWriteVerified:suspend(DocumentRef)->Boolean={false},
):ConversationApi,AutoCloseable {
    private val lock=Any()
    override val state=MutableStateFlow(ConversationState(UUID.randomUUID().toString()))
    override val foreground=ForegroundActionService(checkCommand={command->command==null||coordinator.committed(command.operationId)!=null||runCatching{coordinator.guard(command.copy(mayCommit={true})) {}}.isSuccess})
    private val generation=AtomicLong()
    private val context=ContextAssembler()
    private var turnJob:Job?=null
    @Volatile private var client:ConversationClient?=null
    @Volatile private var connectionChanged=false
    @Volatile private var fingerprint=""
    private data class Continuation(val engine:ToolBatchEngine,val fingerprint:String,val session:String)
    private var continuation:Continuation?=null
    private var phaseClock=0L
    private val phaseSpans=mutableListOf<ConversationPhaseSpan>()
    private var phaseTraceComplete=true
    private fun phaseState(phase:ConversationPhase):ConversationState {
        if(phaseClock==0L)return state.value.copy(phase=phase)
        val now=((System.nanoTime()-phaseClock)/1_000_000).coerceAtLeast(0)
        phaseSpans.lastOrNull()?.let{last->phaseSpans[phaseSpans.lastIndex]=last.copy(endMillis=now)}
        if(phaseSpans.lastOrNull()?.phase!=phase){
            if(phaseSpans.size>=256){phaseTraceComplete=false;phaseSpans.removeAt(phaseSpans.lastIndex)}
            phaseSpans+=ConversationPhaseSpan(phase,now,now)
        }
        return state.value.copy(phase=phase,phaseSpans=phaseSpans.toList(),diagnosticsComplete=phaseTraceComplete)
    }
    private val executor=ToolExecutor(api,coordinator,foreground,resources,queries=queries,documentWriteVerified=documentWriteVerified)
    private val monitor=scope.launch {
        coordinator.changes.collect {
            if(state.value.busy&&fingerprint.isNotEmpty()&&runCatching{connectionFingerprint(settings())!=fingerprint}.getOrDefault(true)) {
                connectionChanged=true;client?.cancel();foreground.invalidate();turnJob?.cancel()
            }
        }
    }
    init {scope.launch{foreground.pending.collect{pending->synchronized(lock){if(state.value.busy&&state.value.phase in setOf(ConversationPhase.EXECUTING,ConversationPhase.WAITING_FOREGROUND))state.value=phaseState(if(pending!=null)ConversationPhase.WAITING_FOREGROUND else ConversationPhase.EXECUTING)}}}}
    override suspend fun send(expectedSessionId:String,text:String)=enqueue(expectedSessionId,text,null)
    override suspend fun sendTo(expectedSessionId:String,text:String,disclosedTarget:AiConnectionView):ApiResult<Unit> {
        val current=runCatching{settings()}.getOrElse{return failed(ErrorCode.STORAGE_FAILURE,"ai.settings_storage_failed")}
        if(!matches(disclosedTarget,current))return failed(ErrorCode.CONFLICT,"ai.connection_changed")
        return enqueue(expectedSessionId,text,disclosedTarget)
    }
    override suspend fun continueTask(expectedSessionId:String,taskId:String,disclosedTarget:AiConnectionView):ApiResult<Unit> = synchronized(lock) {
        val saved=continuation?:return failed(ErrorCode.NOT_FOUND,"ai.task_reference_expired")
        val configuration=settings()
        if(saved.session!=expectedSessionId||saved.fingerprint!=connectionFingerprint(configuration)||!matches(disclosedTarget,configuration))return failed(ErrorCode.CONFLICT,"ai.connection_changed")
        if(taskId !in saved.engine.pendingTasks())return failed(ErrorCode.NOT_FOUND,"ai.task_reference_expired")
        if(saved.engine.ledger.steps.any{it.state==StepState.UNKNOWN})return failed(ErrorCode.SYSTEM_RESTRICTED,"ai.unknown_commit_requires_review")
        enqueue(expectedSessionId,"Continue task $taskId with the original count and committed steps.",disclosedTarget,saved.engine)
    }
    private fun matches(target:AiConnectionView,configuration:RecordingSettings):Boolean {
        val c=configuration.analysis
        return target.protocol==c.protocol&&target.url==c.url&&target.model==c.model&&target.thinkingMode==c.thinkingMode&&target.resolvedProfile==resolveConversationProfile(c,configuration.conversationCompatibility)
    }
    private fun enqueue(expectedSessionId:String,text:String,target:AiConnectionView?,resuming:ToolBatchEngine?=null):ApiResult<Unit> {
        if(text.isBlank()||text.toByteArray(Charsets.UTF_8).size>32768)return failed(ErrorCode.VALIDATION,"ai.message_invalid")
        synchronized(lock) {
            if(expectedSessionId!=state.value.sessionId)return failed(ErrorCode.CONFLICT,"ai.session_changed")
            if(state.value.busy)return failed(ErrorCode.CONFLICT,"ai.busy")
            val token=generation.incrementAndGet();connectionChanged=false
            if(resuming==null)continuation=null
            phaseClock=System.nanoTime();phaseSpans.clear();phaseSpans+=ConversationPhaseSpan(ConversationPhase.PREPARING,0,0);phaseTraceComplete=true
            state.value=state.value.copy(phase=ConversationPhase.PREPARING,error=null,receipts=emptyList(),exchanges=emptyList(),sourceQueries=0,workCount=0,outcome=AiJson.Null,phaseSpans=phaseSpans.toList(),diagnosticsComplete=true)
            turnJob=scope.launch(start=CoroutineStart.LAZY){runTurn(expectedSessionId,token,text,target,resuming)};turnJob!!.start()
        }
        return ApiResult.Success(Unit)
    }
    private fun publish(phase:ConversationPhase,error:ApiError?=null,receipts:List<ToolReceipt>?=null,estimate:Long?=null,engine:ToolBatchEngine?=null,sourceBase:Long=queries.sourceQueries.get())=synchronized(lock) {
        state.value=phaseState(phase).copy(messages=context.uiMessages(),error=error,receipts=receipts?:state.value.receipts,budgetBytes=estimate?:state.value.budgetBytes,sourceQueries=queries.sourceQueries.get()-sourceBase,workCount=engine?.budget?.work?:state.value.workCount,outcome=engine?.summary()?:state.value.outcome,continuableTasks=if(phase in setOf(ConversationPhase.PARTIAL,ConversationPhase.FAILED,ConversationPhase.STOPPED))engine?.pendingTasks().orEmpty()else emptyList())
    }
    private fun sanitize(receipt:ToolReceipt,r:SecretRedactor)=receipt.copy(value=r.redact(receipt.value),verificationEvidence=r.redact(receipt.verificationEvidence),error=receipt.error?.let{it.copy(message=r.text(it.message),fieldErrors=it.fieldErrors.mapValues{entry->r.text(entry.value)})})
    private suspend fun runTurn(session:String,token:Long,text:String,disclosedTarget:AiConnectionView?,resuming:ToolBatchEngine?) {
        val turn=UUID.randomUUID().toString()
        val sourceBase=queries.sourceQueries.get()
        var engine:ToolBatchEngine?=null
        var activeBatch=emptyList<ConversationToolCall>()
        var paired=true
        var sanitizer=SecretRedactor(emptyList())
        val exchanges=mutableListOf<ExchangeMetrics>()
        var completionNudges=0
        try {
            val configuration=settings()
            if(disclosedTarget!=null&&!matches(disclosedTarget,configuration))throw BackendFailure(ApiError(ErrorCode.CONFLICT,"ai.connection_changed"))
            configuration.analysis.validate()
            val current=connectionFingerprint(configuration)
            if(fingerprint.isNotEmpty()&&fingerprint!=current)context.clear()
            fingerprint=current
            sanitizer=SecretRedactor(listOf(configuration.analysis.apiKey,configuration.transcription.apiKey))
            queries.clearObservations()
            context.begin(ConversationMessage("user",sanitizer.text(text),presentationKey=if(resuming!=null)"conversation.continue"else null))
            synchronized(lock){state.value=state.value.copy(target=sanitizer.text("${configuration.analysis.url} · ${configuration.analysis.model} · ${resolveConversationProfile(configuration.analysis,configuration.conversationCompatibility)}"))}
            val operationClient=clientFactory().also{client=it}
            val enabled=linkedSetOf("discover_tools")
            fun previewBudget(results:List<ToolReceipt>,reserved:Int=0):Long {
                val request=ConversationRequest(messages=context.requestMessages()+ConversationMessage("tool","",receipts=results),tools=executor.registry.allDefinitions.filter{it.name in enabled},output=configuration.conversationBudget.output,compatibility=configuration.conversationCompatibility,context=engine?.summary()?:AiJson.Null)
                return ContextBudget.checkReserved(operationClient.encode(configuration.analysis,request),reserved,configuration.conversationBudget)
            }
            val observer:(ToolReceipt)->Unit={receipt->
                val list=state.value.receipts.filterNot{it.operationId==receipt.operationId}+sanitize(receipt,sanitizer).uiProjection()
                publish(ConversationPhase.EXECUTING,receipts=list,sourceBase=sourceBase)
                if(!paired&&activeBatch.isNotEmpty())previewBudget(activeBatch.map{call->
                    engine?.receipts?.lastOrNull{it.toolCallId==call.id}?:if(call.name=="callers_saveProfiles")ToolReceipt("batch:${call.id}",call.id,ToolStatus.READ,aiObject("items" to AiJson.Arr(engine?.groupedReceipts(call.id)?.map{sanitize(it,sanitizer).json()}?:emptyList())))else ToolReceipt("pending",call.id,ToolStatus.SKIPPED)
                }.map{sanitize(it,sanitizer)})
            }
            val active={generation.get()==token&&state.value.sessionId==session&&!connectionChanged}
            engine=resuming?.also{it.resume(token,active,{foregroundHost(session,token)},observer)}?:ToolBatchEngine(api,executor,queries,coordinator,session,turn,token,active,awaitForeground={foregroundHost(session,token)},onReceipt=observer)
            if(resuming!=null)synchronized(lock){state.value=state.value.copy(receipts=engine.receipts.distinctBy{it.operationId}.map{sanitize(it,sanitizer).uiProjection()})}
            while(true) {
                ensureActive(session,token);engine.budget.request()
                publish(ConversationPhase.BUDGETING,engine=engine,sourceBase=sourceBase)
                val request=ConversationRequest(messages=context.requestMessages(),tools=executor.registry.allDefinitions.filter{it.name in enabled},output=configuration.conversationBudget.output,compatibility=configuration.conversationCompatibility,context=aiObject("taskState" to engine.summary(),"sourcePolicy" to aiString("No business snapshot. Discover and query only data required by the current request. Incomplete pages and pending receipts are not completion.")))
                val encoded=operationClient.encode(configuration.analysis,request)
                val estimate=ContextBudget.check(encoded,configuration.conversationBudget)
                publish(ConversationPhase.REQUESTING,estimate=estimate,engine=engine,sourceBase=sourceBase)
                context.markSent()
                val reply=try {operationClient.exchange(configuration.analysis,encoded)}finally{
                    operationClient.lastExchange?.let{exchanges+=it}
                    synchronized(lock){state.value=state.value.copy(exchanges=exchanges.toList())}
                }
                ensureActive(session,token)
                if(reply is ConversationReply.Final) {
                    val done=engine.complete
                    if(!done&&completionNudges==0&&engine.pendingTasks().isNotEmpty()&&engine.ledger.steps.any{it.write&&it.state==StepState.VERIFIED}&&engine.ledger.steps.all{it.state in setOf(StepState.VERIFIED,StepState.PREPARED,StepState.SKIPPED)}&&engine.budget.requests<16&&engine.budget.canStartWrites(1)) {
                        completionNudges++
                        context.appendProtocol(ConversationMessage("assistant",sanitizer.text(reply.text)))
                        context.appendProtocol(ConversationMessage("user","Local task ledger shows missing unstarted work. Continue the original plan only, without replaying committed steps or changing the count. If blocked, explain the actual limitation.\n"+engine.summary().encode()))
                        publish(ConversationPhase.REPAIRING,engine=engine,sourceBase=sourceBase)
                        continue
                    }
                    val finalText=if(done)sanitizer.text(reply.text)else ""
                    context.append(ConversationMessage("assistant",finalText,presentationKey=if(done)null else "conversation.partial"))
                    context.finish(engine.summary())
                    synchronized(lock){continuation=if(!done&&engine.pendingTasks().isNotEmpty())Continuation(engine,fingerprint,session)else null}
                    val hasEffects=engine.receipts.any{it.status in setOf(ToolStatus.COMMITTED,ToolStatus.QUEUED,ToolStatus.LAUNCHED,ToolStatus.UNKNOWN)}
                    publish(if(done)ConversationPhase.COMPLETED else if(hasEffects)ConversationPhase.PARTIAL else ConversationPhase.FAILED,if(done)null else engine.receipts.lastOrNull{it.error!=null}?.error?:ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.task_incomplete"),engine=engine,sourceBase=sourceBase)
                    return
                }
                reply as ConversationReply.ToolBatch
                activeBatch=reply.calls
                if(activeBatch.isEmpty()||activeBatch.size>32||activeBatch.map{it.id}.distinct().size!=activeBatch.size)throw BackendFailure(ApiError(ErrorCode.VALIDATION,"ai.invalid_tool_response"))
                context.append(ConversationMessage("assistant",sanitizer.text(reply.text),activeBatch.map{redactToolCall(it,sanitizer)},blocks=reply.blocks.map{redactAssistantBlock(it,sanitizer)}))
                paired=false
                val writeItems=activeBatch.sumOf{call->if(call.name=="callers_saveProfiles")((call.arguments["items"] as? AiJson.Arr)?.values?.size?:1)else if(executor.registry.allDefinitions.any{it.name==call.name&&it.write})1 else 0}
                previewBudget(activeBatch.map{ToolReceipt("pending",it.id,ToolStatus.SKIPPED)},writeItems.coerceAtMost(64)*1024)
                val phase=when{activeBatch.all{it.name=="discover_tools"}->ConversationPhase.DISCOVERING;activeBatch.all{call->executor.registry.allDefinitions.any{it.name==call.name&&!it.write}}->ConversationPhase.READING;else->ConversationPhase.EXECUTING}
                publish(phase,engine=engine,sourceBase=sourceBase)
                val results=engine.execute(activeBatch,enabled).map{sanitize(it,sanitizer)}
                context.append(ConversationMessage("tool","",receipts=results));paired=true
                results.filter{r->activeBatch.any{it.id==r.toolCallId&&it.name=="discover_tools"}&&r.error==null}.forEach{r->
                    ((r.value as? AiJson.Obj)?.get("tools") as? AiJson.Arr)?.values?.forEach{item->val name=(item as AiJson.Obj).s("name");if(executor.registry.allDefinitions.any{it.name==name})enabled+=name}
                }
                publish(if(results.any{it.error?.code==ErrorCode.VALIDATION})ConversationPhase.REPAIRING else ConversationPhase.EXECUTING,engine=engine,sourceBase=sourceBase)
            }
        } catch(e:Exception) {
            if(!paired) {
                val receipts=activeBatch.map{call->
                    engine?.receipts?.lastOrNull{it.toolCallId==call.id}?:if(call.name=="callers_saveProfiles")ToolReceipt("batch:${call.id}",call.id,ToolStatus.READ,aiObject("items" to AiJson.Arr(engine?.groupedReceipts(call.id)?.map{sanitize(it,sanitizer).json()}?:emptyList()),"complete" to aiBool(false)),verification=VerificationStatus.PENDING)else ToolReceipt("skipped",call.id,ToolStatus.SKIPPED,error=ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.turn_stopped"))
                }.map{sanitize(it,sanitizer)}
                context.append(ConversationMessage("tool","",receipts=receipts))
            }
            val error=when {
                connectionChanged->ApiError(ErrorCode.CONFLICT,"ai.connection_changed")
                e is CancellationException->ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.cancelled")
                e is BackendFailure->e.error
                e is IllegalArgumentException->ApiError(ErrorCode.VALIDATION,"ai.invalid_settings")
                else->ApiError(ErrorCode.IO_FAILURE,"ai.conversation_failed",retryable=true)
            }
            if(connectionChanged){context.clear();queries.reset();resources.reset();synchronized(lock){continuation=null}}
            else context.finish(engine?.summary()?:aiObject("complete" to aiBool(false),"error" to aiString(error.message)))
            if(!connectionChanged&&engine!=null&&engine.pendingTasks().isNotEmpty())synchronized(lock){continuation=Continuation(engine,fingerprint,session)}
            val phase=if(e is CancellationException||connectionChanged)ConversationPhase.STOPPED else if(engine?.receipts?.any{it.status in setOf(ToolStatus.COMMITTED,ToolStatus.QUEUED,ToolStatus.LAUNCHED,ToolStatus.UNKNOWN)}==true)ConversationPhase.PARTIAL else ConversationPhase.FAILED
            val retained=engine?.receipts?.map{sanitize(it,sanitizer).uiProjection()}
            publish(phase,error,retained,engine=engine,sourceBase=sourceBase)
        } finally {client=null}
    }
    private suspend fun foregroundHost(session:String,token:Long){while(!foreground.hasHost){ensureActive(session,token);publish(ConversationPhase.WAITING_FOREGROUND);delay(100)}}
    private fun ensureActive(session:String,token:Long) {
        if(fingerprint.isNotEmpty()&&connectionFingerprint(settings())!=fingerprint)connectionChanged=true
        if(generation.get()!=token||state.value.sessionId!=session||connectionChanged)throw CancellationException("ai.cancelled")
    }
    override suspend fun stop(expectedSessionId:String):ApiResult<Unit> {
        val job=synchronized(lock){
            if(state.value.sessionId!=expectedSessionId)return failed(ErrorCode.CONFLICT,"ai.session_changed")
            generation.incrementAndGet();client?.cancel();foreground.invalidate()
            if(state.value.busy)state.value=phaseState(ConversationPhase.STOPPING)
            turnJob
        }
        job?.cancelAndJoin();if(state.value.busy)publish(ConversationPhase.STOPPED)
        return ApiResult.Success(Unit)
    }
    override suspend fun newSession(expectedSessionId:String):ApiResult<Unit> {
        val result=stop(expectedSessionId);if(result is ApiResult.Failure)return result
        synchronized(lock){context.clear();queries.reset();resources.reset();continuation=null;fingerprint="";state.value=ConversationState(UUID.randomUUID().toString())}
        return ApiResult.Success(Unit)
    }
    override fun close(){generation.incrementAndGet();client?.cancel();foreground.invalidate();resources.reset();queries.reset();scope.cancel()}
    private fun failed(code:ErrorCode,message:String)=ApiResult.Failure(ApiError(code,message))
    private fun connectionFingerprint(configuration:RecordingSettings):String {val c=configuration.analysis;return digest("${c.protocol}\n${c.url}\n${c.model}\n${c.apiKey}\n${c.thinkingMode}\n${configuration.conversationCompatibility}")}
}
