package com.greatwall.callring.ui.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.application.api.AiJson
import com.greatwall.callring.application.api.ConversationMessage
import com.greatwall.callring.application.api.ConversationState
import com.greatwall.callring.application.api.ConversationToolCall
import com.greatwall.callring.ui.feature.conversation.ConversationScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationCopyTest {
    @get:Rule val compose = createComposeRule()

    private val markdown = "# 探针标题\n\n- 中文列表\n\n```kotlin\nval x = 1\n```"

    private class RecordingHaptics : HapticFeedback {
        val types = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { types += hapticFeedbackType }
    }

    private class RecordingUriHandler : UriHandler {
        val opened = mutableListOf<String>()
        override fun openUri(uri: String) { opened += uri }
    }

    private fun clipboard() = RuntimeEnvironment.getApplication()
        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun clipboardText() = clipboard().primaryClip?.getItemAt(0)?.text?.toString()

    /** Markdown 解析晚于首帧；等正文节点出现再交互，避免冷启动时序造成的假失败。 */
    private fun awaitRenderedText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** 4 秒的 Snackbar 提示在测试内结束，避免遗留延迟任务影响同进程后续用例的滚动/帧时序。 */
    private fun settleFeedback() {
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
    }

    @After fun drainPendingFeedback() { runCatching { settleFeedback() } }

    private fun setConversation(
        vararg messages: ConversationMessage,
        haptics: HapticFeedback? = null,
        uriHandler: UriHandler? = null,
    ) {
        compose.setContent {
            val body: @Composable () -> Unit = {
                MaterialTheme {
                    ConversationScreen(ConversationState("s", messages = messages.toList()), "", "host", null, {}, {}, {}, {})
                }
            }
            when {
                haptics != null && uriHandler != null -> CompositionLocalProvider(
                    LocalHapticFeedback provides haptics, LocalUriHandler provides uriHandler,
                ) { body() }
                haptics != null -> CompositionLocalProvider(LocalHapticFeedback provides haptics) { body() }
                uriHandler != null -> CompositionLocalProvider(LocalUriHandler provides uriHandler) { body() }
                else -> body()
            }
        }
    }

    @Test fun longPressUserBubbleCopiesItsTextConfirmsWithSnackbarAndVibratesOnce() {
        val haptics = RecordingHaptics()
        setConversation(ConversationMessage("user", "明天上午十点提醒我"), haptics = haptics)
        compose.onNodeWithTag("message-user-0").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("明天上午十点提醒我", clipboardText())
        compose.onNodeWithText("已复制").assertIsDisplayed()
        assertEquals(listOf(HapticFeedbackType.LongPress), haptics.types)
        settleFeedback()
        compose.onNodeWithText("已复制").assertDoesNotExist()
    }

    @Test fun longPressAssistantBubbleCopiesMarkdownSource() {
        setConversation(ConversationMessage("assistant", markdown))
        compose.onNodeWithTag("message-assistant-0").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(markdown, clipboardText())
        compose.onNodeWithText("已复制").assertIsDisplayed()
    }

    @Test fun longPressOnALinkCopiesWithoutOpeningItAndTapStillOpensIt() {
        val links = RecordingUriHandler()
        setConversation(ConversationMessage("assistant", "[官方链接](https://example.com)"), uriHandler = links)
        awaitRenderedText("官方链接")
        compose.onNodeWithText("官方链接", useUnmergedTree = true).performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("[官方链接](https://example.com)", clipboardText())
        assertEquals(emptyList<String>(), links.opened)
        compose.onNodeWithText("官方链接", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf("https://example.com"), links.opened)
    }

    @Test fun tappingABubbleDoesNotCopy() {
        clipboard().setPrimaryClip(ClipData.newPlainText(null, "原有内容"))
        setConversation(ConversationMessage("user", "点一下不该复制"), ConversationMessage("assistant", markdown))
        compose.onNodeWithTag("message-user-0").performTouchInput { click() }
        compose.onNodeWithTag("message-assistant-1").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals("原有内容", clipboardText())
        compose.onNodeWithText("已复制").assertDoesNotExist()
    }

    @Test fun presentationMessagesCopyTheVisibleTextNotTheHiddenOriginal() {
        setConversation(ConversationMessage("assistant", "隐藏原文", presentationKey = "conversation.continue"))
        compose.onNodeWithTag("message-assistant-0").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals("继续未完成任务", clipboardText())
    }

    @Test fun messagesWithoutRenderableBodyExposeNoCopyableBubble() {
        setConversation(
            ConversationMessage("user", ""),
            ConversationMessage("assistant", "含工具调用", toolCalls = listOf(ConversationToolCall("call-1", "callers_list", AiJson.Obj(emptyMap())))),
        )
        compose.onNodeWithTag("message-user-0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("message-assistant-1", useUnmergedTree = true).assertDoesNotExist()
        assertNull(clipboardText())
    }

    @Test fun copyActionAndMessageTextShareOneAccessibilityNode() {
        setConversation(ConversationMessage("assistant", markdown), ConversationMessage("user", "无障碍复制"))
        awaitRenderedText("探针标题")
        val assistant = compose.onNodeWithTag("message-assistant-0")
        val user = compose.onNodeWithTag("message-user-1")
        assistant.assertTextContains("探针标题", substring = true)
        user.assertTextContains("无障碍复制")
        assertEquals("复制", assistant.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        assertEquals("复制", user.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        assistant.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals(markdown, clipboardText())
        user.performSemanticsAction(SemanticsActions.OnLongClick)
        compose.waitForIdle()
        assertEquals("无障碍复制", clipboardText())
    }
}
