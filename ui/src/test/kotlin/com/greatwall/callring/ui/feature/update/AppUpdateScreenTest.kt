package com.greatwall.callring.ui.feature.update

import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateScreenTest {
    @get:Rule val compose = createComposeRule()

    private val current = AppVersionInfo("0.3.0", 3)
    private val release = AppReleaseInfo("0.4.0", 4, 0L, "修复若干问题", "callring-0.4.0.apk",
        12_000_000L, "sha", "https://example.com/apk")
    private fun status(phase: UpdatePhase, failure: String? = null) =
        UpdateStatus(current, phase, latest = release, downloadedBytes = 3_000_000L, totalBytes = 12_000_000L,
            failureReason = failure)

    @Test fun everyPhaseExposesExactlyOnePrimaryActionOrNone() {
        val expectedActions = mapOf(
            UpdatePhase.IDLE to "检查更新",
            UpdatePhase.UP_TO_DATE to "重新检查",
            UpdatePhase.NO_INSTALLABLE to "重新检查",
            UpdatePhase.UPDATE_AVAILABLE to "下载更新",
            UpdatePhase.CHECK_FAILED to "重试",
            UpdatePhase.DOWNLOADING to "取消下载",
            UpdatePhase.VERIFIED to "安装",
            UpdatePhase.VERIFY_FAILED to "重新下载",
            UpdatePhase.INSTALL_PERMISSION_REQUIRED to "去授权",
            UpdatePhase.INSTALL_CANCELLED to "重新安装",
            UpdatePhase.INSTALL_PENDING_VERIFY to "重新检查",
        )
        val page = mutableStateOf(status(UpdatePhase.IDLE))
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            AppUpdateScreen(LoadState(page.value, loaded = true), MutationState(), {}, {}, {}, {}, {}, {})
        } }
        UpdatePhase.entries.forEach { phase ->
            compose.runOnIdle { page.value = status(phase) }
            compose.waitForIdle()
            val action = expectedActions[phase]
            if (action == null) {
                compose.onNodeWithTag("update-primary-action").assertDoesNotExist()
            } else {
                compose.onNodeWithTag("update-primary-action").assertExists()
                compose.onNodeWithText(action).assertExists()
            }
        }
    }

    @Test fun installIsNeverOfferedBeforeVerificationSucceeds() {
        val page = mutableStateOf(status(UpdatePhase.IDLE))
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            AppUpdateScreen(LoadState(page.value, loaded = true), MutationState(), {}, {}, {}, {}, {}, {})
        } }
        listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.VERIFY_FAILED,
            UpdatePhase.CHECK_FAILED, UpdatePhase.INSTALL_PERMISSION_REQUIRED, UpdatePhase.INSTALL_CANCELLED,
            UpdatePhase.INSTALL_PENDING_VERIFY).forEach { phase ->
            compose.runOnIdle { page.value = status(phase) }
            compose.waitForIdle()
            compose.onNodeWithText("安装", useUnmergedTree = true).assertDoesNotExist()
        }
        compose.runOnIdle { page.value = status(UpdatePhase.VERIFIED) }
        compose.waitForIdle()
        compose.onNodeWithTag("update-primary-action").assertExists()
        compose.onNodeWithText("安装").assertExists()
    }

    @Test fun availableReleaseShowsVersionNotesAndSize() {
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            AppUpdateScreen(LoadState(status(UpdatePhase.UPDATE_AVAILABLE), loaded = true), MutationState(), {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("当前版本：0.3.0（3）").assertExists()
        compose.onNodeWithText("可用版本：0.4.0（4）").assertExists()
        compose.onNodeWithText("更新说明：修复若干问题").assertExists()
        compose.onNodeWithText("安装包：callring-0.4.0.apk · 11.4 MB").assertExists()
        compose.onNodeWithTag("update-primary-action").assertExists()
    }

    @Test fun downloadingShowsByteProgressAndCancel() {
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            AppUpdateScreen(LoadState(status(UpdatePhase.DOWNLOADING), loaded = true), MutationState(), {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("已下载 2.9 MB / 11.4 MB").assertExists()
        compose.onNodeWithText("取消下载").assertExists()
    }

    @Test fun checkAndVerificationFailuresKeepRetryActionsAndReasons() {
        val page = mutableStateOf(status(UpdatePhase.CHECK_FAILED, failure = "update.check_failed"))
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            AppUpdateScreen(LoadState(page.value, loaded = true), MutationState(), {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("检查更新失败，请检查网络后重试。").assertExists()
        compose.onNodeWithText("重试").assertExists()
        compose.runOnIdle { page.value = status(UpdatePhase.VERIFY_FAILED, failure = "update.verify_failed") }
        compose.waitForIdle()
        compose.onNodeWithText("安装包校验未通过，请重新下载。").assertExists()
        compose.onNodeWithText("重新下载").assertExists()
    }

    @Test fun noInstallableOffersTheReleasePageEntry() {
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            AppUpdateScreen(LoadState(status(UpdatePhase.NO_INSTALLABLE), loaded = true), MutationState(), {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("该版本尚未提供可安装更新。").assertExists()
        compose.onNodeWithText("打开 Release 网页手动下载").assertExists()
        compose.onNodeWithText("重新检查").assertExists()
    }

    @Test fun upToDateCanCheckAgain() {
        var checks = 0
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            AppUpdateScreen(LoadState(status(UpdatePhase.UP_TO_DATE), loaded = true), MutationState(),
                {}, {}, { checks++ }, {}, {}, {})
        } }
        compose.onNodeWithText("当前已是最新版本，无需更新。").assertExists()
        compose.onNodeWithText("重新检查").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, checks) }
    }

    @Test fun statusReadFailureShowsRetryWithoutAPhase() {
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            AppUpdateScreen(LoadState(error = ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "update.unavailable")), MutationState(),
                {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("当前环境未提供应用更新服务。").assertExists()
        compose.onNodeWithTag("update-primary-action").assertExists()
        compose.onNodeWithText("重试").assertExists()
    }

    @Test fun sizeFormattingUsesBinaryUnits() {
        assertEquals("999 B", formatSize(999))
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("11.4 MB", formatSize(12_000_000))
    }
}
