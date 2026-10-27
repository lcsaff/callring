package com.greatwall.callring.ui.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartupUpdateNavigationTest {
    @get:Rule val compose = createComposeRule()
    private var checks = 0
    private val available = UpdateStatus(AppVersionInfo("0.3.0", 3), UpdatePhase.UPDATE_AVAILABLE,
        AppReleaseInfo("0.4.0", 4, null, "修复问题", "update.apk", 100, "sha", "https://example.com/update.apk"))

    private fun show(restoration: StateRestorationTester? = null, beforeCheck: suspend () -> Unit = {}) {
        var status = available.copy(phase = UpdatePhase.IDLE, latest = null)
        val updates = object : AppUpdateApi {
            override suspend fun status() = ApiResult.Success(status)
            override suspend fun check(): ApiResult<UpdateStatus> {
                checks++; beforeCheck(); status = available
                return ApiResult.Success(available)
            }
            override suspend fun download(): ApiResult<UpdateStatus> = error("must be manual")
            override suspend fun cancel(): ApiResult<UpdateStatus> = error("must be manual")
            override suspend fun install(): ApiResult<UpdateStatus> = error("must be manual")
        }
        val api = object : UiFakeApi() { override val update = updates }
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            CallringApp(api, onCapability = {}, onPreviewCall = {}, onPinWidget = {}, onAppSettings = {})
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
    }

    @Test fun startupPromptsAndConfirmOpensUpdatePage() {
        show()
        compose.onNodeWithText("发现新版本").assertIsDisplayed()
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("发现新版本").assertDoesNotExist()
        compose.onNodeWithText("下载更新").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, checks) }
    }

    @Test fun closingPromptDoesNotRepeatWhenSwitchingPages() {
        show()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("发现新版本").assertDoesNotExist()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("tab-home").performClick()
        compose.onNodeWithText("发现新版本").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, checks) }
    }

    @Test fun alreadyOpenUpdatePageRefreshesWhenStartupCheckFinishes() {
        val finish = CompletableDeferred<Unit>()
        show { finish.await() }
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithText("检查更新").performScrollTo().performClick()
        compose.onNodeWithTag("update-primary-action").assertTextContains("检查更新")
        compose.runOnIdle { finish.complete(Unit) }
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("下载更新").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, checks) }
    }

    @Test fun restorationRetainsPromptAndDismissalWithoutAnotherCheck() {
        val restoration = StateRestorationTester(compose)
        show(restoration)
        compose.onNodeWithText("发现新版本").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("发现新版本").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, checks) }
        compose.onNodeWithText("关闭").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("发现新版本").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, checks) }
    }
}
