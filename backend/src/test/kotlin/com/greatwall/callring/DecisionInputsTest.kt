package com.greatwall.callring

import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class DecisionInputsTest {
    @Test
    fun capturedInputsDetachAndDeeplyFreezeEveryDecisionCollection() {
        val windows = mutableListOf(TimeWindow(1, 2))
        val weekly = linkedMapOf(1 to windows)
        val state =
            BackendState().apply {
                schedules += WorkScheduleSettings("UTC", weekly, windows, 1, 0)
                quiet = QuietSettings(QuietMode.CUSTOM, true, weekly)
                overrides[java.time.LocalDate.of(2026, 1, 1)] =
                    DateOverride(java.time.LocalDate.of(2026, 1, 1), DateOverrideKind.WORK, windows)
                profiles["30001"] = ProfileRecord("30001", whitelisted = true)
                statisticsIntervals += StatisticsInterval(0, null)
            }
        val snapshot = DecisionSnapshot.capture(state.decisionInputs())
        windows.clear()
        weekly.clear()
        state.schedules.clear()
        state.profiles.clear()
        state.statisticsIntervals.clear()
        assertEquals(listOf(TimeWindow(1, 2)), snapshot.inputs.schedules.single().weekly[1])
        assertEquals(setOf("30001"), snapshot.inputs.whitelistedNumbers)
        assertEquals(1, snapshot.inputs.statisticsIntervals.size)
        fun immutable(block: () -> Unit) {
            try {
                block()
                fail("mutable snapshot input")
            } catch (_: UnsupportedOperationException) {}
        }
        immutable { (snapshot.inputs.schedules as MutableList).clear() }
        immutable { (snapshot.inputs.schedules.single().weekly as MutableMap).clear() }
        immutable { (snapshot.inputs.schedules.single().weekly.getValue(1) as MutableList).clear() }
        immutable { (snapshot.inputs.schedules.single().makeup as MutableList).clear() }
        immutable { (snapshot.inputs.quiet.weeklyWindows as MutableMap).clear() }
        immutable { (snapshot.inputs.quiet.weeklyWindows.getValue(1) as MutableList).clear() }
        immutable { (snapshot.inputs.overrides as MutableMap).clear() }
        immutable { (snapshot.inputs.overrides.values.single().windows as MutableList).clear() }
        immutable { (snapshot.inputs.whitelistedNumbers as MutableSet).clear() }
        immutable { (snapshot.inputs.statisticsIntervals as MutableList).clear() }
        immutable { (snapshot.inputs.calendarYears as MutableMap).clear() }
        immutable { (snapshot.inputs.holidays as MutableMap).clear() }
        immutable { (snapshot.inputs.makeupDates as MutableSet).clear() }
        immutable { (snapshot.inputs.leaves as MutableMap).clear() }
        val next =
            DecisionSnapshot.capture(
                snapshot.inputs.copy(quiet = QuietSettings(QuietMode.OFF, false, emptyMap())),
            )
        assertEquals(QuietMode.OFF, next.inputs.quiet.mode)
        assertEquals(QuietMode.CUSTOM, snapshot.inputs.quiet.mode)
        assertEquals(snapshot.inputs.schedules, next.inputs.schedules)
        assertEquals(
            setOf("inputs"),
            DecisionSnapshot::class
                .java
                .declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun validationIssuesPreserveNestedPathsAndOrderWithoutApiTypes() {
        assertEquals(
            listOf(SettingsIssue("weekly", "weekday.invalid")),
            SettingsRules.weekly(mapOf(9 to emptyList()), "weekly"),
        )
        assertEquals(
            listOf(
                SettingsIssue("weeklyWindows.2.0.startMinute", "minute.invalid"),
                SettingsIssue("weeklyWindows.2.0.endMinute", "window.invalid"),
            ),
            SettingsRules.quiet(
                QuietSettings(QuietMode.CUSTOM, false, mapOf(2 to listOf(TimeWindow(-1, -1)))),
            ),
        )
        assertEquals(
            listOf(SettingsIssue("period.zoneId", "zone.invalid")),
            SettingsRules.zone("invalid/zone", "period.zoneId"),
        )
    }
}
