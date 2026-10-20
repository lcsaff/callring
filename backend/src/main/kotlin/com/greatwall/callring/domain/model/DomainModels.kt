package com.greatwall.callring.domain.model

import java.time.LocalDate

data class WorkScheduleSettings(
    val zoneId: String,
    val weekly: Map<Int, List<TimeWindow>>,
    val makeup: List<TimeWindow>,
    val configVersion: Long,
    val effectiveFromEpochMillis: Long
)

data class CalendarYear(
    val year: Int,
    val source: String,
    val published: String,
    val version: String
)

data class DateOverride(
    val date: LocalDate,
    val kind: DateOverrideKind,
    val windows: List<TimeWindow>
)

data class QuietSettings(
    val mode: QuietMode,
    val allNumbers: Boolean,
    val weeklyWindows: Map<Int, List<TimeWindow>>
)

enum class QuietMode { OFF, NON_WORK, CUSTOM }

data class ScoreSettings(
    val offHoursWeight: Int,
    val repeatWeight: Int,
    val repeatMinutes: Int,
    val observationDays: Int
)

data class TimeWindow(val startMinute: Int, val endMinute: Int)

enum class CallResult {
    ANSWERED, MISSED, REJECTED, BLOCKED, ANSWERED_ELSEWHERE,
    PENDING, UNRESOLVED, UNKNOWN
}

enum class CallDecision {
    NORMAL_RINGING, WHITELIST_ALLOWED, QUIET_SILENCED,
    CUSTOM_RINGTONE, SYSTEM_RINGTONE_FALLBACK, SYSTEM_LOG_ONLY, UNKNOWN
}

enum class CallSource { SCREENING, SYSTEM_CALL_LOG, MERGED, UNKNOWN }

data class ClassificationFact(
    val working: Boolean,
    val reason: WorkReason,
    val holidayName: String?,
    val calendarVerified: Boolean,
    val zoneId: String,
    val configVersion: Long,
    val calendarVersion: String?,
    val leaveVersion: Long
)

enum class WorkReason {
    WORKING, MAKEUP_WORKING, PERSONAL_LEAVE, HOLIDAY,
    REST_DAY, OUTSIDE_WORK_HOURS, UNKNOWN
}

data class WorkStatus(
    val evaluatedAtEpochMillis: Long,
    val classification: ClassificationFact,
    val nextBoundaryEpochMillis: Long?,
    val dayKind: WorkDayKind = when (classification.reason) {
        WorkReason.HOLIDAY -> WorkDayKind.HOLIDAY
        WorkReason.REST_DAY -> WorkDayKind.REST_DAY
        else -> WorkDayKind.WORKDAY
    },
    val holidayName: String? = classification.holidayName,
)

enum class WorkDayKind { WORKDAY, REST_DAY, HOLIDAY }

enum class DateOverrideKind { REST, WORK }

data class TimeRange(
    val startInclusiveEpochMillis: Long,
    val endExclusiveEpochMillis: Long,
    val zoneId: String
)

data class ScoreSummary(
    val points: Int?,
    val totalCalls: Long,
    val offHoursCalls: Long,
    val repeatedCalls: Long,
    val offHoursWeight: Int,
    val repeatWeight: Int,
    val repeatMinutes: Int,
    val range: TimeRange
)

data class ReportTotals(
    val totalCalls: Long,
    val workingCalls: Long,
    val offHoursCalls: Long,
    val knownDurationSeconds: Long,
    val unknownDurationCount: Long,
    val missedCount: Long,
    val rejectedCount: Long,
    val pendingCount: Long
)
