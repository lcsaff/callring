package com.greatwall.callring

import com.greatwall.callring.application.conversation.context.ContextAssembler

import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.conversation.*
import org.junit.Assert.*
import org.junit.Test

class ContextAssemblerTest {
    @Test fun largeResolvedResultsAreSentOnceButToolPairsRemainValid() {
        val c=ContextAssembler();c.begin(ConversationMessage("user","read"))
        c.append(ConversationMessage("assistant","",listOf(ConversationToolCall("id","data_query",aiObject()))))
        c.append(ConversationMessage("tool","",receipts=listOf(ToolReceipt("op","id",ToolStatus.READ,aiString("tail-".repeat(10000))))))
        assertTrue(c.requestMessages().last().receipts.single().value is AiJson.Str)
        c.markSent()
        val repeated=c.requestMessages()
        assertEquals("id",repeated.last().receipts.single().toolCallId)
        assertEquals(true,(repeated.last().receipts.single().value as AiJson.Obj)["dataOmitted"].boolean())
        assertEquals(1,repeated.count{it.toolCalls.isNotEmpty()})
    }
    @Test fun onlyWholeEndedTurnsAreEvictedAndRawResultsNeverEnterNextTurn() {
        val c=ContextAssembler()
        repeat(105){i->c.begin(ConversationMessage("user","question-$i"));c.append(ConversationMessage("assistant","answer-$i"));c.finish(aiObject("complete" to aiBool(true)))}
        assertEquals(100,c.endedTurns)
        c.begin(ConversationMessage("user","active"))
        assertEquals("active",c.requestMessages().last().text)
        assertEquals(101,c.requestMessages().size)
        assertTrue(c.uiMessages().last().text=="active")
        assertTrue(c.uiBytes<=2*1024*1024)
    }
    @Test fun unresolvedActivePairsAreNeverCompacted() {
        val c=ContextAssembler();c.begin(ConversationMessage("user","write"))
        c.append(ConversationMessage("assistant","",listOf(ConversationToolCall("pending","settings_saveAppearance",aiObject("colorMode" to aiString("DARK"))))))
        c.markSent()
        assertEquals("pending",c.requestMessages().last().toolCalls.single().id)
    }
}
