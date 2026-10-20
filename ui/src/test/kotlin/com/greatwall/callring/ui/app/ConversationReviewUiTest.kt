package com.greatwall.callring.ui.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.ui.feature.conversation.ConversationScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationReviewUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun conversationHasNoServiceLabelAndDotTracksConnection() {
        val connected = mutableStateOf(true)
        compose.setContent { ConversationScreen(ConversationState("s"),"next","new-host · new-model",null,{},{},{},{},connected = connected.value) }
        compose.onNodeWithText("服务：new-host · new-model").assertDoesNotExist()
        compose.onNodeWithText("服务：old-host · old-model").assertDoesNotExist()
        compose.onNodeWithContentDescription("AI 设置").assertExists()
        compose.onNodeWithTag("ai-ready-dot").assertContentDescriptionEquals("已连接")
        compose.runOnIdle { connected.value = false }
        compose.onNodeWithTag("ai-ready-dot").assertContentDescriptionEquals("未连接")
        compose.onNodeWithTag("ai-ready-dot").assertExists()
    }
    @Test fun toolBatchNarrationCannotMasqueradeAsVerifiedFinalText() {
        val state = mutableStateOf(ConversationState("s"))
        compose.setContent { ConversationScreen(state.value,"","host",null,{},{},{},{}) }
        val message = ConversationMessage("assistant","录音已删除",listOf(ConversationToolCall("delete","recordings_delete",aiObject("callId" to aiNumber(1)))))
        for(status in listOf<ToolStatus?>(null,ToolStatus.DENIED,ToolStatus.CANCELLED,ToolStatus.FAILED,ToolStatus.COMMITTED,ToolStatus.SKIPPED)) {
            compose.runOnIdle { state.value = ConversationState("s",if(status == null) ConversationPhase.WAITING_FOREGROUND else if(status == ToolStatus.COMMITTED) ConversationPhase.COMPLETED else ConversationPhase.FAILED,
                messages = listOf(message),receipts = status?.let { listOf(ToolReceipt("op","delete",it)) } ?: emptyList()) }
            compose.onNodeWithText("AI 操作建议（未经验证，以操作回执为准）").assertDoesNotExist()
            compose.onNodeWithText("录音已删除").assertDoesNotExist()
            compose.onNodeWithText("AI",useUnmergedTree = true).assertDoesNotExist()
        }
    }
}
