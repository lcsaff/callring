package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
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
): ScoreSummary {
    val counted =
        calls
            .filter {
                it.counted &&
                    it.startedAtEpochMillis >= range.startInclusiveEpochMillis &&
                    it.startedAtEpochMillis < range.endExclusiveEpochMillis
            }
            .sortedBy { it.startedAtEpochMillis }
    val off = counted.count { !it.classification.working }.toLong()
    val repeats =
        counted
            .zipWithNext()
            .count { (a, b) ->
                val gap = b.startedAtEpochMillis - a.startedAtEpochMillis
                gap >= 0 && gap <= s.score.repeatMinutes * 60000L
            }
            .toLong()
    val deductions = off * s.score.offHoursWeight + repeats * s.score.repeatWeight
    return ScoreSummary(
        if (counted.isEmpty()) null else (100L - deductions).coerceIn(0, 100).toInt(),
        counted.size.toLong(),
        off,
        repeats,
        s.score.offHoursWeight,
        s.score.repeatWeight,
        s.score.repeatMinutes,
        range,
    )
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
    val totals =
        ReportTotals(
            counted.size.toLong(),
            counted.count { it.classification.working }.toLong(),
            summary.offHoursCalls,
            counted.mapNotNull { it.durationSeconds }.fold(0L) { a, b -> saturatedAdd(a, b) },
            counted.count { it.durationSeconds == null }.toLong(),
            counted.count { it.result == CallResult.MISSED }.toLong(),
            counted
                .count { it.result == CallResult.REJECTED || it.result == CallResult.BLOCKED }
                .toLong(),
            rows
                .count { it.result == CallResult.PENDING || it.result == CallResult.UNRESOLVED }
                .toLong(),
        )
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
