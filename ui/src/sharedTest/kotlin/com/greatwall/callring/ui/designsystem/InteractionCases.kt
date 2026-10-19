package com.greatwall.callring.ui.designsystem

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.greatwall.callring.ui.app.CallringApp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import com.greatwall.callring.ui.feature.shared.MutationFeedback
import com.greatwall.callring.ui.feature.settings.SettingsOverviewScreen
import com.greatwall.callring.ui.feature.settings.ScoreSettingsScreen
import com.greatwall.callring.ui.feature.callers.CallerEditorScreen
import com.greatwall.callring.ui.state.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

abstract class InteractionCases {
    @get:Rule val compose = createComposeRule()

    @Test fun successfulMutationUsesTransientSnackbarAndDoesNotReplayAfterRestore() {
        val mutation = mutableStateOf(MutationState())
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CallringTheme { ScreenFrame("反馈") { MutationFeedback(mutation.value) } } }
        compose.runOnIdle { mutation.value = MutationState(completed = 1, message = "已保存") }
        compose.onNodeWithTag("feedback-snackbar").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("feedback-snackbar").assertDoesNotExist()
    }

    @Test fun returningToCompletedMutationDoesNotAnnounceHistoricalSuccess() {
        compose.setContent { CallringTheme {
            ScreenFrame("反馈") { MutationFeedback(MutationState(completed = 3, message = "操作成功")) }
        } }
        compose.onNodeWithText("操作成功").assertDoesNotExist()
    }

    @Test fun settingCanBeToggledByTappingItsLabelExactlyOnce() {
        val api = UiFakeApi()
        val summary = runBlocking { api.getSummary().value }
        var changes = 0
        compose.setContent { CallringTheme {
            SettingsOverviewScreen(LoadState(summary, loaded = true), MutationState(), {}, {},
                { changes++ }, {}, {})
        } }
        compose.onNodeWithText("来电统计").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, changes) }
    }

    @Test fun resetNeedsConfirmationAndCancelDoesNotWrite() {
        val score = ScoreSettings(5, 2, 10, 30)
        var resets = 0
        compose.setContent { CallringTheme {
            ScoreSettingsScreen(LoadState(score, loaded = true), score, MutationState(), {}, {}, {}, { resets++ })
        } }
        compose.onNodeWithText("恢复默认评分设置").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, resets) }
        compose.onNodeWithText("恢复默认评分设置").performScrollTo().performClick()
        compose.onNodeWithText("确认").performClick()
        compose.runOnIdle { assertEquals(1, resets) }
    }

    @Test fun dirtyEditorBackKeepsDraftUntilDiscardIsConfirmed() {
        val original = CallerDraft("100", "原姓名", "部门", null, false)
        val draft = mutableStateOf(original)
        var backs = 0
        compose.setContent { CallringTheme {
            CallerEditorScreen(LoadState(CallerEditorData(original, emptyList(), null), loaded = true),
                draft.value, MutationState(), false, { backs++ }, {}, { draft.value = it }, {})
        } }
        compose.onNode(hasSetTextAction() and hasText("姓名")).performTextReplacement("修改姓名")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertEquals(0, backs) }
        compose.onNodeWithText("取消").performClick()
        compose.onNode(hasSetTextAction() and hasText("姓名")).assertTextContains("修改姓名")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun savingOnExitWaitsForSuccessAndFailureKeepsTheFormOpen() {
        val original = CallerDraft("100", "原姓名", "部门", null, false)
        val draft = mutableStateOf(original.copy(name = "待保存"))
        val mutation = mutableStateOf(MutationState())
        var backs = 0
        compose.setContent { CallringTheme {
            CallerEditorScreen(LoadState(CallerEditorData(original, emptyList(), null), loaded = true),
                draft.value, mutation.value, false, { backs++ }, {}, { draft.value = it },
                { mutation.value = MutationState(busy = true) })
        } }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals(0, backs)
            mutation.value = MutationState(error = ApiError(ErrorCode.STORAGE_FAILURE, "保存失败"))
        }
        compose.onNodeWithText("保存失败").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, backs) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { mutation.value = MutationState(completed = 1, message = "档案已保存") }
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun navigationAdaptsToWindowSizeAndPreservesSelectedDestination() {
        val size = mutableStateOf(DpSize(400.dp, 800.dp))
        val api = UiFakeApi()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size.value)) {
                CallringApp(api, onCapability = {}, onPreviewCall = {}, onPinWidget = {}, onAppSettings = {})
            }
        }
        compose.onNodeWithTag("tab-settings").performClick().assertIsSelected()
        val barHome = compose.onNodeWithTag("tab-home").getUnclippedBoundsInRoot()
        val barSettings = compose.onNodeWithTag("tab-settings").getUnclippedBoundsInRoot()
        assertEquals(barHome.top.value, barSettings.top.value, 1f)
        compose.runOnIdle { size.value = DpSize(1000.dp, 800.dp) }
        compose.onNodeWithTag("tab-settings").assertIsSelected()
        val railHome = compose.onNodeWithTag("tab-home").getUnclippedBoundsInRoot()
        val railSettings = compose.onNodeWithTag("tab-settings").getUnclippedBoundsInRoot()
        assertEquals(railHome.left.value, railSettings.left.value, 1f)
        org.junit.Assert.assertTrue(railSettings.top > railHome.top)
        compose.runOnIdle { size.value = DpSize(400.dp, 800.dp) }
        compose.onNodeWithTag("tab-settings").assertIsSelected()
    }

    @Test fun saveAndExitKeepsSuccessFeedbackVisibleOnTheDestinationScreen() {
        val api = UiFakeApi()
        compose.setContent { CallringApp(api, onCapability = {}, onPreviewCall = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("tab-callers").performClick()
        compose.onNodeWithText("测试档案").performClick()
        compose.onNodeWithText("编辑档案").performClick()
        compose.onNode(hasSetTextAction() and hasText("姓名")).performTextReplacement("离开前保存")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("号码报表").assertExists()
        compose.onNodeWithTag("feedback-snackbar").assertIsDisplayed()
        compose.onNodeWithText("档案已保存").assertIsDisplayed()
        compose.runOnIdle { assertEquals("离开前保存", api.savedProfile?.name) }
    }
}
