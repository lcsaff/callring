package com.greatwall.callring.ui.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.ai.ConversationMessage
import com.greatwall.callring.contracts.ai.ConversationState
import com.greatwall.callring.ui.feature.conversation.ConversationScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class ConversationHistoryRestoreTest {
    @get:Rule val compose = createComposeRule()

    private val messages = (0..39).map { ConversationMessage("user", "消息 $it：用于保持历史阅读位置的内容") }

    private fun readEarlierMessages() {
        compose.onNodeWithTag("conversation-messages").performTouchInput { swipeDown() }
        compose.onNodeWithTag("jump-to-latest").assertIsDisplayed()
    }

    @Test fun savedInstanceStateRestoreKeepsHistoryReadingPosition() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme { ConversationScreen(ConversationState("s", messages = messages), "", "host", null, {}, {}, {}, {}) }
        }
        readEarlierMessages()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("jump-to-latest").assertIsDisplayed()
        compose.onNodeWithTag("message-user-39").assertIsNotDisplayed()
    }

    @Test fun returningToSavedPageDoesNotFollowMessagesReceivedWhileAway() {
        val visible = mutableStateOf(true)
        val state = mutableStateOf(ConversationState("s", messages = messages))
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            MaterialTheme {
                if (visible.value) holder.SaveableStateProvider("conversation") {
                    ConversationScreen(state.value, "", "host", null, {}, {}, {}, {})
                }
            }
        }
        readEarlierMessages()
        compose.runOnIdle { visible.value = false }
        compose.runOnIdle {
            state.value = state.value.copy(messages = messages + ConversationMessage("assistant", "切走期间的新回复"))
            visible.value = true
        }
        compose.onNodeWithTag("jump-to-latest").assertIsDisplayed()
        compose.onNodeWithTag("message-assistant-40").assertIsNotDisplayed()
    }

    @Test fun inactivePageWaitsUntilItBecomesActiveBeforeFollowingNewMessages() {
        val active = mutableStateOf(true)
        val state = mutableStateOf(ConversationState("s", messages = messages))
        compose.setContent {
            MaterialTheme { ConversationScreen(state.value, "", "host", null, {}, {}, {}, {}, active = active.value) }
        }
        compose.onNodeWithTag("message-user-39").assertIsDisplayed()
        compose.runOnIdle {
            active.value = false
            state.value = state.value.copy(messages = messages + (40..59).map { ConversationMessage("user", "新增消息 $it") })
        }
        compose.onNodeWithTag("message-user-39").assertIsDisplayed()
        compose.onNodeWithTag("message-user-59").assertIsNotDisplayed()
        compose.runOnIdle { active.value = true }
        compose.onNodeWithTag("message-user-59").assertIsDisplayed()
    }
}
