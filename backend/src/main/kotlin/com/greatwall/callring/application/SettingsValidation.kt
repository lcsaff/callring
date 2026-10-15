package com.greatwall.callring.application

import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun BackendContext.stats(s: BackendState) =
    s.statisticsIntervals.any { it.endMillis == null }

internal fun BackendContext.currentSchedule(s: BackendState) =
    s.schedules.maxWithOrNull(
        compareBy<WorkScheduleSettings> { it.effectiveFromEpochMillis }.thenBy { it.configVersion }
    ) ?: defaultWorkSchedule()

internal fun BackendContext.validateWeekly(weekly: Map<Int, List<TimeWindow>>, field: String) {
    weekly.forEach { (day, windows) ->
        requireField(day in 1..7, field, "weekday.invalid")
        validateWindows(windows, "$field.$day")
    }
}

internal fun BackendContext.validateWindows(windows: List<TimeWindow>, field: String) {
    windows.forEachIndexed { index, w ->
        requireField(w.startMinute in 0..1439, "$field.$index.startMinute", "minute.invalid")
        requireField(
            w.endMinute in 0..1439 && w.endMinute != w.startMinute,
            "$field.$index.endMinute",
            "window.invalid",
        )
    }
}

internal fun BackendContext.validateQuiet(q: QuietSettings) {
    validateWeekly(q.weeklyWindows, "weeklyWindows")
    requireField(
        q.mode != QuietMode.CUSTOM || q.weeklyWindows.values.any { it.isNotEmpty() },
        "weeklyWindows",
        "quiet.windows.required",
    )
}

internal fun BackendContext.validateZone(zone: String, field: String = "zoneId") {
    try {
        ZoneId.of(zone)
    } catch (_: Exception) {
        fail(ErrorCode.VALIDATION, "zone.invalid", field)
    }
}

internal fun BackendContext.calendar(s: BackendState) =
    CalendarInfo(
        frozenList(s.calendarYears.values.sortedBy { it.year }),
        frozenList(
            s.overrides.values
                .sortedBy { it.date }
                .map { it.copy(windows = frozenList(it.windows)) }
        ),
    )

internal fun BackendContext.leaveEntry(l: LeaveRecord): LeaveEntry {
    val now = clock.millis()
    return LeaveEntry(
        l.id,
        l.startMillis,
        l.endMillis,
        l.note,
        when {
            now < l.startMillis -> LeaveState.UPCOMING
            now >= l.endMillis -> LeaveState.FINISHED
            else -> LeaveState.ACTIVE
        },
    )
}

internal fun BackendContext.widgetId(id: Int) {
    requireField(id > 0, "widgetId", "widget.id.invalid")
}

internal fun BackendContext.quietCopy(q: QuietSettings) =
    q.copy(weeklyWindows = windowsCopy(q.weeklyWindows))

internal fun BackendContext.scheduleCopy(s: WorkScheduleSettings) =
    s.copy(weekly = windowsCopy(s.weekly), makeup = frozenList(s.makeup))

internal fun BackendContext.windowsCopy(map: Map<Int, List<TimeWindow>>) =
    frozenMap(map.mapValues { frozenList(it.value) })
