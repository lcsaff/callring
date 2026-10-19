package com.greatwall.callring.ui.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.application.api.ConversationPhase
import com.greatwall.callring.application.api.ConversationState
import com.greatwall.callring.ui.feature.conversation.ConversationScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationComposerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun busyStateMergesStopIntoTheSendButtonAndShowsStatusInMessageList() {
        var stops = 0
        compose.setContent {
            MaterialTheme {
                ConversationScreen(
                    ConversationState("s", ConversationPhase.REQUESTING), "", "host", null,
                    {}, {}, { stops++ }, {},
                    connected = true,
                )
            }
        }
        compose.onNodeWithContentDescription("停止输出").assertExists().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
        compose.onNodeWithContentDescription("发送消息").assertDoesNotExist()
        compose.onNodeWithTag("assistant-pending").assertExists()
        compose.onNodeWithText("等待 AI 回复").assertExists()
    }

    @Test fun idleStateShowsSendButtonAndNoPendingStatus() {
        compose.setContent {
            MaterialTheme {
                ConversationScreen(ConversationState("s"), "hello", "host", null, {}, {}, {}, {})
            }
        }
        compose.onNodeWithContentDescription("发送消息").assertExists()
        compose.onNodeWithTag("assistant-pending").assertDoesNotExist()
    }
}
