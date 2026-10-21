package com.greatwall.callring.ui.feature.home

import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun homeShowsDayAndLeavesWithoutSettingsOrRecentCallActions() {
        val fact = ClassificationFact(false, WorkReason.REST_DAY, null, false, "Asia/Shanghai", 1, null, 1)
        val runtime = RuntimeStatus(Capability.entries.associateWith { AccessState.DENIED }, Audibility.SILENT,
            PhoneActivity.IDLE, PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE), true, 0)
        val home = HomeData(RingtoneSettings(false, AudioSource.BUILT_IN, "内置铃声", 8), runtime,
            WorkStatus(0, fact, null), null, false, false, QuietMode.OFF, SnapshotMeta(0, 0))
        var leaves = 0
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            HomeScreen(LoadState(home, loaded = true), onRetry = {}, onLeaves = { leaves++ })
        } }
        compose.onNodeWithText("休息日").assertExists()
        compose.onNodeWithText("今日来电").assertDoesNotExist()
        compose.onNodeWithText("最近来电").assertDoesNotExist()
        compose.onNodeWithText("添加桌面小组件").assertDoesNotExist()
        compose.onNodeWithText("请假管理").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, leaves) }
    }
}
