package com.greatwall.callring.ui.app

import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.feature.home.HomeScreen
import com.greatwall.callring.ui.feature.settings.*
import com.greatwall.callring.ui.state.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeStructureJvmTest {
    @get:Rule val compose = createComposeRule()

    @Test fun holidayNameRemainsVisibleDuringLeaveAndLeaveEntryWorks() {
        val home = runBlocking { (UiFakeApi().load() as ApiResult.Success).value }
        val fact = home.currentWork.classification.copy(reason = WorkReason.PERSONAL_LEAVE)
        val data = home.copy(currentWork = WorkStatus(0, fact, null, WorkDayKind.HOLIDAY, "国庆节"))
        var leaves = 0
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) { HomeScreen(LoadState(data, loaded = true), {}, { leaves++ }) } }
        compose.onNodeWithText("法定节假日").assertIsDisplayed()
        compose.onNodeWithText("国庆节").assertIsDisplayed()
        compose.onNodeWithText("请假中 · 当前不工作").assertIsDisplayed()
        compose.onNodeWithText("请假管理").performClick()
        compose.runOnIdle { assertEquals(1, leaves) }
    }

    @Test fun settingsContainsConfigurationAndNoLeaveOrPositionManagement() {
        val summary = runBlocking { (UiFakeApi().getSummary() as ApiResult.Success).value }
        var page: SettingsPage? = null
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            SettingsOverviewScreen(LoadState(summary, loaded = true), MutationState(), null, {}, {}, {}, { page = it })
        } }
        compose.onNodeWithText("职位管理").assertDoesNotExist()
        compose.onNodeWithText("请假管理").assertDoesNotExist()
        compose.onNodeWithText("我的职位").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(SettingsPage.MY_POSITION, page) }
        compose.onNodeWithText("节假日同步").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(SettingsPage.CALENDAR, page) }
    }

    @Test fun homeLeavesAndCallerPositionsReturnToTheirOwnTabs() {
        compose.setContent { CallringApp(UiFakeApi(), onCapability = {}, onPreviewCall = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("home-leaves").performScrollTo().performClick()
        compose.onNodeWithText("添加请假").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("home-status").assertExists()
        compose.onNodeWithTag("tab-callers").performClick()
        compose.onNodeWithContentDescription("职位管理").performClick()
        compose.onNodeWithText("添加职位").assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("tab-callers").assertIsSelected()
    }

    @Test fun personalPositionSelectionDoesNotExposeListEditing() {
        val option = PositionOption(1, "工程师", 2, true, PositionRelation.UNKNOWN)
        var selected: Long? = null
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            MyPositionScreen(LoadState(PositionSettings(listOf(option), null), loaded = true), MutationState(), {}, {}, { selected = it })
        } }
        compose.onNodeWithText("添加职位").assertDoesNotExist()
        compose.onNodeWithText("工程师").performClick()
        compose.runOnIdle { assertEquals(1L, selected) }
    }
}
