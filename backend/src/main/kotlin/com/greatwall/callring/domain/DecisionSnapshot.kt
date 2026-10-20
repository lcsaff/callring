package com.greatwall.callring.domain

import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.ai.*
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
class DecisionSnapshot private constructor(private val state: BackendState) {
    fun evaluate(number: String, at: Long): IncomingDecision {
        val normalized =
            number.filterNot {
                it == '-' ||
                    it == '(' ||
                    it == ')' ||
                    it.isWhitespace() ||
                    Character.isSpaceChar(it)
            }
        val matched = normalized.matches(Regex("3[0-9]{4}"))
        val white = state.profiles[normalized]?.whitelisted ?: false
        val classification = WorkClassifier.classify(state, at)
        val quiet = state.quiet
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
            matched && state.overlayEnabled,
            matched &&
                state.statisticsIntervals.any {
                    at >= it.startMillis && (it.endMillis == null || at < it.endMillis)
                },
            classification,
        )
    }

    companion object {
        fun capture(source: BackendState): DecisionSnapshot {
            val copy = BackendState()
            copy.profiles.putAll(source.profiles.filterValues { it.whitelisted })
            copy.schedules.addAll(
                source.schedules.map {
                    it.copy(weekly = freezeWindows(it.weekly), makeup = freeze(it.makeup))
                }
            )
            copy.calendarYears.putAll(source.calendarYears)
            copy.holidays.putAll(source.holidays)
            copy.makeupDates.addAll(source.makeupDates)
            copy.overrides.putAll(
                source.overrides.mapValues { it.value.copy(windows = freeze(it.value.windows)) }
            )
            copy.leaves.putAll(source.leaves)
            copy.statisticsIntervals.addAll(source.statisticsIntervals)
            copy.leaveVersion = source.leaveVersion
            copy.quiet =
                source.quiet.copy(weeklyWindows = freezeWindows(source.quiet.weeklyWindows))
            copy.overlayEnabled = source.overlayEnabled
            return DecisionSnapshot(copy)
        }

        private fun <T> freeze(list: List<T>): List<T> =
            Collections.unmodifiableList(ArrayList(list))

        private fun freezeWindows(windows: Map<Int, List<TimeWindow>>): Map<Int, List<TimeWindow>> =
            Collections.unmodifiableMap(windows.mapValues { freeze(it.value) })
    }
}
