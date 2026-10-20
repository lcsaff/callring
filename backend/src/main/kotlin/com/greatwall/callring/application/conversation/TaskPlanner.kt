package com.greatwall.callring.application.conversation

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.ports.BackendFailure
import java.util.UUID

enum class TaskKind { CREATE_PROFILES, SET_APPEARANCE, OPERATIONS }
data class TaskPlan(val id:String,val userMessageRef:String,val kind:TaskKind,val expectedCount:Int,val targets:Set<String>) {
    fun json()=aiObject("taskId" to aiString(id),"kind" to aiString(kind.name),"userMessageRef" to aiString(userMessageRef),"expectedCount" to aiNumber(expectedCount.toLong()),"targets" to AiJson.Arr(targets.map(::aiString)))
}
class TaskPlanner {
    private val plans=linkedMapOf<String,TaskPlan>()
    fun forUser(userMessageRef:String)=plans.values.filter{it.userMessageRef==userMessageRef}
    fun reset(){plans.clear()}
    fun get(id:String)=plans[id]?:throw BackendFailure(ApiError(ErrorCode.NOT_FOUND,"ai.task_not_found"))
    fun prepare(userMessageRef:String,kind:TaskKind,expectedCount:Int,targets:Set<String> = emptySet()):TaskPlan {
        require(expectedCount in 1..32)
        plans.values.firstOrNull{it.userMessageRef==userMessageRef&&it.kind==kind}?.let{old->require(old.expectedCount==expectedCount&&old.targets==targets){"ai.task_target_changed"};return old}
        if(plans.size>=32)throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.capacity_exceeded"))
        return TaskPlan(UUID.randomUUID().toString(),userMessageRef,kind,expectedCount,targets).also{plans[it.id]=it}
    }
    fun verifiedCount(plan:TaskPlan,steps:List<LedgerStep>):Int {
        val verified=steps.filter{it.taskId==plan.id&&it.write&&it.state==StepState.VERIFIED}
        return if(plan.kind==TaskKind.CREATE_PROFILES)verified.filter{it.call.name=="callers_saveProfile"}.mapNotNull{(it.call.arguments["number"] as? AiJson.Str)?.value}.distinct().size else verified.size
    }
    fun satisfied(plan:TaskPlan,steps:List<LedgerStep>):Boolean {
        if(verifiedCount(plan,steps)!=plan.expectedCount)return false
        if(plan.kind==TaskKind.CREATE_PROFILES&&plan.targets.isNotEmpty())return steps.filter{it.taskId==plan.id&&it.state==StepState.VERIFIED}.mapNotNull{(it.call.arguments["number"] as? AiJson.Str)?.value}.toSet()==plan.targets
        if(plan.kind==TaskKind.SET_APPEARANCE&&plan.targets.isNotEmpty())return steps.filter{it.taskId==plan.id&&it.state==StepState.VERIFIED}.all{(it.call.arguments["colorMode"] as? AiJson.Str)?.value in plan.targets}
        return true
    }
}
