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
class ConversationStateUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun allPhasesAndActualReceiptStatusesRemainDistinct() {
        val state = mutableStateOf(ConversationState("session"))
        compose.setContent { ConversationScreen(state.value,"input","https://example.com · model",null,{},{},{},{}) }
        compose.runOnIdle{state.value=ConversationState("session",ConversationPhase.IDLE)}
        compose.onNodeWithText("准备就绪").assertDoesNotExist()
        compose.onNodeWithTag("ai-ready-dot").assertContentDescriptionEquals("已连接")
        val phases=mapOf(ConversationPhase.PREPARING to "准备会话",ConversationPhase.DISCOVERING to "查找所需功能",ConversationPhase.READING to "读取所需数据",ConversationPhase.REPAIRING to "修正未执行步骤",ConversationPhase.PARTIAL to "部分完成，请核对结果",ConversationPhase.CAPTURING to "准备会话",ConversationPhase.BUDGETING to "检查本轮容量",ConversationPhase.REQUESTING to "等待 AI 回复",ConversationPhase.VALIDATING to "验证回复",ConversationPhase.EXECUTING to "执行并核验操作",ConversationPhase.WAITING_FOREGROUND to "等待系统界面或本地确认",ConversationPhase.COMPLETED to "本轮已完成",ConversationPhase.STOPPING to "正在停止，保留已提交操作",ConversationPhase.STOPPED to "已停止",ConversationPhase.FAILED to "本轮未完成，请查看结果")
        phases.forEach{(phase,label)->
            compose.runOnIdle{state.value=ConversationState("session",phase)}
            if(state.value.busy)compose.onNodeWithText(label).assertExists() else compose.onNodeWithText(label).assertDoesNotExist()
            if(state.value.busy)compose.onNodeWithContentDescription("发送消息").assertIsNotEnabled()else compose.onNodeWithContentDescription("发送消息").assertIsEnabled()
        }
        val statuses = listOf("已读取", "已提交，尚未核验", "已排队，尚未完成", "已发起，等待实际结果", "已取消", "已拒绝", "失败", "结果未知，不会自动重放", "未执行")
        ToolStatus.entries.zip(statuses).forEach { (status, label) ->
            compose.runOnIdle { state.value = ConversationState("session",ConversationPhase.FAILED,receipts = listOf(ToolReceipt("op","call",status))) }
            if(status==ToolStatus.READ)compose.onNodeWithText("操作结果：$label").assertDoesNotExist()else compose.onNodeWithText("操作结果：$label").assertExists()
        }
    }
}
