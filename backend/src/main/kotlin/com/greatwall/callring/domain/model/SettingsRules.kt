package com.greatwall.callring.domain.model

import java.time.LocalDate
import java.time.ZoneId

/** Pure issues retain nested paths; application decides API error code and transport shape. */
data class SettingsIssue(val path: String, val message: String)

object SettingsRules {
    fun windows(windows: List<TimeWindow>, field: String): List<SettingsIssue> = buildList {
        windows.forEachIndexed { index, window ->
            if (window.startMinute !in 0..1439) add(SettingsIssue("$field.$index.startMinute", "minute.invalid"))
            if (window.endMinute !in 0..1439 || window.endMinute == window.startMinute)
                add(SettingsIssue("$field.$index.endMinute", "window.invalid"))
        }
    }
    fun weekly(weekly: Map<Int, List<TimeWindow>>, field: String): List<SettingsIssue> = buildList {
        weekly.forEach { (day, windows) ->
            if (day !in 1..7) add(SettingsIssue(field, "weekday.invalid"))
            addAll(windows(windows, "$field.$day"))
        }
    }
    fun quiet(quiet: QuietSettings): List<SettingsIssue> = weekly(quiet.weeklyWindows, "weeklyWindows") +
        if (quiet.mode == QuietMode.CUSTOM && quiet.weeklyWindows.values.none { it.isNotEmpty() })
            listOf(SettingsIssue("weeklyWindows", "quiet.windows.required")) else emptyList()
    fun zone(zone: String, field: String = "zoneId"): List<SettingsIssue> =
        if (runCatching { ZoneId.of(zone) }.isSuccess) emptyList() else listOf(SettingsIssue(field, "zone.invalid"))
    fun score(score: ScoreSettings): List<SettingsIssue> = buildList {
        if (score.offHoursWeight < 0) add(SettingsIssue("offHoursWeight", "weight.invalid"))
        if (score.repeatWeight < 0) add(SettingsIssue("repeatWeight", "weight.invalid"))
        if (score.repeatMinutes <= 0) add(SettingsIssue("repeatMinutes", "repeat_minutes.invalid"))
        if (score.observationDays <= 0) add(SettingsIssue("observationDays", "observation_days.invalid"))
    }
    fun dateOrder(start: LocalDate, endInclusive: LocalDate, field: String): List<SettingsIssue> =
        if (endInclusive.isBefore(start)) listOf(SettingsIssue(field, "range.order.invalid")) else emptyList()
    fun overrideWindows(override: DateOverride): List<SettingsIssue> = windows(override.windows, "windows") +
        if ((override.kind == DateOverrideKind.REST) != override.windows.isEmpty())
            listOf(SettingsIssue("windows", "override.windows.invalid")) else emptyList()
    fun leaveBounds(start: Long, end: Long): List<SettingsIssue> =
        if (end <= start) listOf(SettingsIssue("period", "leave.range.invalid")) else emptyList()
}
