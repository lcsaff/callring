package com.greatwall.callring.application.conversation.context

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.ports.BackendFailure

class TurnBudget {
    var work=0;private set
    var requests=0;private set
    var repairs=0;private set
    private val stepRepairs=mutableMapOf<String,Int>()
    fun consume(count:Int=1){require(count>=0);if(work+count>64)fail();work+=count}
    fun request(){if(requests>=16)fail();requests++}
    fun canStartWrites(count:Int)=count>=0&&work+count*2<=64
    fun repair(stepId:String):Boolean {if(repairs>=4||(stepRepairs[stepId]?:0)>=2)return false;repairs++;stepRepairs[stepId]=(stepRepairs[stepId]?:0)+1;return true}
    private fun fail():Nothing=throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED,"ai.turn_limit",aiFailure=ConversationFailure(AiFailureKind.CAPACITY,"TURN_LIMIT")))
}
