package com.greatwall.callring.ui.app

import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.greatwall.callring.ui.testing.HomePagerFixture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Before

/** Real input delivery in the standalone com.greatwall.callring.ui.test APK. */
class HomePagerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun matchProductionWindowInsets() {
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
    }

    @Test fun flingTabsVerticalScrollAndInputPriority() {
        val api = HomePagerFixture()
        compose.setContent { CallringApp(api, onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.runOnIdle { assertEquals(0, api.summaryReads); assertEquals(0, api.searches.size); assertEquals(0, api.aiReads) }
        compose.onNodeWithTag("home-pager").performTouchInput { swipe(Offset(width * .75f, centerY), Offset(width * .25f, centerY), durationMillis = 100) }
        compose.onNodeWithTag("tab-callers").assertIsSelected()
        compose.onNodeWithTag("caller-list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("tab-callers").assertIsSelected()
        compose.onNode(hasSetTextAction()).performTextReplacement("设备搜索")
        compose.onNode(hasSetTextAction()).performTouchInput { swipeLeft() }
        // IME can hide the bar; the actual content must still be the callers page.
        compose.onNodeWithTag("caller-list").assertExists()
        compose.onNodeWithTag("caller-list").performTouchInput { swipe(Offset(width * .75f, centerY), Offset(width * .25f, centerY), durationMillis = 100) }
        compose.onNodeWithTag("conversation-input").assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("设备草稿")
        compose.onNodeWithTag("conversation-input").performTouchInput { swipeRight() }
        compose.onNodeWithTag("conversation-input").assertExists()
        compose.onNodeWithTag("conversation-messages").performTouchInput { swipe(Offset(width * .75f, centerY), Offset(width * .25f, centerY), durationMillis = 100) }
        // Native IME hide animation completes after Compose's page-settling frame.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("tab-settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-settings").assertIsSelected()
        compose.onNodeWithTag("home-pager").performTouchInput { swipe(Offset(width * .75f, centerY), Offset(width * .25f, centerY), durationMillis = 100) }
        compose.onNodeWithTag("tab-settings").assertIsSelected()
        compose.onNodeWithTag("tab-conversation").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("设备草稿")
        compose.runOnIdle { assertEquals(1, api.aiReads); assertEquals(0, api.sends) }
    }

    @Test fun delayedReplyAndModalKeepOneOwner() {
        val api = HomePagerFixture()
        compose.setContent { CallringApp(api, onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("tab-conversation").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("隔离测试")
        compose.onNodeWithContentDescription("发送消息").performClick()
        compose.onNodeWithTag("conversation-messages").performTouchInput { swipe(Offset(width * .25f, centerY), Offset(width * .75f, centerY), durationMillis = 100) }
        compose.onNodeWithTag("tab-callers").assertIsSelected()
        compose.runOnIdle { api.reply.complete(Unit) }
        compose.onNodeWithTag("tab-conversation").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("切页期间完成的回复").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("切页期间完成的回复").assertIsDisplayed()
        compose.onNodeWithContentDescription("新对话").performClick()
        compose.onNodeWithTag("tab-home").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("tab-home").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, api.sends); assertEquals(0, api.stops) }
    }

    @Test fun systemEdgeStartDoesNotDrivePager() {
        val api = HomePagerFixture()
        compose.setContent { CallringApp(api, onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("tab-settings").performClick()
        // Compose injects at the view, so this checks Pager yielding; OS back is separate.
        compose.onNodeWithTag("home-pager").performTouchInput {
            swipe(Offset(1f, centerY), Offset(width * .75f, centerY), durationMillis = 100)
        }
        compose.onNodeWithTag("tab-settings").assertIsSelected()
        compose.runOnIdle { assertEquals(0, api.searches.size); assertEquals(0, api.aiReads) }
    }
}
