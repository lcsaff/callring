package com.greatwall.callring.application.conversation.query

import com.greatwall.callring.application.mapping.frozenList
import com.greatwall.callring.domain.calling.WorkClassifier
import com.greatwall.callring.domain.model.PositionRecord
import com.greatwall.callring.domain.model.RawCallRecord

import com.greatwall.callring.domain.model.defaultWorkSchedule
import com.greatwall.callring.application.*
import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.application.ports.store.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.saturatedAdd
import com.greatwall.callring.domain.model.saturatedSubtract
import com.greatwall.callring.domain.report.*

fun projectCoreSnapshot(
    s: FullSnapshotFacts,
    at: Long,
    status: RuntimeStatus,
    appearance: AppearanceSettings,
    ringtone: RingtoneSettings,
): Map<String, AiJson> {
    val audits = s.audits
    val numbers = (s.profiles.keys + s.calls.values.map { it.number }).toSortedSet()
    val zone = currentSchedule(s).zoneId
    val statistics = numbers.map { number ->
        val calls = visible(s, number)
        val min = calls.minOfOrNull { it.startedAtEpochMillis } ?: at
        val max = calls.maxOfOrNull { it.startedAtEpochMillis } ?: at
        val range = TimeRange(min, saturatedAdd(max, 1), zone)
        val summary = score(s, calls, range, includeSaturatedMax = max == Long.MAX_VALUE)
        val end = saturatedAdd(at, 1)
        linkedMapOf(
            "number" to number,
            "totals" to reportTotals(calls, summary),
            "score" to summary,
            "quality" to quality(s, calls, status),
            "rangeUpperBoundSaturated" to (max == Long.MAX_VALUE),
            "rolling" to
                score(
                    s,
                    calls,
                    TimeRange(
                        saturatedSubtract(end, s.score.observationDays * 86400000L),
                        end,
                        zone,
                    ),
                ),
        )
    }
    return mapOf(
        "D01" to
            safeValue(
                numbers.map {
                    com.greatwall.callring.application.callers.callerProfile(
                        it,
                        s.profiles[it],
                        s.positions[s.profiles[it]?.positionId],
                        s.positions[s.myPositionId],
                    )
                },
            ),
        "D02" to safeValue(PositionSettings(options(s), s.myPositionId)),
        "D03" to safeValue(s.calls.values.toList()),
        "D04" to safeValue(visible(s)),
        "D05" to
            safeValue(
                mapOf("intervals" to s.statisticsIntervals.toList(), "numbers" to statistics),
            ),
        "D06" to
            safeValue(
                mapOf(
                    "current" to currentSchedule(s),
                    "history" to s.work.schedules.toList(),
                    "work" to WorkClassifier.status(s.work, at),
                ),
            ),
        "D07" to
            safeValue(
                mapOf(
                    "years" to s.work.calendarYears.values.toList(),
                    "holidays" to s.work.holidays.toMap(),
                    "makeupDates" to s.work.makeupDates.toList(),
                ),
            ),
        "D08" to safeValue(s.work.overrides.values.toList()),
        "D09" to
            safeValue(
                mapOf(
                    "entries" to
                        s.work.leaves.values.map {
                            LeaveEntry(
                                it.id,
                                it.startMillis,
                                it.endMillis,
                                it.note,
                                when {
                                    at < it.startMillis -> LeaveState.UPCOMING
                                    at < it.endMillis -> LeaveState.ACTIVE
                                    else -> LeaveState.FINISHED
                                },
                            )
                        },
                    "version" to s.work.leaveVersion,
                ),
            ),
        "D10" to
            safeValue(
                mapOf(
                    "score" to s.score,
                    "quiet" to s.quiet,
                    "lastQuietMode" to s.lastQuietMode,
                    "statisticsEnabled" to stats(s),
                    "overlayEnabled" to s.overlayEnabled,
                ),
            ),
        "D11" to safeValue(appearance),
        "D12" to safeValue(mapOf("settings" to ringtone, "playback" to status.playback)),
        "D21" to safeValue(audits),
    )
}

private fun options(s: FullSnapshotFacts) =
    frozenList(
        s.positions.values
            .sortedWith(compareBy<PositionRecord> { it.level }.thenBy { it.name }.thenBy { it.id })
            .map {
                com.greatwall.callring.application.settings.positionOption(
                    it,
                    s.positions[s.myPositionId],
                )
            },
    )

private fun score(
    s: FullSnapshotFacts,
    calls: List<CallDetail>,
    range: TimeRange,
    includeSaturatedMax: Boolean = false,
): ScoreSummary {
    val accumulator = StreamingReportAccumulator(s.score, range.toDomain(), includeSaturatedMax)
    calls
        .sortedBy { it.startedAtEpochMillis }
        .forEach {
            accumulator.accept(
                it.startedAtEpochMillis,
                it.counted,
                it.classification.working,
                it.durationSeconds,
                it.result.toDomain(),
            )
        }
    return accumulator.score().toContract()
}

private fun quality(
    s: FullSnapshotFacts,
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

private fun reportTotals(rows: List<CallDetail>, summary: ScoreSummary): ReportTotals {
    val accumulator = ReportTotalsAccumulator()
    rows.forEach {
        accumulator.accept(
            it.counted,
            it.classification.working,
            it.durationSeconds,
            it.result.toDomain(),
        )
    }
    return accumulator.value(summary.offHoursCalls).toContract()
}

/**
 * Complete snapshot current/original call projection; ordinary reports have separate narrow reads.
 */
private fun visible(s: FullSnapshotFacts, number: String? = null) =
    s.calls.values
        .asSequence()
        .filter { !it.hidden && (number == null || it.number == number) }
        .sortedWith(compareByDescending<RawCallRecord> { it.time }.thenByDescending { it.id })
        .map {
            com.greatwall.callring.application.callers.callDetail(
                it,
                it.linkedScreenId?.let { id -> s.calls[id] },
            )
        }
        .toList()

private fun stats(s: FullSnapshotFacts) = s.statisticsIntervals.any { it.endMillis == null }

private fun currentSchedule(s: FullSnapshotFacts) =
    s.work.schedules.maxWithOrNull(
        compareBy<com.greatwall.callring.domain.model.WorkScheduleSettings> {
                it.effectiveFromEpochMillis
            }
            .thenBy { it.configVersion },
    ) ?: defaultWorkSchedule()
