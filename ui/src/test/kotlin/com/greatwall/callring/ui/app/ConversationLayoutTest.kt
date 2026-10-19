package com.greatwall.callring.ui.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.feature.conversation.ConversationScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h800dp")
class ConversationLayoutTest {
    @get:Rule val compose=createComposeRule()
    @Test fun compactSendHas48dpTouchTargetInsideInputAndNoIdentityLabels() {
        compose.setContent{MaterialTheme{ConversationScreen(ConversationState("s",messages=listOf(ConversationMessage("user","你好"),ConversationMessage("assistant","你好，世界"))),"输入","host",null,{},{},{},{})}}
        compose.onNodeWithContentDescription("发送消息").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("send-glyph",useUnmergedTree=true).assertWidthIsEqualTo(18.dp)
        compose.onNodeWithContentDescription("新对话").assertExists()
        compose.onNodeWithText("你").assertDoesNotExist();compose.onNodeWithText("AI").assertDoesNotExist()
        val send=compose.onNodeWithContentDescription("发送消息").fetchSemanticsNode().boundsInRoot
        val input=compose.onNodeWithTag("conversation-input").fetchSemanticsNode().boundsInRoot
        assertTrue(send.left>=input.left&&send.right<=input.right&&send.bottom<=input.bottom)
        val user=compose.onNodeWithTag("message-user-0").fetchSemanticsNode().boundsInRoot
        val assistant=compose.onNodeWithTag("message-assistant-1").fetchSemanticsNode().boundsInRoot
        assertTrue(user.left>assistant.left)
    }
    @Test @Config(qualifiers="w900dp-h900dp") fun wideLargeFontLayoutRetainsSendAndImeAction() {
        var sent=0
        compose.setContent{
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)){MaterialTheme{ConversationScreen(ConversationState("s"),"多行输入\n第二行","host",null,{},{sent++},{},{})}}
        }
        compose.onNodeWithContentDescription("发送消息").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.runOnIdle{assertEquals(1,sent)}
    }
}
