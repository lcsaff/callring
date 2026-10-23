package com.greatwall.callring.ui.widget

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.designsystem.CallringTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetNumberRuleScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun failureKeepsInputAndDisablesSelectionAndSaveUntilRetry() {
        var state by mutableStateOf(WidgetConfigurationState(query = "812345", selectedNumber = "812345",
            ruleReady = false, ruleError = ApiError(ErrorCode.STORAGE_FAILURE, "规则读取失败")))
        var retries = 0; var saves = 0
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) {
            WidgetConfigurationScreen(state, { state = state.copy(query = it) }, {}, {}, {}, {}, { saves++ }, { retries++ })
        } }
        compose.onNodeWithText("812345", substring = false).assertExists()
        compose.onNodeWithText("保存", substring = false).assertIsNotEnabled()
        compose.onNodeWithText("使用输入的号码").assertIsNotEnabled()
        compose.onNodeWithText("重试号码规则").performClick()
        compose.runOnIdle { assertEquals(1, retries); state = state.copy(ruleReady = true, ruleError = null) }
        compose.onNodeWithText("保存", substring = false).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }
}
