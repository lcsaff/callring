package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.contracts.settings.TimeWindow
import com.greatwall.callring.contracts.settings.WorkScheduleSettings

/** Present legacy snapshots as one editable same-day schedule without changing stored history. */
internal fun sharedWorkWindows(settings: WorkScheduleSettings): List<TimeWindow> {
    val candidates = listOfNotNull(settings.weekly[1]) +
        settings.weekly.toSortedMap().filterKeys { it != 1 }.values + listOf(settings.makeup)
    candidates.forEach { windows ->
        val ordered = windows.sortedBy { it.startMinute }
        if (ordered.isNotEmpty() && ordered.all {
                it.startMinute in 0..1439 && it.endMinute in 0..1439 && it.startMinute < it.endMinute
            } && ordered.zipWithNext().all { (a, b) -> a.endMinute <= b.startMinute }) {
            if (ordered.size == 2 && ordered[0].endMinute < ordered[1].startMinute) return ordered
            val start = ordered.first().startMinute
            val end = ordered.last().endMinute
            if (end - start >= 3) {
                val normalLunch = start < 720 && 780 < end
                val lunchStart = if (normalLunch) 720 else start + (end - start) / 3
                val lunchEnd = if (normalLunch) 780 else start + 2 * (end - start) / 3
                return listOf(TimeWindow(start, lunchStart), TimeWindow(lunchEnd, end))
            }
        }
    }
    return listOf(TimeWindow(540, 720), TimeWindow(780, 1080))
}

internal fun normalizeWorkSchedule(settings: WorkScheduleSettings): WorkScheduleSettings {
    val windows = sharedWorkWindows(settings)
    return settings.copy(weekly = (1..7).associateWith { if (it <= 5) windows else emptyList() }, makeup = windows)
}
