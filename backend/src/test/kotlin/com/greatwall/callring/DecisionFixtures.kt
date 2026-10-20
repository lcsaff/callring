package com.greatwall.callring

import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.*
import java.time.LocalDate

/** Mutable inputs for pure classification and decision tests, without persistence identity. */
internal class DecisionFixture : WorkInputs {
    override val schedules = mutableListOf<WorkScheduleSettings>()
    override val calendarYears = linkedMapOf<Int, CalendarYear>()
    override val holidays = linkedMapOf<LocalDate, String>()
    override val makeupDates = linkedSetOf<LocalDate>()
    override val overrides = linkedMapOf<LocalDate, DateOverride>()
    override val leaves = linkedMapOf<Long, LeaveRecord>()
    override var leaveVersion = 0L
    val whitelist = linkedSetOf<String>()
    var quiet = QuietSettings(QuietMode.OFF, false, emptyMap())
    val intervals = mutableListOf<StatisticsInterval>()
    var overlay = false
    fun decisionInputs() = DecisionInputs(schedules, calendarYears, holidays, makeupDates,
        overrides, leaves, leaveVersion, whitelist, quiet, intervals, overlay)
}
