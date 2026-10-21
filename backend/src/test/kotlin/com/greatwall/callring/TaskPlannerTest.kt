package com.greatwall.callring

import com.greatwall.callring.application.conversation.context.TurnBudget
import com.greatwall.callring.application.conversation.execution.StepLedger
import com.greatwall.callring.application.conversation.execution.TaskKind
import com.greatwall.callring.application.conversation.execution.TaskPlanner

import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.conversation.*
import org.junit.Assert.*
import org.junit.Test

class TaskPlannerTest {
    @Test fun planNeverShrinksAndDuplicateReceiptsDoNotMeetTenItemGoal() {
        val planner=TaskPlanner();val plan=planner.prepare("user-1",TaskKind.CREATE_PROFILES,10)
        assertTrue(runCatching{planner.prepare("user-1",TaskKind.CREATE_PROFILES,8)}.isFailure)
        val ledger=StepLedger()
        val call=ConversationToolCall("one","callers_saveProfile",aiObject("number" to aiString("9990001")))
        val step=ledger.prepare(plan.id,call,true);ledger.start(step);ledger.finish(step,ToolReceipt("op","one",ToolStatus.COMMITTED,verification=VerificationStatus.VERIFIED))
        ledger.prepare(plan.id,call.copy(id="again"),true)
        assertFalse(planner.satisfied(plan,ledger.steps));assertEquals(1,planner.verifiedCount(plan,ledger.steps))
    }
    @Test fun budgetAccountsForBatchItemsAndReadBackBeforeStartingWrites() {
        val budget=TurnBudget();budget.consume(45)
        assertFalse(budget.canStartWrites(10));assertTrue(budget.canStartWrites(9))
        assertTrue(runCatching{budget.consume(20)}.isFailure)
    }
}
