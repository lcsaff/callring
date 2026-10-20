package com.greatwall.callring.application.ports.store

import com.greatwall.callring.application.*
import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.*

/** Obtained only by the explicit complete snapshot entry point. */
fun interface FullSnapshotReader {
    fun capture(): FullSnapshotFacts
}

class FullSnapshotFacts(
    profiles: Map<String, ProfileRecord>,
    positions: Map<Long, PositionRecord>,
    calls: Map<Long, RawCallRecord>,
    history: WorkHistory,
    intervals: List<StatisticsInterval>,
    val myPositionId: Long?,
    quiet: QuietSettings,
    val lastQuietMode: QuietMode,
    val score: ScoreSettings,
    val overlayEnabled: Boolean,
    audits: List<AuditRecord>,
) {
    val profiles = frozenMap(profiles)
    val positions = frozenMap(positions)
    val calls = frozenMap(calls)
    val work =
        WorkHistory(
            frozenList(
                history.schedules.map {
                    it.copy(
                        weekly = frozenMap(it.weekly.mapValues { frozenList(it.value) }),
                        makeup = frozenList(it.makeup),
                    )
                },
            ),
            frozenMap(history.calendarYears),
            frozenMap(history.holidays),
            frozenSet(history.makeupDates),
            frozenMap(
                history.overrides.mapValues {
                    it.value.copy(windows = frozenList(it.value.windows))
                },
            ),
            frozenMap(history.leaves),
            history.leaveVersion,
        )
    val statisticsIntervals = frozenList(intervals)
    val quiet =
        quiet.copy(
            weeklyWindows = frozenMap(quiet.weeklyWindows.mapValues { frozenList(it.value) }),
        )
    val audits = frozenList(audits)
}
