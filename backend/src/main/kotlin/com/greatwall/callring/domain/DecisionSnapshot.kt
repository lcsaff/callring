package com.greatwall.callring.domain

import com.greatwall.callring.domain.model.*

import java.time.ZoneId
import java.util.Collections

data class IncomingDecision(
    val matched: Boolean,
    val whitelisted: Boolean,
    val silence: Boolean,
    val overlayEnabled: Boolean,
    val statisticsEnabled: Boolean,
    val classification: ClassificationFact,
)

/** Detached and deeply frozen. No live database references and no cached "working now" boolean. */
class DecisionSnapshot private constructor(val inputs: DecisionInputs) {
    fun evaluate(number: String, at: Long): IncomingDecision {
        val identity = NumberIdentity.parse(number)
        val normalized = identity.normalized
        val matched = identity.matchesRule
        val white = normalized in inputs.whitelistedNumbers
        val classification = WorkClassifier.classify(inputs, at)
        val quiet = inputs.quiet
        val silence =
            !white &&
                (matched || quiet.allNumbers) &&
                !classification.working &&
                when (quiet.mode) {
                    QuietMode.OFF -> false
                    QuietMode.NON_WORK -> true
                    QuietMode.CUSTOM ->
                        WorkClassifier.inWeekly(
                            quiet.weeklyWindows,
                            ZoneId.of(classification.zoneId),
                            at,
                        )
                }
        return IncomingDecision(
            matched,
            white,
            silence,
            matched && inputs.overlayEnabled,
            matched &&
                inputs.statisticsIntervals.any {
                    at >= it.startMillis && (it.endMillis == null || at < it.endMillis)
                },
            classification,
        )
    }

    companion object {
        fun capture(source: BackendState): DecisionSnapshot = capture(DecisionInputs(
            source.schedules, source.calendarYears, source.holidays, source.makeupDates,
            source.overrides, source.leaves, source.leaveVersion,
            source.profiles.filterValues { it.whitelisted }.keys,
            source.quiet, source.statisticsIntervals, source.overlayEnabled,
        ))

        fun capture(source: DecisionInputs): DecisionSnapshot = DecisionSnapshot(source.copy(
            schedules = freeze(source.schedules.map { it.copy(weekly = freezeWindows(it.weekly), makeup = freeze(it.makeup)) }),
            calendarYears = freezeMap(source.calendarYears),
            holidays = freezeMap(source.holidays),
            makeupDates = Collections.unmodifiableSet(LinkedHashSet(source.makeupDates)),
            overrides = freezeMap(source.overrides.mapValues { it.value.copy(windows = freeze(it.value.windows)) }),
            leaves = freezeMap(source.leaves),
            whitelistedNumbers = Collections.unmodifiableSet(LinkedHashSet(source.whitelistedNumbers)),
            quiet = source.quiet.copy(weeklyWindows = freezeWindows(source.quiet.weeklyWindows)),
            statisticsIntervals = freeze(source.statisticsIntervals),
        ))

        private fun <K, V> freezeMap(map: Map<K, V>): Map<K, V> =
            Collections.unmodifiableMap(LinkedHashMap(map))

        private fun <T> freeze(list: List<T>): List<T> =
            Collections.unmodifiableList(ArrayList(list))

        private fun freezeWindows(windows: Map<Int, List<TimeWindow>>): Map<Int, List<TimeWindow>> =
            Collections.unmodifiableMap(windows.mapValues { freeze(it.value) })
    }
}
