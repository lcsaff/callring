package com.greatwall.callring.domain.model

import com.greatwall.callring.domain.model.LeaveRecord
import com.greatwall.callring.domain.model.StatisticsInterval
import java.time.LocalDate

/** Complete work history and corrected facts required by event-time classification. */
interface WorkInputs {
    val schedules: List<WorkScheduleSettings>
    val calendarYears: Map<Int, CalendarYear>
    val holidays: Map<LocalDate, String>
    val makeupDates: Set<LocalDate>
    val overrides: Map<LocalDate, DateOverride>
    val leaves: Map<Long, LeaveRecord>
    val leaveVersion: Long
}

/** Only facts needed on the incoming-call path; no call history or external capabilities. */
data class DecisionInputs(
    override val schedules: List<WorkScheduleSettings>,
    override val calendarYears: Map<Int, CalendarYear>,
    override val holidays: Map<LocalDate, String>,
    override val makeupDates: Set<LocalDate>,
    override val overrides: Map<LocalDate, DateOverride>,
    override val leaves: Map<Long, LeaveRecord>,
    override val leaveVersion: Long,
    val whitelistedNumbers: Set<String>,
    val quiet: QuietSettings,
    val statisticsIntervals: List<StatisticsInterval>,
    val overlayEnabled: Boolean,
) : WorkInputs
