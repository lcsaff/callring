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
        val phases = listOf("准备就绪", "完整读取数据中", "检查完整上下文容量", "等待 AI 回复", "验证完整回复", "执行并同步业务操作", "等待前台系统界面或本地确认", "本轮已结束，请核对实际操作结果", "正在停止，保留已提交操作", "已停止", "本轮未完成，请查看错误和已提交结果")
        ConversationPhase.entries.zip(phases).forEach { (phase, label) ->
            compose.runOnIdle { state.value = ConversationState("session",phase) }
            compose.onNodeWithText(label).assertExists()
            if(state.value.busy) compose.onNodeWithText("发送").assertIsNotEnabled()
            else compose.onNodeWithText("发送").assertIsEnabled()
        }
        val statuses = listOf("已读取", "已保存 / 已执行", "已排队，尚未完成", "已发起，后续状态以系统为准", "已取消", "已拒绝", "失败", "结果未知，不会自动重放", "未执行")
        ToolStatus.entries.zip(statuses).forEach { (status, label) ->
            compose.runOnIdle { state.value = ConversationState("session",ConversationPhase.FAILED,receipts = listOf(ToolReceipt("op","call",status))) }
            compose.onNodeWithText("操作结果：$label").assertExists()
        }
    }
}
