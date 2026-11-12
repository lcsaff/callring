package com.greatwall.callring.ui.feature.report

import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.change.InvalidationToken
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.ui.app.CallringApp
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.testing.FixtureRecordingApi
import com.greatwall.callring.ui.testing.UiFakeApi
import com.greatwall.callring.ui.state.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class ReportScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun zeroAndUnknownDurationsRemainDistinctAndSummaryComesFromApi() {
        val fact = ClassificationFact(true, WorkReason.WORKING, null, true, "Asia/Shanghai", 1, null, 1)
        fun call(id: Long, duration: Long?) = CallDetail(id, "100", id, null, duration, CallResult.ANSWERED,
            CallDecision.NORMAL_RINGING, CallSource.SYSTEM_CALL_LOG, true, fact, fact, false, null)
        val time = TimeRange(0, 100, "Asia/Shanghai")
        val report = CallerReport(CallerProfile("100", false, "", "", null, PositionRelation.UNKNOWN, false), time,
            ReportTotals(999, 999, 0, 0, 1, 0, 0, 0), ScoreSummary(80, 999, 0, 0, 5, 2, 10, time),
            Page(listOf(call(1, null), call(2, 0)), 0, 50, 2, SnapshotMeta(0, 0)),
            DataQuality(true, AccessState.GRANTED, true, false, false, false), SnapshotMeta(0, 0))
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            ReportScreen(LoadState(report, loaded = true), ReportQuery("100", DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2))), MutationState(),
                onBack = {}, onRetry = {}, onRange = {}, onFilter = {}, onPage = {}, onExport = {}, onEdit = {}, onCapability = {})
        } }
        compose.onNodeWithText("来电总数 999").assertExists()
        expandReport()
        compose.onNodeWithTag("report-list").performScrollToNode(hasText("时长未知"))
        compose.onNodeWithText("时长未知").assertIsDisplayed()
        compose.onNodeWithTag("report-list").performScrollToNode(hasText("0 秒"))
        compose.onNodeWithText("0 秒").assertIsDisplayed()
        compose.onNodeWithText("上一页").assertDoesNotExist()
        compose.onNodeWithText("下一页").assertDoesNotExist()
    }

    @Test fun numberHistoryPaginatesIndependentlyAndPreservesItsPlaceOnDetailReturn() {
        val fact = ClassificationFact(true, WorkReason.WORKING, null, true, "UTC", 0, null, 0)
        val rows = (1L..51L).map { id ->
            val call = CallDetail(id, "100", (52L - id) * 60_000, null, null, CallResult.MISSED,
                CallDecision.NORMAL_RINGING, CallSource.SYSTEM_CALL_LOG, false, fact, fact, false, null)
            val recording = if (id == 1L) CallHistoryRecording(-101, CallArchiveAvailability.AVAILABLE, true, true)
                else CallHistoryRecording(null, CallArchiveAvailability.NONE, false, false)
            CallHistoryEntry(call, recording)
        }
        val queries = mutableListOf<CallHistoryQuery>()
        val details = mutableListOf<Long>()
        val playback = mutableListOf<Long>()
        var session: RecordingPlaybackSession? = null
        val cursor = CallHistoryCursor(InvalidationToken("test", 0, emptyMap()), rows[49].call.startedAtEpochMillis,
            rows[49].call.id, 50, "100")
        val api = object : UiFakeApi() {
            override suspend fun queryCallHistory(query: CallHistoryQuery): ApiResult<CallHistoryPage> {
                queries += query
                val first = query.cursor == null
                return ApiResult.Success(CallHistoryPage(if (first) rows.take(50) else rows.drop(50),
                    if (first) cursor else null, SnapshotMeta(1, 0)))
            }

            override suspend fun getCallHistoryDetail(callId: Long): ApiResult<CallHistoryEntry> {
                details += callId
                return ApiResult.Success(rows.single { it.call.id == callId })
            }

            override val recordings = object : RecordingApi by FixtureRecordingApi {
                override suspend fun getPlaybackSession() = ApiResult.Success(session)
                override suspend fun playOwned(callId: Long, token: String): ApiResult<Unit> {
                    playback += callId
                    session = RecordingPlaybackSession(callId, token)
                    return ApiResult.Success(Unit)
                }

                override suspend fun stopPlaybackSession(token: String): ApiResult<Unit> {
                    if (session?.token == token) {
                        playback += 0L
                        session = null
                    }
                    return ApiResult.Success(Unit)
                }

                override suspend fun getArchive(callId: Long) =
                    ApiResult.Success(if (callId == -101L) CallArchive(callId, "100", 0, transcript = "号码通话的转写") else null)
            }
        }
        compose.setContent { CallringApp(api, onCapability = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("tab-callers").performClick()
        compose.onNodeWithText("测试档案").performClick()
        val list = compose.onNodeWithTag("report-list")
        fun indexFor(key: String): Int = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key)

        assertTrue(indexFor("history-call-3") >= 0)
        assertEquals(-1, indexFor("history-call-4"))
        list.performScrollToKey("history-call-1")
        compose.onNodeWithContentDescription("播放录音").performTouchInput { click() }
        compose.onNodeWithContentDescription("停止录音").performTouchInput { click() }
        compose.onNodeWithContentDescription("查看转写").performTouchInput { click() }
        compose.onNodeWithText("号码通话的转写").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle {
            assertEquals(listOf(-101L, 0L), playback)
            assertTrue(details.isEmpty())
        }

        expandReport()
        list.performScrollToNode(hasText("全部明细 ▾"))
        compose.onNodeWithText("全部明细 ▾").performClick()
        compose.onNodeWithText("节假日").performClick()
        list.performScrollToNode(hasContentDescription("展开通话记录"))
        compose.onNodeWithContentDescription("展开通话记录").performClick()
        assertTrue(indexFor("history-call-50") >= 0)
        compose.runOnIdle { assertEquals(listOf(CallHistoryQuery(number = "100")), queries) }
        list.performScrollToKey("history-more")
        compose.onNodeWithText("加载更多").performClick()
        compose.onNodeWithText("加载更多").assertDoesNotExist()
        list.performScrollToKey("history-call-51")
        compose.onNodeWithText("1970-01-01 00:01:00").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("1970-01-01 00:01:00").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(CallHistoryQuery(number = "100"), CallHistoryQuery(cursor = cursor, number = "100")), queries)
            assertEquals(listOf(51L), details)
        }
    }

    private fun expandReport() {
        compose.onNodeWithTag("report-list").performScrollToNode(hasContentDescription("展开报表信息"))
        compose.onNodeWithContentDescription("展开报表信息").performClick()
    }

    @Test fun moreThanOneDetailPageStillShowsPagination() {
        val time = TimeRange(0, 100, "Asia/Shanghai")
        val report = CallerReport(CallerProfile("100", false, "", "", null, PositionRelation.UNKNOWN, false), time,
            ReportTotals(0, 0, 0, 0, 0, 0, 0, 0), ScoreSummary(null, 0, 0, 0, 5, 2, 10, time),
            Page(emptyList(), 0, 50, 51, SnapshotMeta(0, 0)),
            DataQuality(true, AccessState.GRANTED, false, false, false, false), SnapshotMeta(0, 0))
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            ReportScreen(LoadState(report, loaded = true), ReportQuery("100", DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2))), MutationState(),
                onBack = {}, onRetry = {}, onRange = {}, onFilter = {}, onPage = {}, onExport = {}, onEdit = {}, onCapability = {})
        } }
        expandReport()
        compose.onNodeWithTag("report-list").performScrollToNode(hasText("上一页"))
        compose.onNodeWithText("上一页").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("下一页").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("第 1 页 · 共 51 条").assertExists()
    }
}
