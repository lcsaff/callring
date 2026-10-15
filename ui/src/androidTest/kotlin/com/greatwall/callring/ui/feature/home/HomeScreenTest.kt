package com.greatwall.callring.ui.feature.home

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun noCallsDoesNotInventTodayStatisticsAndRuntimeActionWorks() {
        val fact = ClassificationFact(false, WorkReason.REST_DAY, null, false, "Asia/Shanghai", 1, null, 1)
        val runtime = RuntimeStatus(Capability.entries.associateWith { AccessState.DENIED }, Audibility.SILENT,
            PhoneActivity.IDLE, PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE), true, 0)
        val home = HomeData(RingtoneSettings(false, AudioSource.BUILT_IN, "内置铃声", 8), runtime,
            WorkStatus(0, fact, null), null, false, false, QuietMode.OFF, SnapshotMeta(0, 0))
        var runtimeCalls = 0
        var previews = 0
        var pins = 0
        compose.setContent { CallringTheme {
            HomeScreen(LoadState(home, loaded = true), MutationState(), onRetry = {}, onStatistics = {}, onOverlay = {}, onRingtone = {},
                onQuietSettings = {}, onRuntime = { runtimeCalls++ }, onRingtoneSettings = {}, onReport = {}, onSettings = {},
                latestPreview = LoadState(), onPreviewLatest = { previews++ }, onPinWidget = { pins++ })
        } }
        compose.onNodeWithText("暂无来电记录").assertExists()
        compose.onNodeWithText("今日来电").assertDoesNotExist()
        compose.onNodeWithText("显示最近来电卡片").performScrollTo().performClick()
        compose.onNodeWithText("添加桌面小组件").performScrollTo().performClick()
        compose.onNodeWithText("权限与运行条件").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, runtimeCalls); assertEquals(1, previews); assertEquals(1, pins) }
    }
}
