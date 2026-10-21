package com.greatwall.callring.application.conversation.execution

import com.greatwall.callring.application.conversation.query.AdoptionExpectation
import com.greatwall.callring.application.conversation.query.digest

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.ports.BackendFailure
import java.util.UUID

enum class StepState { PREPARED, STARTED, COMMITTED, VERIFIED, FAILED, CANCELLED, DENIED, SKIPPED, UNKNOWN }
class LedgerStep(val id:String,var taskId:String,var call:ConversationToolCall,var write:Boolean,var signature:String) {
    var state=StepState.PREPARED
    var receipt:ToolReceipt?=null
    var repairs=0
    var effectScopes:Set<String> = emptySet()
    var adoption:AdoptionExpectation?=null
    val dependencies=linkedSetOf<String>()
    val started get()=state in setOf(StepState.STARTED,StepState.COMMITTED,StepState.VERIFIED,StepState.UNKNOWN)
}
class StepLedger(private val normalizeNumber:(String)->String={it}) {
    private val mutableSteps=mutableListOf<LedgerStep>()
    val steps:List<LedgerStep> get()=mutableSteps.toList()
    private val byId=mutableMapOf<String,Pair<String,LedgerStep>>()
    private val byEffect=mutableMapOf<Pair<String,String>,LedgerStep>()
    fun byCall(id:String):LedgerStep?=byId[id]?.second
    fun signature(call:ConversationToolCall):String {
        fun canonical(v:AiJson,key:String=""):AiJson=when(v){
            is AiJson.Obj->AiJson.Obj(v.fields.filterKeys{it !in setOf("taskId","dependsOn")}.toSortedMap().mapValues{(name,value)->canonical(value,name)})
            is AiJson.Arr->AiJson.Arr(v.values.map{canonical(it,key)})
            is AiJson.Str->aiString(when(key){"number"->normalizeNumber(v.value);"name","department"->v.value.trim();else->v.value})
            else->v
        }
        return digest(call.name+":"+canonical(call.arguments).encode())
    }
    fun prepare(taskId:String,call:ConversationToolCall,write:Boolean):LedgerStep {
        val signature=signature(call)
        byId[call.id]?.let{(old,step)->if(old!=signature)fail("duplicate_call_mismatch");return step}
        if(write)byEffect[taskId to signature]?.let{step->byId[call.id]=signature to step;return step}
        if(mutableSteps.size>=64)fail("turn_limit")
        return LedgerStep(UUID.randomUUID().toString(),taskId,call,write,signature).also{mutableSteps+=it;byId[call.id]=signature to it;if(write)byEffect[taskId to signature]=it}
    }
    fun start(step:LedgerStep){check(step.state==StepState.PREPARED);step.state=StepState.STARTED}
    fun finish(step:LedgerStep,receipt:ToolReceipt){
        step.receipt=receipt
        step.state=when{
            receipt.verification==VerificationStatus.VERIFIED->StepState.VERIFIED
            receipt.status==ToolStatus.COMMITTED->StepState.COMMITTED
            receipt.status in setOf(ToolStatus.QUEUED,ToolStatus.LAUNCHED)->StepState.COMMITTED
            receipt.status==ToolStatus.UNKNOWN->StepState.UNKNOWN
            receipt.status==ToolStatus.CANCELLED->StepState.CANCELLED
            receipt.status==ToolStatus.DENIED->StepState.DENIED
            receipt.status==ToolStatus.SKIPPED->StepState.SKIPPED
            receipt.status==ToolStatus.READ->StepState.VERIFIED
            else->StepState.FAILED
        }
    }
    fun repair(step:LedgerStep,call:ConversationToolCall,write:Boolean=step.write,taskId:String=step.taskId):LedgerStep {
        if(step.started||step.repairs>=2)fail("repair_limit")
        val identities=setOf("number","id","callId","widgetId","date","from","to","slot","taskId")
        if(identities.any{step.call.arguments[it]!=AiJson.Null&&step.call.arguments[it]!=call.arguments[it]})fail("repair_target_changed")
        val signature=signature(call)
        byId[call.id]?.let{if(it.first!=signature)fail("duplicate_call_mismatch")}
        byEffect.remove(step.taskId to step.signature)
        step.call=call;step.signature=signature;step.write=write;step.taskId=taskId;step.repairs++;step.state=StepState.PREPARED;step.receipt=null
        byId[call.id]=signature to step;if(step.write)byEffect[step.taskId to signature]=step
        return step
    }
    private fun fail(code:String):Nothing=throw BackendFailure(ApiError(ErrorCode.VALIDATION,"ai.$code"))
}
