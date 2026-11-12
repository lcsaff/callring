package com.greatwall.callring.ui.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.ai.ConversationState
import com.greatwall.callring.ui.feature.conversation.ConversationScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationModalLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newConversationDialogReportsVisibilityAndReleasesItOnDisposal() {
        val visible = mutableStateOf(true)
        var modal = false
        compose.setContent {
            MaterialTheme {
                if (visible.value) ConversationScreen(ConversationState("s"), "", "host", null, {}, {}, {}, {},
                    onModalChanged = { modal = it })
            }
        }
        compose.runOnIdle { assertFalse(modal) }
        compose.onNodeWithContentDescription("新对话").performClick()
        compose.runOnIdle { assertTrue(modal) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertFalse(modal) }
        compose.onNodeWithContentDescription("新对话").performClick()
        compose.runOnIdle { assertTrue(modal); visible.value = false }
        compose.runOnIdle { assertFalse(modal) }
    }
}
