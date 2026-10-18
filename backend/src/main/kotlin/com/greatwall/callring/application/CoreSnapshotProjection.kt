package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
import com.greatwall.callring.domain.*

internal fun BackendContext.captureCore(at: Long): Map<String, AiJson> {
    val status = runtimeStatus()
    val (s, audits) = store.captureFull { stored, rows ->
        BackendState().apply {
            profiles.putAll(stored.profiles); positions.putAll(stored.positions); calls.putAll(stored.calls)
            schedules.addAll(stored.schedules.map(::scheduleCopy))
            calendarYears.putAll(stored.calendarYears); holidays.putAll(stored.holidays); makeupDates.addAll(stored.makeupDates)
            overrides.putAll(stored.overrides.mapValues { it.value.copy(windows = it.value.windows.toList()) })
            leaves.putAll(stored.leaves); statisticsIntervals.addAll(stored.statisticsIntervals)
            myPositionId = stored.myPositionId; quiet = quietCopy(stored.quiet); lastQuietMode = stored.lastQuietMode
            score = stored.score; overlayEnabled = stored.overlayEnabled; leaveVersion = stored.leaveVersion
        } to rows.toList()
    }
        val numbers = (s.profiles.keys + s.calls.values.map { it.number }).toSortedSet()
        val zone = currentSchedule(s).zoneId
        val statistics = numbers.map { number ->
            val calls = visible(s, number)
            val min = calls.minOfOrNull { it.startedAtEpochMillis } ?: at
            val max = calls.maxOfOrNull { it.startedAtEpochMillis } ?: at
            val range = TimeRange(min, saturatedAdd(max, 1), zone)
            val summary = score(s,calls,range,includeSaturatedMax = max == Long.MAX_VALUE)
            val end = saturatedAdd(at, 1)
            linkedMapOf("number" to number, "totals" to reportTotals(calls,summary), "score" to summary, "quality" to quality(s,calls,status), "rangeUpperBoundSaturated" to (max == Long.MAX_VALUE),
                "rolling" to score(s, calls, TimeRange(saturatedSubtract(end, s.score.observationDays * 86400000L), end, zone)))
        }
        return mapOf(
            "D01" to safeValue(numbers.map { profile(s, it) }),
            "D02" to safeValue(PositionSettings(options(s), s.myPositionId)),
            "D03" to safeValue(s.calls.values.toList()),
            "D04" to safeValue(visible(s)),
            "D05" to safeValue(mapOf("intervals" to s.statisticsIntervals.toList(), "numbers" to statistics)),
            "D06" to safeValue(mapOf("current" to currentSchedule(s), "history" to s.schedules.toList(), "work" to WorkClassifier.status(s, at))),
            "D07" to safeValue(mapOf("years" to s.calendarYears.values.toList(), "holidays" to s.holidays.toMap(), "makeupDates" to s.makeupDates.toList())),
            "D08" to safeValue(s.overrides.values.toList()),
            "D09" to safeValue(mapOf("entries" to s.leaves.values.map { LeaveEntry(it.id, it.startMillis, it.endMillis, it.note,
                when { at < it.startMillis -> LeaveState.UPCOMING; at < it.endMillis -> LeaveState.ACTIVE; else -> LeaveState.FINISHED }) }, "version" to s.leaveVersion)),
            "D10" to safeValue(mapOf("score" to s.score, "quiet" to s.quiet, "lastQuietMode" to s.lastQuietMode, "statisticsEnabled" to stats(s), "overlayEnabled" to s.overlayEnabled)),
            "D11" to safeValue(preferences.getAppearance()),
            "D12" to safeValue(mapOf("settings" to audio.getSettings(), "playback" to status.playback)),
            "D21" to safeValue(audits),
        )
}
