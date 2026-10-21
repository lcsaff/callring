package com.greatwall.callring.contracts.report

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.runtime.*

interface ReportsApi {
    suspend fun load(query: ReportQuery): ApiResult<CallerReport>
    suspend fun exportCsv(
        query: ReportExportQuery,
        destination: DocumentRef
    ): ApiResult<ExportReceipt>
}

data class ReportQuery(
    val number: String,
    val range: DateRange,
    val filter: CallFilter = CallFilter.ALL,
    val page: PageRequest = PageRequest()
)

data class ReportExportQuery(val number: String, val range: DateRange)

enum class CallFilter {
    ALL, WORKING, PERSONAL_LEAVE, HOLIDAY, REST_DAY, OUTSIDE_WORK_HOURS
}

data class ScoreSummary(
    val points: Int?,
    val totalCalls: Long,
    val offHoursCalls: Long,
    val repeatedCalls: Long,
    val offHoursWeight: Int,
    val repeatWeight: Int,
    val repeatMinutes: Int,
    val range: TimeRange
)

data class ReportTotals(
    val totalCalls: Long,
    val workingCalls: Long,
    val offHoursCalls: Long,
    val knownDurationSeconds: Long,
    val unknownDurationCount: Long,
    val missedCount: Long,
    val rejectedCount: Long,
    val pendingCount: Long
)

data class DataQuality(
    val statisticsEnabled: Boolean,
    val callLogAccess: AccessState,
    val hasUnknownDuration: Boolean,
    val hasPendingResults: Boolean,
    val hasUnresolvedRecords: Boolean,
    val hasUnverifiedCalendar: Boolean
)

data class CallerReport(
    val profile: CallerProfile,
    val range: TimeRange,
    val totals: ReportTotals,
    val score: ScoreSummary,
    val details: Page<CallDetail>,
    val quality: DataQuality,
    val meta: SnapshotMeta
)

data class ExportReceipt(
    val number: String,
    val range: TimeRange,
    val recordCount: Long,
    val mimeType: String
)
