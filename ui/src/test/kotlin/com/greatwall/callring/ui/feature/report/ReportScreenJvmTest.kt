package com.greatwall.callring.ui.feature.report

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState
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

    private fun show(detailTotal: Long) {
        compose.setContent { CallringTheme {
            ReportScreen(LoadState(report(detailTotal), loaded = true), query, MutationState(),
                onBack = {}, onRetry = {}, onRange = {}, onFilter = {}, onPage = {}, onExport = {},
                onEdit = {}, onCapability = {})
        } }
    }

    @Test fun reportWithASingleDetailPageHidesAllPagination() {
        show(detailTotal = 1)
        compose.onNodeWithTag("report-list").performScrollToNode(hasText("逐次明细"))
        compose.onNodeWithText("上一页").assertDoesNotExist()
        compose.onNodeWithText("下一页").assertDoesNotExist()
        compose.onNodeWithText("第 1 页", substring = true).assertDoesNotExist()
    }

    @Test fun reportWithMoreThanOneDetailPageStillShowsPagination() {
        show(detailTotal = 51)
        compose.onNodeWithTag("report-list").performScrollToNode(hasText("上一页"))
        compose.onNodeWithText("上一页").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("下一页").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("第 1 页 · 共 51 条").assertExists()
    }
}
