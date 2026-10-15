package com.greatwall.callring.domain

import com.greatwall.callring.application.api.*
import java.time.LocalDate
import java.time.ZoneId

/**
 * Raw persistence facts only. The application derives relation, scores, quality and presentation.
 */
class BackendState {
    val profiles: MutableMap<String, ProfileRecord> = linkedMapOf()
    val positions: MutableMap<Long, PositionRecord> = linkedMapOf()
    val calls: MutableMap<Long, RawCallRecord> = linkedMapOf()
    val schedules: MutableList<WorkScheduleSettings> = mutableListOf()
    val calendarYears: MutableMap<Int, CalendarYear> = linkedMapOf()
    val holidays: MutableMap<LocalDate, String> = linkedMapOf()
    val makeupDates: MutableSet<LocalDate> = linkedSetOf()
    val overrides: MutableMap<LocalDate, DateOverride> = linkedMapOf()
    val leaves: MutableMap<Long, LeaveRecord> = linkedMapOf()
    val statisticsIntervals: MutableList<StatisticsInterval> = mutableListOf()
    val pendingAudits: MutableList<AuditRecord> = mutableListOf()
    var myPositionId: Long? = null
    var quiet: QuietSettings = QuietSettings(QuietMode.OFF, false, emptyMap())
    var score: ScoreSettings = ScoreSettings(5, 2, 10, 30)
    var overlayEnabled: Boolean = false
    var leaveVersion: Long = 0
    /** Loaded from max(sqlite_sequence, existing IDs); incremented even when rows are deleted. */
    var nextPositionId: Long = 1
    var nextLeaveId: Long = 1
    var nextCallId: Long = 1
    var nextConfigVersion: Long = 1
}

fun defaultWorkSchedule(
    zoneId: String = ZoneId.systemDefault().id,
    version: Long = 1,
    effective: Long = 0,
): WorkScheduleSettings {
    val windows = listOf(TimeWindow(540, 720), TimeWindow(780, 1080))
    return WorkScheduleSettings(
        zoneId,
        (1..5).associateWith { windows },
        windows,
        version,
        effective,
    )
}
