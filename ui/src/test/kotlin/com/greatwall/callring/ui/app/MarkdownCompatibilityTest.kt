package com.greatwall.callring.ui.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import com.greatwall.callring.application.api.ConversationMessage
import com.greatwall.callring.application.api.ConversationState
import com.greatwall.callring.ui.feature.conversation.ConversationScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h1200dp")
class MarkdownCompatibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun assistantRendersMarkdownInsteadOfLiteralSyntax() {
        compose.setContent {
            MaterialTheme {
                ConversationScreen(
                    ConversationState("markdown", messages = listOf(ConversationMessage("assistant", "# 探针标题\n\n- 中文列表\n\n```kotlin\nval x = 1\n```\n\n[官方链接](https://example.com)\n\n|列|值|\n|---|---|\n|甲|乙|"))),
                    "", "", null, {}, {}, {}, {},
                )
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("探针标题").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("探针标题").assertIsDisplayed()
        compose.onNodeWithText("中文列表").assertIsDisplayed()
    }
}
