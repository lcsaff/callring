package com.greatwall.callring.ui.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import com.greatwall.callring.contracts.callers.CallerSearch
import com.greatwall.callring.ui.testing.UiFakeApi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomePagerTest {
    @get:Rule val compose = createComposeRule()

    private class CountingApi : UiFakeApi() {
        var homes = 0
        var searches = 0
        var summaries = 0
        override suspend fun load() = super.load().also { homes++ }
        override suspend fun search(query: CallerSearch) = super.search(query).also { searches++ }
        override suspend fun getSummary() = super.getSummary().also { summaries++ }
    }

    private fun show(api: UiFakeApi) {
        compose.setContent { CallringApp(api, onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
    }

    @Test fun precompositionDoesNotQueryUnvisitedPages() {
        val api = CountingApi()
        show(api)
        compose.runOnIdle {
            assertEquals(1, api.homes)
            assertEquals(0, api.searches)
            assertEquals(0, api.summaries)
        }
    }

    @Test fun fastFlingChangesOnePageAndSynchronizesTabsWithoutLooping() {
        show(CountingApi())
        compose.onNodeWithTag("home-pager").performTouchInput { swipeLeft(durationMillis = 100) }
        compose.onNodeWithTag("tab-callers").assertIsSelected()
        compose.onNodeWithTag("home-pager").performTouchInput { swipeLeft(durationMillis = 100) }
        compose.onNodeWithTag("tab-conversation").assertIsSelected()
        compose.onNodeWithTag("home-pager").performTouchInput { swipeLeft(durationMillis = 100) }
        compose.onNodeWithTag("tab-settings").assertIsSelected()
        compose.onNodeWithTag("home-pager").performTouchInput { swipeLeft(durationMillis = 100) }
        compose.onNodeWithTag("tab-settings").assertIsSelected()
        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithTag("home-pager").performTouchInput { swipeRight(durationMillis = 100) }
        compose.onNodeWithTag("tab-home").assertIsSelected()
    }

    @Test fun selectingFarTabSkipsQueriesForIntermediatePagesAndReturningReusesQueries() {
        val api = CountingApi()
        show(api)
        compose.onNodeWithTag("tab-settings").performClick()
        compose.runOnIdle { assertEquals(0, api.searches); assertEquals(1, api.summaries) }
        repeat(3) {
            compose.onNodeWithTag("tab-callers").performClick()
            compose.onNodeWithTag("tab-conversation").performClick()
            compose.onNodeWithTag("tab-home").performClick()
            compose.onNodeWithTag("tab-settings").performClick()
        }
        compose.runOnIdle { assertEquals(1, api.homes); assertEquals(1, api.searches); assertEquals(1, api.summaries) }
    }

    @Test fun detailsReturnToSourcePageAndKeepSearch() {
        val api = CountingApi()
        show(api)
        compose.onNodeWithTag("tab-callers").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("测试")
        compose.onNodeWithText("测试档案").performClick()
        compose.onNodeWithTag("bottom-bar").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("tab-callers").assertIsSelected()
        compose.onNode(hasSetTextAction()).assertTextContains("测试")
        compose.runOnIdle { assertEquals(2, api.searches) }
    }

    @Test fun horizontalDragInsideSearchDoesNotPage() {
        show(CountingApi())
        compose.onNodeWithTag("tab-callers").performClick()
        compose.onNode(hasSetTextAction()).performTouchInput { swipeLeft() }
        compose.onNodeWithTag("tab-callers").assertIsSelected()
        compose.onNodeWithTag("home-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("tab-conversation").assertIsSelected()
    }

    @Test fun rapidTabRequestsFinishAtLastSelectionWithoutDuplicateQueries() {
        val api = CountingApi()
        show(api)
        val actions = listOf("callers", "conversation", "home", "callers", "settings").map {
            compose.onNodeWithTag("tab-$it").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        }
        compose.runOnIdle { actions.forEach { it() } }
        compose.onNodeWithTag("tab-settings").assertIsSelected()
        compose.runOnIdle { org.junit.Assert.assertTrue(api.searches <= 1); assertEquals(1, api.summaries) }
    }
}
