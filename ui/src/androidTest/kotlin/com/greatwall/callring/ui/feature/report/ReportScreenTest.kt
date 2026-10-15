package com.greatwall.callring.ui.feature.report

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.*
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
        compose.setContent { CallringTheme {
            ReportScreen(LoadState(report, loaded = true), ReportQuery("100", DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2))), MutationState(),
                onBack = {}, onRetry = {}, onRange = {}, onFilter = {}, onPage = {}, onExport = {}, onEdit = {}, onCapability = {})
        } }
        compose.onNodeWithText("来电总数 999").assertExists()
        compose.onNodeWithTag("report-list").performScrollToNode(hasText("时长未知"))
        compose.onNodeWithText("时长未知").assertIsDisplayed()
        compose.onNodeWithTag("report-list").performScrollToNode(hasText("0 秒"))
        compose.onNodeWithText("0 秒").assertIsDisplayed()
    }
}
