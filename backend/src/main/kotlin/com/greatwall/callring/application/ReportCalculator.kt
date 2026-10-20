package com.greatwall.callring.application

import com.greatwall.callring.domain.report.*
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun BackendContext.score(
    s: BackendState,
    calls: List<CallDetail>,
    range: TimeRange,
    includeSaturatedMax: Boolean = false,
): ScoreSummary {
    val accumulator=StreamingReportAccumulator(s.score,range.toDomain(),includeSaturatedMax)
    calls.sortedBy{it.startedAtEpochMillis}.forEach{accumulator.accept(it.startedAtEpochMillis,it.counted,it.classification.working,it.durationSeconds,it.result.toDomain())}
    return accumulator.score().toContract()
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

internal fun BackendContext.reportTotals(rows: List<CallDetail>, summary: ScoreSummary): ReportTotals {
    val accumulator=ReportTotalsAccumulator()
    rows.forEach{accumulator.accept(it.counted,it.classification.working,it.durationSeconds,it.result.toDomain())}
    return accumulator.value(summary.offHoursCalls).toContract()
}

/** Remaining full-state CoreSnapshotProjection consumer; T09b removes this closure. */
internal fun BackendContext.visible(s: BackendState, number: String? = null) =
    s.calls.values
        .asSequence()
        .filter { !it.hidden && (number == null || it.number == number) }
        .sortedWith(compareByDescending<RawCallRecord> { it.time }.thenByDescending { it.id })
        .map { com.greatwall.callring.application.callers.callDetail(it,it.linkedScreenId?.let { id -> s.calls[id] }) }
        .toList()
