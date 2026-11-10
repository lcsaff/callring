package com.greatwall.callring.ui.feature.callers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.app.CallringApp
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.designsystem.ThemeDefinition
import com.greatwall.callring.ui.designsystem.ThemeSpec
import com.greatwall.callring.ui.designsystem.colorContrast
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState
import com.greatwall.callring.ui.testing.FixtureRecordingApi
import com.greatwall.callring.ui.testing.UiFakeApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

internal object CallHistoryScreenCases {
    fun rowActionsDoNotNavigateAndReturningFromDetailPreservesScroll(compose: ComposeContentTestRule) {
        val classification = ClassificationFact(true, WorkReason.WORKING, null, true, "UTC", 0, null, 0)
        val rows = (1L..40L).map { id ->
            val call = CallDetail(id, "300${id.toString().padStart(2, '0')}", 60_000L * (21 - id), null, null,
                CallResult.MISSED, CallDecision.NORMAL_RINGING, CallSource.SYSTEM_CALL_LOG, false,
                classification, classification, false, null)
            CallHistoryEntry(call, if (id == 1L)
                CallHistoryRecording(-71, CallArchiveAvailability.AVAILABLE, true, true)
            else CallHistoryRecording(null, CallArchiveAvailability.NONE, false, false),
                callerName = if (id == 1L) "测试来电人" else null,
                callerRegion = if (id == 1L) "上海" else null,
                callerSelfReportedPosition = if (id == 1L) "顾问" else null,
                callerConfiguredPosition = if (id == 1L) "经理" else null)
        }
        var displayedRows = rows
        var pageReads = 0
        val playbackActions = mutableListOf<Long>()
        val detailReads = mutableListOf<Long>()
        var playing: RecordingPlaybackSession? = null
        val api = object : UiFakeApi() {
            fun settingsChanged() { feed.publish(setOf(DataDomain.AI_SETTINGS)) }
            fun removeCall(id: Long) {
                displayedRows = displayedRows.filterNot { it.call.id == id }
                feed.publish(setOf(DataDomain.CALLS))
            }
            override suspend fun queryCallHistory(query: CallHistoryQuery): ApiResult<CallHistoryPage> {
                pageReads++
                return ApiResult.Success(CallHistoryPage(displayedRows, null, SnapshotMeta(0, 0)))
            }
            override suspend fun getCallHistoryDetail(callId: Long): ApiResult<CallHistoryEntry> {
                detailReads += callId
                return ApiResult.Success(rows.single { it.call.id == callId })
            }
            override val recordings = object : RecordingApi by FixtureRecordingApi {
                override suspend fun getPlaybackSession(): ApiResult<RecordingPlaybackSession?> = ApiResult.Success(playing)
                override suspend fun playOwned(callId: Long, token: String): ApiResult<Unit> {
                    playbackActions += callId
                    playing = RecordingPlaybackSession(callId, token)
                    return ApiResult.Success(Unit)
                }
                override suspend fun stopPlaybackSession(token: String): ApiResult<Unit> {
                    if (playing?.token == token) {
                        playbackActions += 0L
                        playing = null
                    }
                    return ApiResult.Success(Unit)
                }
                override suspend fun getArchive(callId: Long): ApiResult<CallArchive?> =
                    ApiResult.Success(if (callId == -71L) CallArchive(callId, "30001", 1, transcript = "对应来电全文") else null)
            }
        }
        compose.setContent { CallringApp(api, onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("tab-callers").performClick()
        compose.onNodeWithText("通话记录").performClick()
        compose.onNodeWithText("测试来电人 · 上海 · 顾问").assertIsDisplayed()
        compose.onNodeWithText("30001").assertDoesNotExist()
        compose.onNodeWithText("1970-01-01 00:20:00").assertIsDisplayed()
        compose.onNodeWithText("未接听").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "工作时间内")).onFirst().assertExists()
        compose.onNodeWithContentDescription("播放录音").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithContentDescription("停止录音").performTouchInput { click() }
        compose.onNodeWithContentDescription("查看转写").performTouchInput { click() }
        compose.onNodeWithText("对应来电全文").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle {
            assertEquals(listOf(-71L, 0L), playbackActions)
            assertEquals(emptyList<Long>(), detailReads)
        }
        compose.runOnIdle { api.settingsChanged() }
        compose.runOnIdle { assertEquals(1, pageReads) }
        compose.onNodeWithTag("call-history-list").performScrollToKey(15L)
        compose.onNodeWithText("30015").performClick()
        compose.onNodeWithText("时长：未知").assertIsDisplayed()
        compose.onNodeWithText("号码：30015").assertIsDisplayed()
        compose.onNodeWithText("1970-01-01 00:06:00").assertIsDisplayed()
        compose.onNodeWithText("工作分类：工作时间").assertIsDisplayed()
        compose.onNodeWithText("未接听").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(15L), detailReads) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("30015").assertIsDisplayed()
        compose.runOnIdle { api.removeCall(15L) }
        compose.onNodeWithText("测试来电人 · 上海 · 顾问").assertIsDisplayed()
    }

    fun datesAndActionsRemainUsableInNarrowLargeType(compose: ComposeContentTestRule) {
        val dark = mutableStateOf(false)
        val theme = ThemeDefinition.fromSeed("test", "测试", 0x5566BB)
        val classification = ClassificationFact(false, WorkReason.UNKNOWN, null, false, "Asia/Shanghai", 0, null, 0)
        val call = CallDetail(1, "30001", 0, null, null, CallResult.MISSED, CallDecision.NORMAL_RINGING,
            CallSource.SYSTEM_CALL_LOG, false, classification, classification, false, null)
        val longName = "很长的测试来电人姓名".repeat(5)
        val rows = listOf(
            CallHistoryEntry(call, CallHistoryRecording(-1, CallArchiveAvailability.AVAILABLE, true, true),
                callerName = longName),
            CallHistoryEntry(call.copy(id = 2, number = "30002", startedAtEpochMillis = -1_000L,
                classification = classification.copy(working = true, reason = WorkReason.WORKING)),
                CallHistoryRecording(null, CallArchiveAvailability.INDEXING, false, false)),
            CallHistoryEntry(call.copy(id = 3, number = "30003", startedAtEpochMillis = -2_000L,
                classification = classification.copy(reason = WorkReason.OUTSIDE_WORK_HOURS)),
                CallHistoryRecording(null, CallArchiveAvailability.UNREADABLE, false, false)),
            CallHistoryEntry(call.copy(id = 4, number = "30004", startedAtEpochMillis = -3_000L),
                CallHistoryRecording(-4, CallArchiveAvailability.AVAILABLE, false, true)),
        )
        val actions = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                CallringTheme(ThemeSpec(theme, dark.value)) {
                    Box(Modifier.width(280.dp)) {
                        CallHistoryScreen(LoadState(CallHistoryPage(rows, null, SnapshotMeta(0, 0)), loaded = true),
                            LoadState(), MutationState(), {}, {}, {}, {}, { actions += "detail:$it" },
                            { archive, _ -> actions += "play:$archive" }, { actions += "transcript:${it.call.id}" })
                    }
                }
            }
        }

        val list = compose.onNodeWithTag("call-history-list")
        val expectedDates = listOf(
            "1970-01-01 08:00:00" to "工作状态未知",
            "1970-01-01 07:59:59" to "工作时间内",
            "1970-01-01 07:59:58" to "工作时间外",
        )
        for (darkMode in listOf(false, true)) {
            compose.runOnIdle { dark.value = darkMode }
            val surface = (if (darkMode) theme.dark else theme.light).surface
            for ((index, expected) in expectedDates.withIndex()) {
                val (date, workState) = expected
                list.performScrollToKey((index + 1).toLong())
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(date, useUnmergedTree = true).assertIsDisplayed()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, workState))
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertFalse("$date / $workState size=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
                    "lines=${layout.lineCount}, overflowW=${layout.didOverflowWidth}, overflowH=${layout.didOverflowHeight}, " +
                    "lastVisible=${layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)}", layout.hasVisualOverflow)
                assertTrue(colorContrast(layout.layoutInput.style.color, surface) >= 4.5)
            }
        }
        list.performScrollToKey(1L)
        compose.onNodeWithContentDescription("播放录音").assertIsDisplayed().performTouchInput { click() }
        compose.onAllNodesWithContentDescription("查看转写").onFirst().assertIsDisplayed()
            .performTouchInput { click() }
        list.performScrollToKey(2L)
        compose.onAllNodesWithContentDescription("正在读取归档状态").onFirst().assertIsNotEnabled()
        list.performScrollToKey(3L)
        compose.onAllNodesWithContentDescription("归档无法读取").onFirst().assertIsNotEnabled()
        list.performScrollToKey(4L)
        compose.onNodeWithContentDescription("无可播放录音").assertIsNotEnabled()
        compose.onAllNodesWithContentDescription("查看转写").onLast().performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("play:-1", "transcript:1", "transcript:4"), actions) }
    }
}
