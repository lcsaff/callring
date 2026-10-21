package com.greatwall.callring.ui.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.ApiError
import com.greatwall.callring.contracts.common.ErrorCode
import com.greatwall.callring.ui.feature.conversation.ConversationScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class ConversationBlankRowsTest {
    @get:Rule val compose = createComposeRule()

    private fun hiddenRounds(count: Int) = (1..count).flatMap { index ->
        listOf(
            ConversationMessage("assistant", "工具调用说明不可作为操作结果显示", toolCalls = listOf(
                ConversationToolCall("read-$index", "settings_getAppearance", aiObject()))),
            ConversationMessage("tool", "", receipts = listOf(
                ToolReceipt("read-op-$index", "read-$index", ToolStatus.READ))),
        )
    }

    @Test fun invisibleToolRoundsDoNotMoveThePendingReply() {
        val user = ConversationMessage("user", "检查设置")
        val state = mutableStateOf(ConversationState("s", ConversationPhase.REQUESTING, listOf(user)))
        compose.setContent {
            MaterialTheme { ConversationScreen(state.value, "", "fixture", null, {}, {}, {}, {}) }
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("assistant-pending").fetchSemanticsNode().boundsInRoot.top
        for (round in 1..5) {
            compose.runOnIdle { state.value = state.value.copy(messages = listOf(user) + hiddenRounds(round)) }
            compose.waitForIdle()
            val after = compose.onNodeWithTag("assistant-pending").fetchSemanticsNode().boundsInRoot.top
            assertEquals("Invisible tool round $round must add no blank space", before, after, 0.5f)
            compose.onNodeWithTag("message-user-0").assertIsDisplayed()
        }
    }

    @Test fun whitespaceOnlyMessagesDoNotMoveThePendingReply() {
        val user = ConversationMessage("user", "你好")
        val state = mutableStateOf(ConversationState("s", ConversationPhase.REQUESTING, listOf(user)))
        compose.setContent {
            MaterialTheme { ConversationScreen(state.value, "", "fixture", null, {}, {}, {}, {}) }
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("assistant-pending").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle {
            state.value = state.value.copy(messages = listOf(user,
                ConversationMessage("assistant", "\n \t\n"),
                ConversationMessage("assistant", ""),
                ConversationMessage("user", "  "),
            ))
        }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("assistant-pending").fetchSemanticsNode().boundsInRoot.top
        assertEquals("Empty and whitespace-only messages must add no blank space", before, after, 0.5f)
        compose.onNodeWithTag("message-assistant-1").assertDoesNotExist()
    }

    @Test fun receiptOnlyRowsSurviveFilteringAndFinalReplyRemainsVisible() {
        val failed = ToolReceipt("failed-op", "failed-call", ToolStatus.FAILED,
            error = ApiError(ErrorCode.IO_FAILURE, "ai.timeout"))
        val verified = ToolReceipt("saved-op", "saved-call", ToolStatus.COMMITTED,
            verification = VerificationStatus.VERIFIED)
        val state = mutableStateOf(ConversationState("s", ConversationPhase.REQUESTING,
            listOf(ConversationMessage("user", "处理设置")) + hiddenRounds(20)))
        compose.setContent {
            MaterialTheme { ConversationScreen(state.value, "", "fixture", null, {}, {}, {}, {}) }
        }
        compose.runOnIdle {
            state.value = state.value.copy(phase = ConversationPhase.COMPLETED,
                messages = state.value.messages + ConversationMessage("tool", "", receipts = listOf(failed, verified)) +
                    ConversationMessage("assistant", "FINAL-AFTER-HIDDEN-ROWS"),
                receipts = listOf(failed, verified))
        }
        compose.waitForIdle()
        compose.waitUntil(5000) {
            runCatching { compose.onNodeWithText("FINAL-AFTER-HIDDEN-ROWS").assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.onAllNodesWithText("操作结果：失败").assertCountEquals(1)
        compose.onNodeWithText("查看 1 项已核验操作").assertIsDisplayed()
        compose.onNodeWithText("FINAL-AFTER-HIDDEN-ROWS").assertIsDisplayed()
        compose.onNodeWithTag("message-assistant-1").assertDoesNotExist()
        compose.onNodeWithTag("message-assistant-42").assertIsDisplayed()
        compose.onNodeWithTag("assistant-pending").assertDoesNotExist()
    }
}
