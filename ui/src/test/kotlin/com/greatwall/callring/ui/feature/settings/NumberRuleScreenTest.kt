package com.greatwall.callring.ui.feature.settings

import androidx.compose.ui.test.*
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NumberRuleScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun emptyPrefixAndHistoryExplanationAreVisibleAndSaveIsExplicit() {
        var saves = 0
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) { NumberRuleScreen(LoadState(NumberRuleSettings("", 5, 0), loaded = true),
            NumberRuleDraft("", "5"), MutationState(), emptyMap(), false, "", null, {}, {}, {}, {}, {}, {}, { saves++ }, {}) } }
        compose.onNodeWithText("不限前缀").assertExists()
        compose.onNodeWithText("已有记录不重算；后续同步可能补入历史通话").assertExists()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithText("保存规则").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }
    @Test fun conflictOffersExplicitReloadAndKeepsDraftVisible() {
        var reloads = 0
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) { NumberRuleScreen(LoadState(NumberRuleSettings("8", 6, 2), loaded = true),
            NumberRuleDraft("9", "5"), MutationState(), mapOf("prefix" to "请输入数字前缀"), true, "", null,
            {}, {}, {}, {}, {}, {}, {}, { reloads++ }) } }
        compose.onNodeWithText("规则已被其他操作更新，当前输入已保留").assertExists()
        compose.onNodeWithText("请输入数字前缀").assertExists()
        compose.onNodeWithText("重新加载规则").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reloads) }
    }
    @Test fun loadFailureOffersRetryWithoutRenderingEditableRule() {
        var retries = 0
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) { NumberRuleScreen(LoadState(error = ApiError(ErrorCode.STORAGE_FAILURE, "规则读取失败")),
            null, MutationState(), emptyMap(), false, "", null, {}, { retries++ }, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("规则读取失败").assertExists()
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("保存规则").assertDoesNotExist()
    }
    @Test fun staleSaveOffersReloadEvenBeforeFeedRefresh() {
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) {
            NumberRuleScreen(LoadState(NumberRuleSettings("3", 5, 0), loaded = true), NumberRuleDraft("9", "5"),
                MutationState(error = ApiError(ErrorCode.CONFLICT, "规则已更新")), emptyMap(), false, "", null,
                {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("重新加载规则").assertExists()
    }
}
