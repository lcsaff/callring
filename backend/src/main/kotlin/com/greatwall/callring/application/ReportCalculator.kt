package com.greatwall.callring.application

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun BackendContext.timeRange(s: BackendState, range: DateRange): TimeRange {
    requireField(
        !range.endDateInclusive.isBefore(range.startDate),
        "range.endDateInclusive",
        "range.order.invalid",
    )
    val zone =
        try {
            ZoneId.of(range.zoneId ?: currentSchedule(s).zoneId)
        } catch (_: Exception) {
            fail(ErrorCode.VALIDATION, "zone.invalid", "range.zoneId")
        }
    return try {
        TimeRange(
            range.startDate.atStartOfDay(zone).toInstant().toEpochMilli(),
            range.endDateInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            zone.id,
        )
    } catch (_: Exception) {
        fail(ErrorCode.VALIDATION, "range.invalid", "range.endDateInclusive")
    }
}

internal fun BackendContext.score(
    s: BackendState,
    calls: List<CallDetail>,
    range: TimeRange,
    includeSaturatedMax: Boolean = false,
): ScoreSummary {
    val accumulator=StreamingReportAccumulator(s.score,range,includeSaturatedMax)
    calls.sortedBy{it.startedAtEpochMillis}.forEach{accumulator.accept(it.startedAtEpochMillis,it.counted,it.classification.working,it.durationSeconds,it.result)}
    return accumulator.score()
}

internal fun BackendContext.quality(
    s: BackendState,
    calls: List<CallDetail>,
    status: RuntimeStatus,
) =
    DataQuality(
        stats(s),
        status.capabilities.getValue(Capability.CALL_LOG),
        calls.any { it.counted && it.durationSeconds == null },
        calls.any { it.result == CallResult.PENDING },
        calls.any { it.result == CallResult.UNRESOLVED },
        calls.any { !it.classification.calendarVerified },
    )

internal fun BackendContext.report(
    s: BackendState,
    n: String,
    range: TimeRange,
    filter: CallFilter,
    page: PageRequest,
    status: RuntimeStatus,
): CallerReport {
    val rows =
        visible(s, n).filter {
            it.startedAtEpochMillis >= range.startInclusiveEpochMillis &&
                it.startedAtEpochMillis < range.endExclusiveEpochMillis
        }
    val counted = rows.filter { it.counted }
    val summary = score(s, rows, range)
    val snapshot = meta()
    val totals = reportTotals(rows,summary)
    val filtered =
        rows.filter {
            when (filter) {
                CallFilter.ALL -> true
                CallFilter.WORKING -> it.classification.working
                else -> it.classification.reason.name == filter.name
            }
        }
    return CallerReport(
        profile(s, n),
        range,
        totals,
        summary,
        paged(filtered, page, snapshot),
        quality(s, rows, status),
        snapshot,
    )
}

internal fun BackendContext.reportTotals(rows: List<CallDetail>, summary: ScoreSummary): ReportTotals {
    val accumulator=ReportTotalsAccumulator()
    rows.forEach{accumulator.accept(it.counted,it.classification.working,it.durationSeconds,it.result)}
    return accumulator.value(summary.offHoursCalls)
}
