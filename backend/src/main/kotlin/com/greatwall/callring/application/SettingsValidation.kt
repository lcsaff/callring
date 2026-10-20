package com.greatwall.callring.application

import com.greatwall.callring.application.mapping.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.application.ports.*
import com.greatwall.callring.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun BackendContext.stats(s: BackendState) =
    s.statisticsIntervals.any { it.endMillis == null }

internal fun BackendContext.currentSchedule(s: BackendState) =
    s.schedules.maxWithOrNull(
        compareBy<com.greatwall.callring.domain.model.WorkScheduleSettings> { it.effectiveFromEpochMillis }.thenBy { it.configVersion }
    ) ?: defaultWorkSchedule()

/** Preserve the original first-error API behavior while domain emits ordinary ordered issues. */
internal fun BackendContext.requireIssues(issues: List<com.greatwall.callring.domain.model.SettingsIssue>) {
    issues.firstOrNull()?.let { fail(ErrorCode.VALIDATION, it.message, it.path) }
}
internal fun BackendContext.validateWeekly(weekly: Map<Int, List<TimeWindow>>, field: String) =
    requireIssues(com.greatwall.callring.domain.model.SettingsRules.weekly(weekly.mapValues { (_, windows) -> windows.map { it.toDomain() } }, field))
internal fun BackendContext.validateWindows(windows: List<TimeWindow>, field: String) =
    requireIssues(com.greatwall.callring.domain.model.SettingsRules.windows(windows.map { it.toDomain() }, field))
internal fun BackendContext.validateZone(zone: String, field: String = "zoneId") =
    requireIssues(com.greatwall.callring.domain.model.SettingsRules.zone(zone, field))

internal fun BackendContext.calendar(s: BackendState) =
    CalendarInfo(
        frozenList(s.calendarYears.values.sortedBy { it.year }.map { it.toContract() }),
        frozenList(
            s.overrides.values
                .sortedBy { it.date }
                .map { it.toContract().let { dto -> dto.copy(windows = frozenList(dto.windows)) } }
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

internal fun BackendContext.quietCopy(q: com.greatwall.callring.domain.model.QuietSettings) = q.toContract().let { quietCopy(it) }.toDomain()
internal fun BackendContext.scheduleCopy(s: com.greatwall.callring.domain.model.WorkScheduleSettings) = s.toContract().let { scheduleCopy(it) }.toDomain()
