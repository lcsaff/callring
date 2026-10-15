package com.greatwall.callring.ui.widget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.application.api.ApiError
import com.greatwall.callring.application.api.ErrorCode
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.MutationState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WidgetConfigurationScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingDirectNumberRequiresExplicitSaveAndCancelNeverSaves() {
        var state by mutableStateOf(WidgetConfigurationState())
        var saves = 0
        var cancelled = false
        compose.setContent {
            CallringTheme {
                WidgetConfigurationScreen(state,
                    onQueryChange = { state = state.copy(query = it, selectedNumber = null) },
                    onUseDirectNumber = { state = state.copy(selectedNumber = state.query) },
                    onSelectNumber = {}, onSearch = {}, onSave = { saves++ }, onCancel = { cancelled = true })
            }
        }
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("搜索号码或档案").performTextInput("100")
        compose.onNodeWithText("使用输入的号码").performClick()
        compose.onNodeWithText("保存").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(cancelled); assertEquals(0, saves) }
    }

    @Test fun bindingFailureStillAllowsDirectNumberRecovery() {
        var state by mutableStateOf(WidgetConfigurationState(bindingError = ApiError(ErrorCode.STORAGE_FAILURE, "读取失败")))
        var saves = 0
        compose.setContent {
            CallringTheme {
                WidgetConfigurationScreen(state,
                    onQueryChange = { state = state.copy(query = it) },
                    onUseDirectNumber = { state = state.copy(selectedNumber = state.query) },
                    onSelectNumber = {}, onSearch = {}, onSave = { saves++ }, onCancel = {})
            }
        }
        compose.onNodeWithText("原绑定读取失败，仍可重新选择号码：读取失败").assertIsDisplayed()
        compose.onNodeWithText("搜索号码或档案").performTextInput("100")
        compose.onNodeWithText("使用输入的号码").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }
    @Test fun committedSaveCannotBeCancelledOrRepeatedWhileHostRefreshes() {
        compose.setContent { CallringTheme {
            WidgetConfigurationScreen(WidgetConfigurationState(selectedNumber = "100", mutation = MutationState(completed = 1)),
                {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("取消").assertIsNotEnabled()
        compose.onNodeWithText("保存").assertIsNotEnabled()
    }
}
