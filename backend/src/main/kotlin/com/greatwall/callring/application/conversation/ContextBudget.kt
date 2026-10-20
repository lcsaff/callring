package com.greatwall.callring.application.conversation

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.application.ports.BackendFailure

object ContextBudget {
    const val REQUEST_BYTES = 8 * 1024 * 1024
    const val RESPONSE_BYTES = 1024 * 1024
    fun estimate(body: ByteArray, budget: ConversationBudget): Long {
        return estimateSize(body.size,budget)
    }
    private fun estimateSize(size:Int,budget:ConversationBudget):Long {
        budget.validate()
        val input = size.toLong()
        val margin = maxOf(1024L, Math.addExact(input, 9) / 10)
        return Math.addExact(Math.addExact(Math.addExact(input, 1024), budget.output), margin)
    }
    fun check(body: ByteArray, budget: ConversationBudget): Long {
        return checkReserved(body,0,budget)
    }
    fun checkReserved(body:ByteArray,reservedBytes:Int,budget:ConversationBudget):Long {
        require(reservedBytes>=0)
        val size=Math.addExact(body.size,reservedBytes)
        val estimate = estimateSize(size,budget)
        if (size > REQUEST_BYTES || estimate > budget.context) throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED, "ai.context_budget_exceeded", fieldErrors = mapOf("estimated" to estimate.toString(), "context" to budget.context.toString())))
        return estimate
    }
}
