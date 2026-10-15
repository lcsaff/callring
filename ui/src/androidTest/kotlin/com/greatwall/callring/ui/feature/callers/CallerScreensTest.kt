package com.greatwall.callring.ui.feature.callers

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CallerScreensTest {
    @get:Rule val compose = createComposeRule()

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
            CallerListScreen(LoadState(Page(emptyList(), 0, 50, 0, SnapshotMeta(1, 0)), loaded = true),
                query = CallerSearch(), onSearch = {}, onPage = {}, onRetry = {}, onAdd = { addCalls++ }, onCaller = {}, onLookup = {})
        } }
        compose.onNodeWithText("暂无匹配档案").assertExists()
        compose.onNodeWithText("新增").performClick()
        compose.runOnIdle { assertEquals(1, addCalls) }
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
