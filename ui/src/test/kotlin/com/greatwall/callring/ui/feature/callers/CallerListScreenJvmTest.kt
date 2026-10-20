package com.greatwall.callring.ui.feature.callers

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.LoadState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallerListScreenJvmTest {
    @get:Rule val compose = createComposeRule()

    private val validUnmatched = NumberCheck("31234", validProfileNumber = true, matchesRule = false)

    private fun profile(number: String) =
        CallerProfile(number, true, "档案$number", "", null, PositionRelation.UNKNOWN, false)

    private fun loadedPage(items: List<CallerProfile>, totalItems: Long = items.size.toLong()) =
        LoadState(Page(items, 0, 50, totalItems, SnapshotMeta(1, 0)), loaded = true)

    private fun show(
        state: LoadState<Page<CallerProfile>>, query: String, check: NumberCheck?,
        onAdd: () -> Unit = {}, onReport: (String) -> Unit = {}, onPositions: () -> Unit = {},
    ) {
        compose.setContent { CallringTheme {
            CallerListScreen(state, CallerSearch(query), check, onSearch = {}, onPage = {}, onRetry = {},
                onAdd = onAdd, onCaller = {}, onReport = onReport, onPositions = onPositions)
        } }
    }

    @Test fun validNumberWithoutSavedProfileShowsReportActionForTheNormalizedNumber() {
        var reported: String? = null
        show(loadedPage(emptyList()), "31234", validUnmatched, onReport = { reported = it })
        compose.onNodeWithText("查看该号码的报表").assertExists()
        compose.onNodeWithText("规范号码 31234 · 不符合规则号码").assertExists()
        compose.onNodeWithText("查看该号码的报表").performClick()
        compose.runOnIdle { assertEquals("31234", reported) }
    }

    @Test fun ruleMismatchOnlyChangesTheSubCaptionAndNeverSuppressesTheAction() {
        val unmatched = NumberCheck("31234", true, false)
        show(loadedPage(emptyList()), "31234", unmatched)
        compose.onNodeWithText("查看该号码的报表").assertExists()
        compose.onNodeWithText("不符合规则号码", substring = true).assertExists()
    }

    @Test fun nameQueryHasNoReportAction() {
        show(loadedPage(emptyList()), "张", NumberCheck("张", validProfileNumber = false, matchesRule = false))
        compose.onNodeWithText("查看该号码的报表").assertDoesNotExist()
    }

    @Test fun blankQueryHasNoReportAction() {
        show(loadedPage(emptyList()), "", null)
        compose.onNodeWithText("查看该号码的报表").assertDoesNotExist()
    }

    @Test fun exactSavedProfileSuppressesTheReportAction() {
        show(loadedPage(listOf(profile("31234"))), "31234", validUnmatched)
        compose.onNodeWithText("查看该号码的报表").assertDoesNotExist()
    }

    @Test fun substringMatchInAnotherProfileDoesNotSuppressTheReportAction() {
        show(loadedPage(listOf(profile("131234"))), "31234", validUnmatched)
        compose.onNodeWithText("查看该号码的报表").assertExists()
    }

    @Test fun invalidNumberHasNoReportAction() {
        show(loadedPage(emptyList()), "12", NumberCheck("12", validProfileNumber = false, matchesRule = false))
        compose.onNodeWithText("查看该号码的报表").assertDoesNotExist()
    }

    @Test fun loadingSearchHasNoReportAction() {
        show(LoadState(Page(emptyList(), 0, 50, 0, SnapshotMeta(1, 0)), loaded = true, loading = true),
            "31234", validUnmatched)
        compose.onNodeWithText("查看该号码的报表").assertDoesNotExist()
    }

    @Test fun titleActionsAreIconButtonsWithAccessibleNamesAndNoLegacyBodyEntries() {
        var adds = 0
        var positions = 0
        show(loadedPage(emptyList()), "", null, onAdd = { adds++ }, onPositions = { positions++ })
        compose.onNodeWithText("按号码查看报表").assertDoesNotExist()
        compose.onNodeWithText("通话录音").assertDoesNotExist()
        compose.onNodeWithText("新增").assertDoesNotExist()
        compose.onNodeWithContentDescription("职位管理").performClick()
        compose.onNodeWithContentDescription("新增档案").performClick()
        compose.runOnIdle {
            assertEquals(1, positions)
            assertEquals(1, adds)
        }
    }

    @Test fun singlePageSearchHidesPageControls() {
        show(loadedPage(listOf(profile("100")), 1), "", null)
        compose.onNodeWithText("上一页").assertDoesNotExist()
        compose.onNodeWithText("下一页").assertDoesNotExist()
    }
}
