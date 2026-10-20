package com.greatwall.callring.application.calling

import com.greatwall.callring.application.ports.store.WorkHistory
import com.greatwall.callring.domain.DecisionSnapshot
import com.greatwall.callring.domain.model.*
import java.util.concurrent.atomic.AtomicReference

/** One decision mirror for a root and its bound commands; patches run inside the commit fence. */
class DecisionMirror {
    internal val snapshots = AtomicReference<DecisionSnapshot?>(null)
    private fun patch(change: (DecisionInputs) -> DecisionInputs) {
        snapshots.get()?.let { snapshots.set(DecisionSnapshot.capture(change(it.inputs))) }
    }
    internal fun calendar(history: WorkHistory) = patch { it.copy(calendarYears = history.calendarYears,
        holidays = history.holidays, makeupDates = history.makeupDates, overrides = history.overrides) }
    internal fun leaves(history: WorkHistory) = patch { it.copy(leaves = history.leaves, leaveVersion = history.leaveVersion) }
    internal fun whitelist(number: String, enabled: Boolean) = patch {
        it.copy(whitelistedNumbers = if (enabled) it.whitelistedNumbers + number else it.whitelistedNumbers - number)
    }
    internal fun work(schedule: WorkScheduleSettings) = patch { it.copy(schedules = it.schedules + schedule) }
    internal fun overlay(enabled: Boolean) = patch { it.copy(overlayEnabled = enabled) }
    internal fun quiet(quiet: QuietSettings) = patch { it.copy(quiet = quiet) }
    internal fun statistics(change: com.greatwall.callring.application.settings.StatisticsChange) = patch {
        it.copy(statisticsIntervals = if (change.before == null) it.statisticsIntervals + change.after
            else it.statisticsIntervals.map { old -> if (old == change.before) change.after else old })
    }
}
