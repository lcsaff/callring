package com.greatwall.callring.ui.feature.report

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import java.time.LocalDate

class ReportViewModel(
    private val number: String,
    private val api: ReportsApi,
    changes: ChangeFeed,
    private val savedState: SavedStateHandle,
    initialRange: DateRange,
    testScope: CoroutineScope? = null
) : UiViewModel(testScope) {
    internal val timeRefresh = TimeRefreshSchedule()
    private var readStartedAt = timeRefresh.captureRead()
    private val writer = MutationRunner(scope)
    val mutation = writer.state
    private val mutableQuery = MutableStateFlow(ReportQuery(number, DateRange(
        savedState.get<String>("start")?.let(LocalDate::parse) ?: initialRange.startDate,
        savedState.get<String>("end")?.let(LocalDate::parse) ?: initialRange.endDateInclusive,
        savedState["zone"] ?: initialRange.zoneId),
        savedState.get<String>("filter")?.let(CallFilter::valueOf) ?: CallFilter.ALL,
        PageRequest(savedState["page"] ?: 0)))
    val query = mutableQuery.asStateFlow()

    private val subscription = numberSubscription(setOf(DataDomain.CALLERS, DataDomain.CALLS, DataDomain.REPORTS,
        DataDomain.WORK_CALENDAR, DataDomain.SCORE_SETTINGS, DataDomain.STATISTICS, DataDomain.RUNTIME), number)
    private val loader = ObservedQuery(scope, changes, ChangeSubscription(subscription.domains,
        subscription.entities + (DataDomain.RUNTIME to setOf("capability:CALL_LOG"))), { query.value },
        { readStartedAt = timeRefresh.captureRead(); api.load(query.value) }, { report ->
            timeRefresh.readSucceeded(readStartedAt)
            val details = report.details
            val lastPage = ((details.totalItems - 1).coerceAtLeast(0) / details.pageSize).toInt()
            if (query.value.page.pageIndex > lastPage) page(lastPage)
        })
    val state = loader.state
    init { loader.start(scope) { mutableQuery.value = query.value.copy(page = PageRequest()); savedState["page"] = 0 } }

    fun range(range: DateRange) = update(query.value.copy(range = range, page = PageRequest()))
    fun filter(filter: CallFilter) = update(query.value.copy(filter = filter, page = PageRequest()))
    fun page(index: Int) = update(query.value.copy(page = PageRequest(index.coerceAtLeast(0))))
    fun refresh() = loader.request(force = true)

    fun export(destination: DocumentRef) {
        val captured = query.value
        writer.run({ api.exportCsv(ReportExportQuery(number, captured.range), destination) }, "报表已导出") {}
    }

    private fun update(value: ReportQuery) {
        mutableQuery.value = value
        savedState["start"] = value.range.startDate.toString()
        savedState["end"] = value.range.endDateInclusive.toString()
        savedState["zone"] = value.range.zoneId
        savedState["filter"] = value.filter.name
        savedState["page"] = value.page.pageIndex
        loader.request()
    }
}
