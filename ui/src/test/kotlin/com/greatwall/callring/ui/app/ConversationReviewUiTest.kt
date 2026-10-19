package com.greatwall.callring.ui.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.application.api.*
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
    @Test fun nextSendAlwaysDisclosesLatestTargetAndUnknownTargetDisablesSend() {
        val state = mutableStateOf(ConversationState("s",ConversationPhase.STOPPED,target = "old-host · old-model"))
        val target = mutableStateOf("new-host · new-model")
        compose.setContent { ConversationScreen(state.value,"next",target.value,null,{},{},{},{}) }
        listOf(ConversationPhase.STOPPED,ConversationPhase.IDLE,ConversationPhase.COMPLETED,ConversationPhase.FAILED).forEach { phase ->
            compose.runOnIdle { state.value = state.value.copy(phase = phase) }
            compose.onNodeWithText("服务：new-host · new-model").assertExists()
            compose.onNodeWithText("服务：old-host · old-model").assertDoesNotExist()
        }
        compose.runOnIdle { target.value = "" }
        compose.onNodeWithContentDescription("发送消息").assertIsNotEnabled()
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
