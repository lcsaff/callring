package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.application.ports.BackendFailure
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Protocol batches are validated whole; the local execution window never truncates their tail. */
class ToolBatchEngine(
    private val api:()->CallringUiApi,private val executor:ToolExecutor,private val queries:QueryService,
    private val coordinator:BusinessRevisionCoordinator,private val session:String,private val turn:String,
    private var generation:Long,private var active:()->Boolean,
    private var awaitForeground:suspend()->Unit={},private var onReceipt:(ToolReceipt)->Unit={},
) {
    var budget=TurnBudget();private set
    val ledger=StepLedger{api().checkNumber(it).normalized}
    val planner=TaskPlanner(executor.registry.catalog)
    private val scopes=OperationScopePlanner(queries,coordinator,executor.registry.catalog)
    private val verifier=ReceiptVerifier(queries,executor.registry.catalog)
    private val repairable=mutableListOf<LedgerStep>()
    private val allReceipts=mutableListOf<ToolReceipt>()
    private val batchChildren=mutableMapOf<String,List<String>>()
    val receipts:List<ToolReceipt> get()=allReceipts.toList()
    fun groupedReceipts(callId:String):List<ToolReceipt> = batchChildren[callId].orEmpty().map{id->allReceipts.lastOrNull{it.toolCallId==id}?:ToolReceipt("skipped:$id",id,ToolStatus.SKIPPED)}
    fun pendingTasks()=planner.forUser(turn).filterNot{planner.satisfied(it,ledger.steps)}.map{it.id}
    private var reconcile=false
    fun resume(newGeneration:Long,newActive:()->Boolean,newAwaitForeground:suspend()->Unit=awaitForeground,newOnReceipt:(ToolReceipt)->Unit=onReceipt) {
        if(ledger.steps.any{it.state==StepState.UNKNOWN})throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.unknown_commit_requires_review"))
        generation=newGeneration;active=newActive;awaitForeground=newAwaitForeground;onReceipt=newOnReceipt;budget=TurnBudget()
        queries.clearObservations();scopes.resetForContinuation();reconcile=true
        ledger.steps.filter{it.state in setOf(StepState.PREPARED,StepState.SKIPPED)}.forEach{it.state=StepState.PREPARED;it.receipt=null}
    }
    val complete:Boolean get() {
        val plans=planner.forUser(turn)
        return plans.all{planner.satisfied(it,ledger.steps)}&&ledger.steps.none{it.state in setOf(StepState.UNKNOWN,StepState.STARTED,StepState.COMMITTED)}&&
            (plans.isNotEmpty()||ledger.steps.none{it.state in setOf(StepState.FAILED,StepState.CANCELLED,StepState.DENIED,StepState.SKIPPED)})
    }
    fun summary():AiJson=aiObject("plans" to AiJson.Arr(planner.forUser(turn).map{p->AiJson.Obj(p.json().fields+mapOf("verifiedCount" to aiNumber(planner.verifiedCount(p,ledger.steps).toLong()),"satisfied" to aiBool(planner.satisfied(p,ledger.steps))))}),"verifiedWrites" to aiNumber(ledger.steps.count{it.write&&it.state==StepState.VERIFIED}.toLong()),"committedUnverified" to aiNumber(ledger.steps.count{it.state==StepState.COMMITTED}.toLong()),"unknown" to aiNumber(ledger.steps.count{it.state==StepState.UNKNOWN}.toLong()),"complete" to aiBool(complete),"work" to aiNumber(budget.work.toLong()))
    private suspend fun <T> metered(block:suspend()->T):T {val before=queries.sourceQueries.get();try{return block()}finally{budget.consume((queries.sourceQueries.get()-before).toInt())}}
    private fun failure(call:ConversationToolCall,error:ApiError,status:ToolStatus=ToolStatus.FAILED)=ToolReceipt(UUID.randomUUID().toString(),call.id,status,error=error)
    private fun report(receipt:ToolReceipt):ToolReceipt {allReceipts+=receipt;onReceipt(receipt);return receipt}
    private fun target(call:ConversationToolCall):Map<String,AiJson> = call.arguments.fields.filterKeys{it in setOf("number","id","callId","widgetId","date","slot","from","to","taskId")}
    private fun isRepairable(error:ApiError?)=error?.code==ErrorCode.VALIDATION&&error.message !in setOf("ai.duplicate_call_mismatch","ai.repair_target_changed","ai.task_target_changed","ai.task_required","ai.profile_exists")
    private data class Prepared(val call:ConversationToolCall,val step:LedgerStep,val definition:OperationDefinition?,var expected:Map<String,Long> = emptyMap(),var error:ApiError?=null)
    suspend fun execute(calls:List<ConversationToolCall>,enabledNames:Set<String>?=null):List<ToolReceipt> {
        if(reconcile){
            reconcile=false
            ledger.steps.filter{it.state==StepState.COMMITTED&&it.receipt?.status==ToolStatus.COMMITTED}.forEach{step->
                val operation=executor.registry.definitions.single{it.name==step.call.name}.id
                val receipt=metered{verifier.verify(operation,step.call,step.receipt!!,adoption=step.adoption)}
                ledger.finish(step,receipt);report(receipt)
            }
        }
        if(calls.isEmpty()||calls.size>32||calls.map{it.id}.distinct().size!=calls.size)throw BackendFailure(ApiError(ErrorCode.VALIDATION,"ai.invalid_tool_response"))
        val expanded=mutableListOf<ConversationToolCall>()
        val groups=linkedMapOf<String,List<String>>()
        for(call in calls) {
            if(executor.registry.catalog.find(call.name)?.id!=OperationId.CALLERS_SAVE_PROFILES||(enabledNames!=null&&call.name !in enabledNames)){expanded+=call;continue}
            try {
                executor.registry.validate(call)
                val items=(call.arguments["items"] as AiJson.Arr).values
                require(items.size in 1..32)
                val task=planner.get(call.arguments.s("taskId"));require(task.kind==TaskKind.CREATE_PROFILES)
                val children=items.mapIndexed{i,item->ConversationToolCall("item:${digest(call.id).take(32)}:$i",executor.registry.catalog[OperationId.CALLERS_SAVE_PROFILE].name,AiJson.Obj((item as AiJson.Obj).fields+mapOf("taskId" to aiString(task.id))))}
                groups[call.id]=children.map{it.id};batchChildren[call.id]=children.map{it.id};expanded+=children
            } catch(e:Exception){
                if(e is CancellationException)throw e
                expanded+=call
            }
        }
        if(expanded.size>64)throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.turn_limit"))
        val pending=mutableListOf<Prepared>()
        val writes=expanded.count{c->executor.registry.definitions.any{it.name==c.name&&it.write}}
        for(original in expanded) {
            var call=original
            val definition=executor.registry.definitions.singleOrNull{it.name==call.name}
            val write=definition?.write==true
            val explicitTask=call.arguments.optS("taskId")
            val task=if(write&&explicitTask==null&&writes==1)planner.prepare(turn,if(definition.id==OperationId.SETTINGS_SAVE_APPEARANCE)TaskKind.SET_APPEARANCE else TaskKind.OPERATIONS,1)else null
            val taskId=explicitTask?:task?.id?:"read:$turn"
            val old=repairable.lastOrNull{s->!s.started&&(s.call.name==call.name||executor.registry.definitions.none{it.name==s.call.name})&&target(s.call).all{(k,v)->call.arguments[k]==v}}
            val step=if(old!=null&&(ledger.signature(call)!=old.signature||old.call.id!=call.id)){
                if(!budget.repair(old.id))throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.repair_limit"))
                repairable.remove(old);ledger.repair(old,call,write,taskId)
            }else ledger.prepare(taskId,call,write)
            val p=Prepared(call,step,definition);pending+=p
            if(step.receipt!=null)continue
            try {
                executor.registry.validate(call)
                if(enabledNames!=null&&call.name !in enabledNames&&groups.values.none{call.id in it})throw BackendFailure(ApiError(ErrorCode.VALIDATION,"ai.tool_not_enabled",aiFailure=ConversationFailure(AiFailureKind.NOT_ENABLED,"NOT_ENABLED")))
                if(write&&explicitTask==null&&writes>1)throw BackendFailure(ApiError(ErrorCode.VALIDATION,"ai.task_required"))
                if(write&&explicitTask!=null)planner.get(explicitTask)
                if(definition?.id in setOf(OperationId.CALLERS_SAVE_PROFILE,OperationId.CALLERS_SET_WHITELISTED)) {
                    val checked=api().checkNumber(call.arguments.s("number"))
                    if(!checked.validProfileNumber)throw BackendFailure(ApiError(ErrorCode.VALIDATION,"number.invalid",fieldErrors=mapOf("$.number" to "number.valid_profile_required")))
                    call=call.copy(arguments=AiJson.Obj(call.arguments.fields+("number" to aiString(checked.normalized))));p.step.call=call
                }
                if(write) {
                    if(definition.id==OperationId.CALLERS_SAVE_PROFILES)throw BackendFailure(ApiError(ErrorCode.VALIDATION,"ai.invalid_profile_batch"))
                    p.expected=metered{scopes.prepare(call,definition.id)}
                    if(definition.id==OperationId.RECORDINGS_ADOPT_IDENTITY)step.adoption=metered{verifier.prepareAdoption(call)}
                    step.effectScopes=p.expected.keys.filter{definition.id !in setOf(OperationId.CALLERS_SAVE_PROFILE,OperationId.CALLERS_SET_WHITELISTED)||it.startsWith("profile:")}.toSet()
                    if(ledger.steps.any{prior->prior!==step&&prior.effectScopes.any{it in step.effectScopes}&&
                        (prior.state==StepState.UNKNOWN||prior.receipt?.verification==VerificationStatus.COMMITTED_UNVERIFIED||
                            prior.receipt?.status in setOf(ToolStatus.DENIED,ToolStatus.CANCELLED)||prior.receipt?.error?.code==ErrorCode.CONFLICT)})
                        throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.commit_requires_review"))
                    if(explicitTask!=null&&planner.get(explicitTask).kind==TaskKind.CREATE_PROFILES) {
                        if(definition.id!=OperationId.CALLERS_SAVE_PROFILE)throw BackendFailure(ApiError(ErrorCode.VALIDATION,"ai.task_operation_mismatch"))
                        val disclosed=queries.snapshotObservations()
                        val page=try{metered{queries.query(QuerySpec(AiDomain.D01,filter=QueryFilter(number=call.arguments.s("number")),limit=1)).requiredValue()}}finally{queries.restoreObservations(disclosed)}
                        if(page.items.any{sameValue(it["stored"],aiBool(true))})throw BackendFailure(ApiError(ErrorCode.CONFLICT,"ai.profile_exists"))
                    }
                }
            } catch(e:CancellationException){throw e}
            catch(e:BackendFailure){p.error=e.error}
            catch(_:IllegalArgumentException){p.error=ApiError(ErrorCode.VALIDATION,"ai.invalid_tool_arguments")}
        }
        // Explicit dependencies are protocol IDs; unknown/cyclic graphs reject before any write.
        val byCall=pending.associateBy{it.call.id}
        pending.forEach{p->
            val deps=(p.call.arguments["dependsOn"] as? AiJson.Arr)?.values?.map{it.string()}?:emptyList()
            if(deps.any{it !in byCall&&ledger.byCall(it)==null})p.error=ApiError(ErrorCode.VALIDATION,"ai.unknown_dependency")
            p.step.dependencies+=deps
        }
        pending.forEachIndexed{i,p->pending.take(i).filter{prior->prior.step!==p.step&&prior.definition?.write==true&&p.expected.keys.any{it in prior.expected}}.forEach{p.step.dependencies+=it.call.id}}
        fun cycle(id:String,visiting:MutableSet<String>,done:MutableSet<String>):Boolean {
            if(id in done)return false
            if(!visiting.add(id))return true
            if(byCall[id]?.step?.dependencies?.any{cycle(it,visiting,done)}==true)return true
            visiting.remove(id);done+=id;return false
        }
        if(pending.any{cycle(it.call.id,mutableSetOf(),mutableSetOf())})throw BackendFailure(ApiError(ErrorCode.VALIDATION,"ai.dependency_cycle"))
        planner.forUser(turn).forEach{plan->
            val possible=ledger.steps.count{it.taskId==plan.id&&it.write&&it.started}
            val proposed=pending.filter{it.step.taskId==plan.id&&it.definition?.write==true&&it.step.receipt==null&&it.error==null}.distinctBy{it.step.id}
            if(possible+proposed.size>plan.expectedCount)proposed.forEach{it.error=ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.task_count_exceeded")}
        }
        // A malformed item may only be bypassed when its resource target is demonstrably different.
        val invalid=pending.filter{it.error!=null}
        val validWrites=pending.count{it.definition?.write==true&&it.error==null&&it.step.receipt==null}
        if(!budget.canStartWrites(validWrites))throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.turn_limit"))
        val results=linkedMapOf<String,ToolReceipt>()
        val remaining=pending.toMutableList()
        while(remaining.isNotEmpty()) {
            val p=remaining.firstOrNull{it.step.dependencies.none{d->d in remaining.map{x->x.call.id}}}?:error("dependency ordering")
            remaining.remove(p)
            val call=p.call;val step=p.step
            val cached=step.receipt
            if(cached!=null){results[call.id]=report(cached.copy(toolCallId=call.id));continue}
            val blocked=step.dependencies.any{id->val receipt=results[id]?:ledger.byCall(id)?.receipt;receipt==null||receipt.error!=null||receipt.status in setOf(ToolStatus.FAILED,ToolStatus.SKIPPED,ToolStatus.UNKNOWN,ToolStatus.DENIED,ToolStatus.CANCELLED)}
            val uncertain=invalid.any{bad->bad!==p&&(target(bad.call).isEmpty()||target(p.call).isEmpty()||target(bad.call)==target(p.call))}
            var receipt:ToolReceipt
            if(p.error!=null)receipt=failure(call,p.error!!)
            else if(blocked||uncertain)receipt=failure(call,ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.dependency_failed"),ToolStatus.SKIPPED)
            else {
                if(!active())throw CancellationException("ai.cancelled")
                val operation=p.definition!!.id
                if(operation==OperationId.TASK_PREPARE) {
                    budget.consume()
                    receipt=try{val plan=planner.prepare(turn,TaskKind.valueOf(call.arguments.s("kind")),call.arguments.l("expectedCount").exactInt(),(call.arguments["targets"] as? AiJson.Arr)?.values?.map{it.string()}?.toSet()?:emptySet());ToolReceipt(step.id,call.id,ToolStatus.READ,plan.json())}catch(_:IllegalArgumentException){failure(call,ApiError(ErrorCode.VALIDATION,"ai.task_target_changed"))}
                } else {
                    if(p.definition.write)awaitForeground()
                    val command=CommandContext(step.id,coordinator.capture(),generation,mayCommit={active()&&executor.foregroundReady},expectedScopes=if(p.definition.write)scopes.afterOwnCommits(p.expected)else null){active()}
                    ledger.start(step)
                    budget.consume()
                    try {
                        receipt=metered{executor.execute(step.call,ToolExecutionContext(session,turn,generation,command))}
                        if(p.definition.write) {
                            receipt=metered{verifier.verify(operation,step.call,receipt,adoption=step.adoption)}
                            if(receipt.commitRevision!=null)scopes.committed(command.operationId,p.expected.keys)
                        }
                    } catch(e:Exception){
                        val committed=coordinator.committed(command.operationId)
                        val cancelled=ToolReceipt(command.operationId,call.id,if(committed!=null)ToolStatus.COMMITTED else if(e is CancellationException)ToolStatus.CANCELLED else ToolStatus.UNKNOWN,commitRevision=committed,verification=if(committed!=null)VerificationStatus.COMMITTED_UNVERIFIED else VerificationStatus.NOT_REQUIRED)
                        ledger.finish(step,cancelled);report(cancelled);throw e
                    }
                }
            }
            ledger.finish(step,receipt)
            if(isRepairable(receipt.error))repairable+=step
            results[call.id]=report(receipt)
        }
        return calls.map{call->groups[call.id]?.let{ids->
            val children=ids.map{results.getValue(it)}
            val verified=children.count{it.verification==VerificationStatus.VERIFIED}
            ToolReceipt("batch:${call.id}",call.id,if(verified==children.size)ToolStatus.COMMITTED else ToolStatus.READ,aiObject("items" to AiJson.Arr(children.map{it.json()}),"verifiedCount" to aiNumber(verified.toLong()),"expectedCount" to aiNumber(children.size.toLong()),"complete" to aiBool(verified==children.size)),verification=if(verified==children.size)VerificationStatus.VERIFIED else VerificationStatus.PENDING)
        }?:results.getValue(call.id)}
    }
}
fun ToolReceipt.json()=aiObject("operationId" to aiString(operationId),"toolCallId" to aiString(toolCallId),"status" to aiString(status.name),"value" to value,"verification" to aiString(verification.name),"noOp" to aiBool(noOp),"verificationEvidence" to verificationEvidence,"error" to (error?.let{aiObject("code" to aiString(it.code.name),"message" to aiString(it.message),"fieldErrors" to AiJson.Obj(it.fieldErrors.mapValues{entry->aiString(entry.value)}),"retryable" to aiBool(it.retryable))}?:AiJson.Null))
