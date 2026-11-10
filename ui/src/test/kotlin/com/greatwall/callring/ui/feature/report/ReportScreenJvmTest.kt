package com.greatwall.callring.ui.feature.report

import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.change.InvalidationToken
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportScreenJvmTest {
    @get:Rule val compose = createComposeRule()

    private val time = TimeRange(0, 1, "Asia/Shanghai")
    private val query = ReportQuery("100", DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15)))

    private fun report(detailTotal: Long) = CallerReport(
        CallerProfile("100", false, "", "", null, PositionRelation.UNKNOWN, false), time,
        ReportTotals(0, 0, 0, 0, 0, 0, 0, 0), ScoreSummary(null, 0, 0, 0, 5, 2, 10, time),
        Page(emptyList(), 0, 50, detailTotal, SnapshotMeta(1, 0)),
        DataQuality(true, AccessState.GRANTED, false, false, false, false), SnapshotMeta(1, 0))

    @Test fun previewReusesLoadedPageAndScrollTitleFollowsTheProfile() {
        val restoration = StateRestorationTester(compose)
        val profile = mutableStateOf(report(1).profile.copy(
            name = "来电人", region = "杭州", position = PositionOption(2, "经理", 2, true, PositionRelation.HIGHER),
        ))
        val rows = (1L..51L).map(::entry)
        val cursor = CallHistoryCursor(InvalidationToken("test", 0, emptyMap()), rows[49].call.startedAtEpochMillis,
            rows[49].call.id, 50, "100")
        val page = mutableStateOf(CallHistoryPage(rows.take(50), cursor, SnapshotMeta(1, 0)))
        var loads = 0
        var restoreWithError = false
        val details = mutableListOf<Long>()
        restoration.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            val initialError = remember { restoreWithError }
            val retried = remember { mutableStateOf(false) }
            val callState = if (initialError && !retried.value)
                LoadState(error = ApiError(ErrorCode.INTERNAL, "测试加载失败", retryable = true))
            else LoadState(page.value, loaded = true)
            ReportScreen(LoadState(report(1).copy(profile = profile.value), loaded = true), query, MutationState(),
                onBack = {}, onRetry = {}, onRange = {}, onFilter = {}, onPage = {}, onExport = {},
                onEdit = {}, onCapability = {},
                history = ReportHistory(callState, LoadState(), MutationState(),
                    onRefresh = { retried.value = true },
                    onMore = { loads++; page.value = page.value.copy(entries = rows, nextCursor = null) },
                    onDetail = { details.add(it) }, onPlayback = { _, _ -> }, onTranscript = {}))
        } }
        val list = compose.onNodeWithTag("report-list")
        fun indexFor(key: String): Int = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey](key)

        for (size in listOf(0, 1, 3, 4)) {
            compose.runOnIdle { page.value = CallHistoryPage(rows.take(size), null, SnapshotMeta(1, 0)) }
            for (id in 1..minOf(size, 3)) assertTrue(indexFor("history-call-$id") >= 0)
            assertEquals(-1, indexFor("history-call-${minOf(size, 3) + 1}"))
            assertEquals(-1, indexFor("history-more"))
        }
        compose.runOnIdle { page.value = CallHistoryPage(rows.take(50), cursor, SnapshotMeta(1, 0)) }
        assertTrue(indexFor("history-call-3") >= 0)
        assertEquals(-1, indexFor("history-call-4"))
        assertEquals(-1, indexFor("history-more"))
        list.performScrollToNode(hasContentDescription("展开通话记录"))
        compose.onNodeWithContentDescription("展开通话记录").performClick()

        assertTrue(indexFor("history-call-4") >= 0)
        assertTrue(indexFor("history-call-50") >= 0)
        assertEquals(-1, indexFor("history-call-51"))
        list.performScrollToKey("history-call-12")
        compose.onNodeWithText("来电人 · 杭州 · 经理").assertIsDisplayed()
        compose.runOnIdle {
            profile.value = profile.value.copy(position = profile.value.position!!.copy(name = "总监"))
        }
        compose.onNodeWithText("来电人 · 杭州 · 总监").assertIsDisplayed()
        compose.onNodeWithText("1970-01-01 00:12:00").assertIsDisplayed()
        compose.runOnIdle { restoreWithError = true }
        restoration.emulateSavedInstanceStateRestore()
        list.performScrollToNode(hasText("测试加载失败"))
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("1970-01-01 00:12:00").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(12L), details) }

        list.performScrollToKey("history-more")
        compose.onNodeWithText("加载更多").performClick()
        assertTrue(indexFor("history-call-51") >= 0)
        assertEquals(-1, indexFor("history-more"))
        compose.runOnIdle { assertEquals(1, loads) }
        list.performScrollToKey("profile")
        compose.onNodeWithText("号码档案详情").assertIsDisplayed()
    }

    private fun entry(id: Long): CallHistoryEntry {
        val fact = ClassificationFact(true, WorkReason.WORKING, null, true, "UTC", 0, null, 0)
        return CallHistoryEntry(CallDetail(id, "100", id * 60_000, null, null, CallResult.MISSED,
            CallDecision.NORMAL_RINGING, CallSource.SYSTEM_CALL_LOG, false, fact, fact, false, null),
            CallHistoryRecording(null, CallArchiveAvailability.NONE, false, false))
    }

    private fun show(detailTotal: Long) {
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            ReportScreen(LoadState(report(detailTotal), loaded = true), query, MutationState(),
                onBack = {}, onRetry = {}, onRange = {}, onFilter = {}, onPage = {}, onExport = {},
                onEdit = {}, onCapability = {})
        } }
    }

    private fun expandReport() {
        compose.onNodeWithTag("report-list").performScrollToNode(hasContentDescription("展开报表信息"))
        compose.onNodeWithContentDescription("展开报表信息").performClick()
    }

    @Test fun profileReportAndCallsStartCollapsed() {
        show(detailTotal = 1)
        compose.onNodeWithText("号码档案详情").assertIsDisplayed()
        compose.onNodeWithContentDescription("展开号码信息").assertIsDisplayed()
        compose.onNodeWithTag("report-list").performScrollToNode(hasContentDescription("展开报表信息"))
        compose.onNodeWithText("来电总数 0").assertExists()
        compose.onNodeWithTag("report-list").performScrollToNode(hasContentDescription("展开通话记录"))
        compose.onNodeWithText("逐次明细").assertDoesNotExist()
    }

    @Test fun reportWithASingleDetailPageHidesAllPagination() {
        show(detailTotal = 1)
        expandReport()
        compose.onNodeWithTag("report-list").performScrollToNode(hasText("逐次明细"))
        compose.onNodeWithText("上一页").assertDoesNotExist()
        compose.onNodeWithText("下一页").assertDoesNotExist()
        compose.onNodeWithText("第 1 页", substring = true).assertDoesNotExist()
    }

    @Test fun reportWithMoreThanOneDetailPageStillShowsPagination() {
        show(detailTotal = 51)
        expandReport()
        compose.onNodeWithTag("report-list").performScrollToNode(hasText("上一页"))
        compose.onNodeWithText("上一页").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("下一页").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("第 1 页 · 共 51 条").assertExists()
    }
}
