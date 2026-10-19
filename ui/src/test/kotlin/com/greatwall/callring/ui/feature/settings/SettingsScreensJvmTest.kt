package com.greatwall.callring.ui.feature.settings

import androidx.compose.ui.test.*
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.designsystem.DefaultAppearance
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreensJvmTest : SettingsScreenCases() {
    private val summary = SettingsSummary(false, false, QuietMode.OFF, null, "Asia/Shanghai",
        ScoreSettings(5, 2, 10, 30), DefaultAppearance, SnapshotMeta(0, 0))

    @Test fun settingsOverviewShowsTheInstalledVersionAndOpensTheUpdatePage() {
        val context = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        val expectedVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        var page: SettingsPage? = null
        compose.setContent { CallringTheme {
            SettingsOverviewScreen(LoadState(summary, loaded = true), MutationState(), null, {}, {}, {}, { page = it })
        } }
        compose.onNodeWithText("检查更新").performScrollTo().assertExists()
        if (expectedVersion != null) {
            compose.onNodeWithText("当前版本 $expectedVersion").assertExists()
        }
        compose.onNodeWithText("检查更新").performClick()
        compose.runOnIdle { assertEquals(SettingsPage.UPDATE, page) }
    }

    @Test fun backendQaPackageNamesHideTheUpdateEntry() {
        assertFalse(updateEntryVisible("com.greatwall.callring.backendqa"))
        assertTrue(updateEntryVisible("com.greatwall.callring"))
    }

    @Test fun installedVersionComesFromThePackageManager() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(installedVersionName(context), installedVersionName(context))
    }
}
