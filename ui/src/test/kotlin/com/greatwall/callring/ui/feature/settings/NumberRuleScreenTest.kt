package com.greatwall.callring.ui.feature.settings

import androidx.compose.ui.test.*
import androidx.compose.runtime.mutableStateOf
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
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) { NumberRuleScreen(LoadState(NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("", 5)), 0), loaded = true),
            listOf(NumberRuleDraft(1, "", "5")), MutationState(), emptyMap(), false, "", null, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, { saves++ }, {}, {}, {}) } }
        compose.onNodeWithText("不限前缀").assertExists()
        compose.onNodeWithText("已有记录不重算；后续同步可能补入历史通话").assertExists()
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithText("保存规则").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }
    @Test fun conflictOffersExplicitReloadAndKeepsDraftVisible() {
        var reloads = 0
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) { NumberRuleScreen(LoadState(NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("8", 6)), 2), loaded = true),
            listOf(NumberRuleDraft(1, "9", "5")), MutationState(), mapOf("rules[0].prefix" to "请输入数字前缀"), true, "", null,
            {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, { reloads++ }, {}, {}) } }
        compose.onNodeWithText("规则已被其他操作更新，当前输入已保留").assertExists()
        compose.onNodeWithText("请输入数字前缀").assertExists()
        compose.onNodeWithText("重新加载规则").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, reloads) }
    }
    @Test fun loadFailureOffersRetryWithoutRenderingEditableRule() {
        var retries = 0
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) { NumberRuleScreen(LoadState(error = ApiError(ErrorCode.STORAGE_FAILURE, "规则读取失败")),
            null, MutationState(), emptyMap(), false, "", null, {}, { retries++ }, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("规则读取失败").assertExists()
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("保存规则").assertDoesNotExist()
    }
    @Test fun staleSaveOffersReloadEvenBeforeFeedRefresh() {
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) {
            NumberRuleScreen(LoadState(NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("3", 5)), 0), loaded = true), listOf(NumberRuleDraft(1, "9", "5")),
                MutationState(error = ApiError(ErrorCode.CONFLICT, "规则已更新")), emptyMap(), false, "", null,
                {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("重新加载规则").assertExists()
    }
    @Test fun eachRowPlacesPrefixAndLengthSideBySideAndSupportsAddRemove() {
        var adds = 0; var removed: Long? = null
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) {
            NumberRuleScreen(LoadState(NumberRuleSettings(emptyList(), 0), loaded = true),
                listOf(NumberRuleDraft(1, "010", "11"), NumberRuleDraft(2, "138", "11")), MutationState(), emptyMap(), false, "", null,
                {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, { adds++ }, { removed = it })
        } }
        listOf("1", "2").forEach { id ->
            val prefix = compose.onNodeWithTag("rule.$id.prefix").fetchSemanticsNode().boundsInRoot
            val length = compose.onNodeWithTag("rule.$id.length").fetchSemanticsNode().boundsInRoot
            assertEquals(prefix.top, length.top, 1f); assertTrue(prefix.right <= length.left)
        }
        compose.onNodeWithContentDescription("添加规则").performScrollTo().performClick()
        compose.onNodeWithContentDescription("删除规则 1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, adds); assertEquals(1L, removed) }
    }

    @Test fun emptyGroupExplainsNoMatchesAndAllowsExplicitSave() {
        var saves = 0
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) {
            NumberRuleScreen(LoadState(NumberRuleSettings(emptyList(), 0), loaded = true), emptyList(), MutationState(), emptyMap(), false, "", null,
                {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, { saves++ }, {}, {}, {})
        } }
        compose.onNodeWithText("不会匹配任何号码").assertExists()
        compose.onNodeWithText("保存规则").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun multiDigitPrefixesStayEditableAndRemovingEarlierRowRetainsFocus() {
        val rows = mutableStateOf(listOf(NumberRuleDraft(1, "", "11"), NumberRuleDraft(2, "", "11")))
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) {
            NumberRuleScreen(LoadState(NumberRuleSettings(emptyList(), 0), loaded = true), rows.value, MutationState(), emptyMap(), false, "", null,
                {}, {}, { id, value -> rows.value = rows.value.map { if (it.id == id) it.copy(prefix = value) else it } },
                { id, value -> rows.value = rows.value.map { if (it.id == id) it.copy(length = value) else it } },
                {}, {}, {}, {}, {}, { id -> rows.value = rows.value.filterNot { it.id == id } })
        } }
        compose.onNodeWithTag("rule.1.prefix").performScrollTo().performClick().performTextInput("010")
        compose.onNodeWithTag("rule.1.prefix").assertTextContains("010")
        compose.onNodeWithTag("rule.2.prefix").performScrollTo().performClick().performTextInput("138")
        compose.onNodeWithTag("rule.2.prefix").assertTextContains("138")
        compose.runOnIdle { rows.value = rows.value.drop(1) }
        compose.onNodeWithTag("rule.2.prefix").assertIsFocused().assertTextContains("138")
        compose.onNodeWithTag("rule.2.prefix").performTextInput("99")
        compose.onNodeWithTag("rule.2.prefix").assertTextContains("13899")
    }

}
