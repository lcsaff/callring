package com.greatwall.callring.ui.feature.home

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.feature.settings.SettingsOverviewScreen
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
class HomeQuietScreenJvmTest {
    @get:Rule val compose = createComposeRule()

    @Test fun quietCardHasCustomizeLeftOfSwitchAndBetweenStatusAndLeaves() {
        val home = runBlocking { (UiFakeApi().load() as ApiResult.Success).value }
        var toggled = false
        var opened = false
        compose.setContent { CallringTheme {
            HomeScreen(LoadState(home, loaded = true), {}, {}, quietCard = {
                HomeQuietCard(LoadState(QuietSettings(QuietMode.OFF, false, emptyMap()), loaded = true),
                    MutationState(), {}, { toggled = it }, { opened = true })
            })
        } }
        val custom = compose.onNodeWithText("自定义").fetchSemanticsNode().boundsInRoot
        val toggle = compose.onNodeWithTag("home-quiet-switch").fetchSemanticsNode().boundsInRoot
        assertTrue(custom.right <= toggle.left)
        assertTrue(compose.onNodeWithTag("home-status").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithTag("home-quiet").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("自定义").performClick()
        compose.onNodeWithTag("home-quiet-switch").performClick()
        compose.runOnIdle { assertTrue(opened); assertTrue(toggled) }
    }

    @Test @Config(qualifiers = "w640dp-h360dp")
    fun emptyCustomBlocksSaveAndSmallDialogScrollsToSundayWithActionsVisible() {
        var value by mutableStateOf(QuietSettings(QuietMode.CUSTOM, false, emptyMap()))
        var saves = 0
        compose.setContent { CallringTheme {
            HomeQuietDialog(value, value, MutationState(), { value = it }, { saves++ }, {})
        } }
        compose.onNodeWithTag("quiet-dialog-save").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("quiet-dialog-cancel").assertIsDisplayed()
        compose.onNodeWithText("星期日").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("添加时段").onLast().performScrollTo().performClick()
        compose.onNodeWithTag("quiet-dialog-save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun dirtyCancelOffersDiscardOrContinueWithoutSaving() {
        val saved = QuietSettings(QuietMode.OFF, false, emptyMap())
        var value by mutableStateOf(saved.copy(mode = QuietMode.CUSTOM))
        var closes = 0
        var saves = 0
        compose.setContent { CallringTheme {
            HomeQuietDialog(saved, value, MutationState(), { value = it }, { saves++ }, { closes++ })
        } }
        compose.onNodeWithTag("quiet-dialog-cancel").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle { assertEquals(1, closes); assertEquals(0, saves) }
    }

    @Test fun personalInformationPrecedesCallHandlingAndQuietEntryIsRemoved() {
        val summary = runBlocking { (UiFakeApi().getSummary() as ApiResult.Success).value }
        compose.setContent { CallringTheme {
            SettingsOverviewScreen(LoadState(summary, loaded = true), MutationState(), null, {}, {}, {}, {})
        } }
        compose.onNodeWithText("免打扰").assertDoesNotExist()
        assertTrue(compose.onNodeWithText("个人信息").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText("来电处理").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun failedSaveShowsErrorAndKeepsCrossMidnightWindowsEditable() {
        val saved = QuietSettings(QuietMode.OFF, false, emptyMap())
        var value by mutableStateOf(QuietSettings(QuietMode.CUSTOM, true,
            mapOf(1 to listOf(TimeWindow(1320, 420), TimeWindow(480, 540)))))
        var mutation by mutableStateOf(MutationState())
        compose.setContent { CallringTheme {
            HomeQuietDialog(saved, value, mutation, { value = it }, {
                mutation = MutationState(error = ApiError(ErrorCode.STORAGE_FAILURE, "磁盘不可用"))
            }, {})
        } }
        compose.onNodeWithTag("quiet-dialog-save").performClick()
        compose.onNodeWithText("磁盘不可用").assertExists()
        compose.onNodeWithText("跨至次日").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("移除时段 2").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(2, value.weeklyWindows[1]!!.size); assertTrue(value.allNumbers) }
    }
}
