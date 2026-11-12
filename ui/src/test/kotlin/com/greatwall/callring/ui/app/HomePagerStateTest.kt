package com.greatwall.callring.ui.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.ui.testing.HomePagerFixture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class HomePagerStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedUiStateRestoresPageSearchPaginationAndConversationDraft() {
        val api = HomePagerFixture()
        val restore = StateRestorationTester(compose)
        restore.setContent { CallringApp(api, onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("tab-conversation").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("待发送草稿")
        compose.onNodeWithTag("tab-callers").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("查询条件")
        compose.onNodeWithText("下一页").performClick()
        compose.onNodeWithText("第 2 页 · 共 150 条").assertExists()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("tab-callers").assertIsSelected()
        compose.onNode(hasSetTextAction()).assertTextContains("查询条件")
        compose.onNodeWithText("第 2 页 · 共 150 条").assertExists()
        compose.onNodeWithTag("tab-conversation").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("待发送草稿")
        compose.runOnIdle { assertEquals(0, api.sends) }
    }

    @Test fun backFromEachNonHomePageReturnsHome() {
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            CallringApp(HomePagerFixture(), onCapability = {}, onPinWidget = {}, onAppSettings = {})
        }
        listOf("callers", "conversation", "settings").forEach { route ->
            compose.onNodeWithTag("tab-$route").performClick()
            compose.runOnIdle { back.onBackPressed() }
            compose.onNodeWithTag("tab-home").assertIsSelected()
        }
    }

    @Test fun callerScrollSurvivesPageRemovalAndVerticalSwipesDoNotPage() {
        val api = HomePagerFixture()
        compose.setContent { CallringApp(api, onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("tab-callers").performClick()
        compose.onNodeWithTag("caller-list").performScrollToIndex(20)
        compose.onNodeWithText("档案20").assertIsDisplayed()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("tab-callers").performClick()
        compose.onNodeWithText("档案20").assertIsDisplayed()
        compose.onNodeWithTag("caller-list").performTouchInput { swipeUp() }
        compose.onNodeWithTag("tab-callers").assertIsSelected()
        compose.runOnIdle { assertEquals(1, api.searches.size) }
    }

    @Test fun relevantInvalidationRefreshesWhileUnrelatedChangesAndReturningDoNotQuery() {
        val api = HomePagerFixture()
        compose.setContent { CallringApp(api, onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("tab-callers").performClick()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.runOnIdle { api.publish(DataDomain.RINGTONE) }
        compose.runOnIdle { assertEquals(1, api.searches.size); assertEquals(1, api.summaryReads) }
        compose.runOnIdle { api.publish(DataDomain.CALLERS) }
        compose.runOnIdle { assertEquals(2, api.searches.size); assertEquals(1, api.summaryReads) }
        compose.onNodeWithTag("tab-callers").performClick()
        compose.runOnIdle { assertEquals(2, api.searches.size) }
    }

    @Test fun delayedReplyCompletesAfterPageAndDetailNavigationWithoutSendingOrStoppingAgain() {
        val api = HomePagerFixture()
        compose.setContent { CallringApp(api, onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("tab-conversation").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("请回复")
        compose.onNodeWithContentDescription("发送消息").performClick()
        compose.onNodeWithTag("tab-callers").performClick()
        compose.onNodeWithText("档案0").performClick()
        compose.runOnIdle { api.reply.complete(Unit) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("tab-conversation").performClick()
        // Markdown parses outside Compose's idling resources before it produces text layout.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("切页期间完成的回复").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("切页期间完成的回复").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, api.sends); assertEquals(0, api.stops); assertEquals(1, api.aiReads) }
    }

    @Test fun conversationModalDisablesBackgroundTabsAndRestoresThemAfterDismissal() {
        compose.setContent { CallringApp(HomePagerFixture(), onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("tab-conversation").performClick()
        compose.onNodeWithContentDescription("新对话").performClick()
        compose.onNodeWithTag("tab-home").assertIsNotEnabled()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("tab-home").assertIsEnabled().performClick()
        compose.onNodeWithTag("tab-home").assertIsSelected()
    }

    @Test fun restorationDuringHeldDragReturnsToLastSettledPageWithoutLoadingTentativePage() {
        val api = HomePagerFixture()
        val restore = StateRestorationTester(compose)
        restore.setContent { CallringApp(api, onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("home-pager").performTouchInput {
            down(Offset(width * .9f, height * .5f))
            moveTo(Offset(width * .1f, height * .5f), delayMillis = 300)
        }
        compose.onNodeWithTag("tab-callers").assertIsSelected()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("tab-home").assertIsSelected()
        compose.runOnIdle { assertEquals(0, api.searches.size) }
    }
}
