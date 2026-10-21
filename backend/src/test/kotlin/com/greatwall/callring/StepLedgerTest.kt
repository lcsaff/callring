package com.greatwall.callring

import com.greatwall.callring.application.conversation.execution.StepLedger

import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.conversation.*
import org.junit.Assert.*
import org.junit.Test

class StepLedgerTest {
    @Test fun changedIdsAndJsonFieldOrderCannotRepeatACommittedWrite() {
        val ledger=StepLedger()
        val call=ConversationToolCall("one","settings_saveAppearance",aiObject("colorMode" to aiString("DARK"),"presetId" to aiString("default")))
        val first=ledger.prepare("task",call,true)
        ledger.start(first)
        ledger.finish(first,ToolReceipt("operation","one",ToolStatus.COMMITTED,verification=VerificationStatus.VERIFIED))
        val duplicate=ledger.prepare("task",call.copy(id="two",arguments=aiObject("presetId" to aiString("default"),"colorMode" to aiString("DARK"))),true)
        assertSame(first,duplicate);assertEquals(1,ledger.steps.size)
        assertTrue(runCatching{ledger.prepare("task",call.copy(arguments=aiObject("colorMode" to aiString("LIGHT"))),true)}.isFailure)
    }
    @Test fun unknownCommitCannotBeReplayedAsARepair() {
        val ledger=StepLedger();val call=ConversationToolCall("one","settings_saveAppearance",aiObject("colorMode" to aiString("DARK")))
        val step=ledger.prepare("task",call,true);ledger.start(step);ledger.finish(step,ToolReceipt("op","one",ToolStatus.UNKNOWN))
        assertTrue(runCatching{ledger.repair(step,call.copy(id="repair"))}.isFailure)
    }
}
