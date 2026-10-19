package com.greatwall.callring.ui.feature.callers

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CallerScreensTest {
    @get:Rule val compose = createComposeRule()

    private val meta = SnapshotMeta(1, 0)
    private fun page(items: List<CallerProfile> = emptyList(), totalItems: Long = items.size.toLong()) =
        LoadState(Page(items, 0, 50, totalItems, meta), loaded = true)

    @Test fun failedSaveKeepsEditableInputAndDisplaysFieldError() {
        val draft = CallerDraft("100", "正在编辑", "研发", null, false)
        var saveCalls = 0
        compose.setContent { CallringTheme {
            CallerEditorScreen(
                state = LoadState(CallerEditorData(draft, emptyList(), null), loaded = true), draft = draft,
                mutation = MutationState(error = ApiError(ErrorCode.VALIDATION, "输入有误", mapOf("name" to "名称过长"))),
                numberEditable = false, onBack = {}, onRetry = {}, onChange = {}, onSave = { saveCalls++ })
        } }
        compose.onNodeWithText("正在编辑").assertExists()
        compose.onNodeWithText("名称过长").assertExists()
        compose.onNodeWithText("保存档案").performClick()
        compose.runOnIdle { assertEquals(1, saveCalls) }
    }

    @Test fun loadedEmptySearchIsExplicitAndAddActionWorks() {
        var addCalls = 0
        compose.setContent { CallringTheme {
            CallerListScreen(page(), CallerSearch(), null,
                onSearch = {}, onPage = {}, onRetry = {}, onAdd = { addCalls++ }, onCaller = {}, onReport = {})
        } }
        compose.onNodeWithText("暂无匹配档案").assertExists()
        compose.onNodeWithContentDescription("新增档案").performClick()
        compose.runOnIdle { assertEquals(1, addCalls) }
    }

    @Test fun titleActionsAreAccessible48dpIconButtonsWithoutLegacyBodyEntries() {
        var positions = 0
        compose.setContent { CallringTheme {
            CallerListScreen(page(), CallerSearch(), null,
                onSearch = {}, onPage = {}, onRetry = {}, onAdd = {}, onCaller = {}, onReport = {},
                onPositions = { positions++ })
        } }
        compose.onNodeWithText("按号码查看报表").assertDoesNotExist()
        compose.onNodeWithText("通话录音").assertDoesNotExist()
        compose.onNodeWithContentDescription("职位管理").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription("新增档案").assertHeightIsAtLeast(48.dp).assertExists()
        compose.runOnIdle { assertEquals(1, positions) }
    }

    @Test fun validUnsavedNumberOffersTheReportActionForItsNormalizedNumber() {
        var reported: String? = null
        compose.setContent { CallringTheme {
            CallerListScreen(page(), CallerSearch("31234"), NumberCheck("31234", true, false),
                onSearch = {}, onPage = {}, onRetry = {}, onAdd = {}, onCaller = {}, onReport = { reported = it })
        } }
        compose.onNodeWithText("查看该号码的报表").assertExists().performClick()
        compose.runOnIdle { assertEquals("31234", reported) }
    }

    @Test fun savedExactNumberSuppressesTheReportAction() {
        val saved = CallerProfile("31234", true, "档案", "", null, PositionRelation.UNKNOWN, false)
        compose.setContent { CallringTheme {
            CallerListScreen(page(listOf(saved), 1), CallerSearch("31234"), NumberCheck("31234", true, false),
                onSearch = {}, onPage = {}, onRetry = {}, onAdd = {}, onCaller = {}, onReport = {})
        } }
        compose.onNodeWithText("查看该号码的报表").assertDoesNotExist()
    }

    @Test fun singleSearchPageHidesPagination() {
        compose.setContent { CallringTheme {
            CallerListScreen(page(), CallerSearch(), null,
                onSearch = {}, onPage = {}, onRetry = {}, onAdd = {}, onCaller = {}, onReport = {})
        } }
        compose.onNodeWithText("上一页").assertDoesNotExist()
        compose.onNodeWithText("下一页").assertDoesNotExist()
    }

    @Test fun whitelistSwitchHasAnAccessibleName() {
        val draft = CallerDraft("100", "测试", "", null, false)
        compose.setContent { CallringTheme {
            CallerEditorScreen(LoadState(CallerEditorData(draft, emptyList(), null), loaded = true), draft,
                MutationState(), false, {}, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("白名单").performScrollTo().assertHasClickAction()
    }
}
