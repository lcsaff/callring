package com.greatwall.callring.ui.feature.report

import com.greatwall.callring.ui.testing.TestChangeFeed
import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReportViewModelTest {
    @Test fun exportAlwaysUsesWholeRangeIgnoringDetailFilterAndPage() = runTest {
        val api = ReportsFake()
        val range = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), "Asia/Shanghai")
        val model = ReportViewModel("100", api, object : ChangeFeed { override fun snapshots() = TestChangeFeed().snapshots() }, SavedStateHandle(), range, backgroundScope)
        model.filter(CallFilter.HOLIDAY)
        model.page(3)
        model.export(DocumentRef("content://test/report.csv")); runCurrent()
        assertEquals(ReportExportQuery("100", range), api.exported)
    }

    @Test fun filterChangesOnlyQueryAndNeverCalculatesTotalsFromCurrentPage() = runTest {
        val api = ReportsFake()
        val range = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15))
        val model = ReportViewModel("100", api, object : ChangeFeed { override fun snapshots() = TestChangeFeed().snapshots() }, SavedStateHandle(), range, backgroundScope)
        model.filter(CallFilter.HOLIDAY); runCurrent()
        assertEquals(999L, model.state.value.data?.totals?.totalCalls)
        assertEquals(37, model.state.value.data?.score?.points)
        assertTrue(model.state.value.data!!.details.items.isEmpty())
        assertEquals(CallFilter.HOLIDAY, api.query?.filter)
    }

    @Test fun configuredPositionChangesInvalidateReportProfileButAppearanceDoesNot() = runTest {
        val api = ReportsFake()
        val feed = TestChangeFeed()
        val range = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15))
        val model = ReportViewModel("100", api, feed, SavedStateHandle(), range, backgroundScope)
        runCurrent()
        feed.publish(setOf(DataDomain.APPEARANCE))
        feed.publish(setOf(DataDomain.POSITIONS))
        runCurrent()
        assertEquals(1, api.reads)

        api.position = PositionOption(1, "研发总监", 2, true, PositionRelation.HIGHER)
        feed.publish(setOf(DataDomain.POSITIONS, DataDomain.CALLERS, DataDomain.REPORTS),
            mapOf(DataDomain.CALLERS to setOf("100"), DataDomain.REPORTS to setOf("100")))
        runCurrent()
        assertEquals(2, api.reads)
        assertEquals("研发总监", model.state.value.data?.profile?.position?.name)
    }

    private class ReportsFake : ReportsApi {
        var reads = 0
        var position: PositionOption? = null
        var exported: ReportExportQuery? = null
        var query: ReportQuery? = null
        val time = TimeRange(0, 1, "Asia/Shanghai")
        override suspend fun load(query: ReportQuery): ApiResult<CallerReport> {
            reads++
            this.query = query
            return ApiResult.Success(CallerReport(
                CallerProfile("100", true, "张", "", position, PositionRelation.UNKNOWN, false), time,
                ReportTotals(999, 100, 899, 0, 999, 1, 0, 0),
                ScoreSummary(37, 999, 899, 1, 1, 1, 5, time),
                Page(emptyList(), query.page.pageIndex, 50, 1000, SnapshotMeta(1, 0)),
                DataQuality(true, AccessState.GRANTED, true, false, false, false), SnapshotMeta(1, 0)))
        }
        override suspend fun exportCsv(query: ReportExportQuery, destination: DocumentRef): ApiResult<ExportReceipt> {
            exported = query
            return ApiResult.Success(ExportReceipt(query.number, time, 999, "text/csv"))
        }
    }
}
