package com.greatwall.callring.application.mapping

import java.util.Collections

import com.greatwall.callring.domain.model.WorkScheduleSettings
import com.greatwall.callring.contracts.settings.WorkScheduleSettings as ApiWorkScheduleSettings
import com.greatwall.callring.domain.model.CalendarYear
import com.greatwall.callring.contracts.settings.CalendarYear as ApiCalendarYear
import com.greatwall.callring.domain.model.DateOverride
import com.greatwall.callring.contracts.settings.DateOverride as ApiDateOverride
import com.greatwall.callring.domain.model.QuietSettings
import com.greatwall.callring.contracts.settings.QuietSettings as ApiQuietSettings
import com.greatwall.callring.domain.model.QuietMode
import com.greatwall.callring.contracts.settings.QuietMode as ApiQuietMode
import com.greatwall.callring.domain.model.ScoreSettings
import com.greatwall.callring.contracts.settings.ScoreSettings as ApiScoreSettings
import com.greatwall.callring.domain.model.TimeWindow
import com.greatwall.callring.contracts.settings.TimeWindow as ApiTimeWindow
import com.greatwall.callring.domain.model.CallResult
import com.greatwall.callring.contracts.callers.CallResult as ApiCallResult
import com.greatwall.callring.domain.model.CallDecision
import com.greatwall.callring.contracts.callers.CallDecision as ApiCallDecision
import com.greatwall.callring.domain.model.CallSource
import com.greatwall.callring.contracts.callers.CallSource as ApiCallSource
import com.greatwall.callring.domain.model.ClassificationFact
import com.greatwall.callring.contracts.callers.ClassificationFact as ApiClassificationFact
import com.greatwall.callring.domain.model.WorkReason
import com.greatwall.callring.contracts.callers.WorkReason as ApiWorkReason
import com.greatwall.callring.domain.model.WorkStatus
import com.greatwall.callring.contracts.callers.WorkStatus as ApiWorkStatus
import com.greatwall.callring.domain.model.WorkDayKind
import com.greatwall.callring.contracts.callers.WorkDayKind as ApiWorkDayKind
import com.greatwall.callring.domain.model.DateOverrideKind
import com.greatwall.callring.contracts.settings.DateOverrideKind as ApiDateOverrideKind
import com.greatwall.callring.domain.model.TimeRange
import com.greatwall.callring.contracts.common.TimeRange as ApiTimeRange
import com.greatwall.callring.domain.model.ScoreSummary
import com.greatwall.callring.contracts.report.ScoreSummary as ApiScoreSummary
import com.greatwall.callring.domain.model.ReportTotals
import com.greatwall.callring.contracts.report.ReportTotals as ApiReportTotals

fun WorkScheduleSettings.toContract(): ApiWorkScheduleSettings = ApiWorkScheduleSettings(zoneId, frozen(weekly.mapValues { (_, windows) -> frozen(windows.map { it.toContract() }) }), frozen(makeup.map { it.toContract() }), configVersion, effectiveFromEpochMillis)

fun ApiWorkScheduleSettings.toDomain(): WorkScheduleSettings = WorkScheduleSettings(zoneId, weekly.mapValues { (_, windows) -> windows.map { it.toDomain() } }, makeup.map { it.toDomain() }, configVersion, effectiveFromEpochMillis)

fun CalendarYear.toContract(): ApiCalendarYear = ApiCalendarYear(year, source, published, version)

fun ApiCalendarYear.toDomain(): CalendarYear = CalendarYear(year, source, published, version)

fun DateOverride.toContract(): ApiDateOverride = ApiDateOverride(date, kind.toContract(), frozen(windows.map { it.toContract() }))

fun ApiDateOverride.toDomain(): DateOverride = DateOverride(date, kind.toDomain(), windows.map { it.toDomain() })

fun QuietSettings.toContract(): ApiQuietSettings = ApiQuietSettings(mode.toContract(), allNumbers, frozen(weeklyWindows.mapValues { (_, windows) -> frozen(windows.map { it.toContract() }) }))

fun ApiQuietSettings.toDomain(): QuietSettings = QuietSettings(mode.toDomain(), allNumbers, weeklyWindows.mapValues { (_, windows) -> windows.map { it.toDomain() } })

fun QuietMode.toContract(): ApiQuietMode = when (this) {
    QuietMode.OFF -> ApiQuietMode.OFF
    QuietMode.NON_WORK -> ApiQuietMode.NON_WORK
    QuietMode.CUSTOM -> ApiQuietMode.CUSTOM
}
fun ApiQuietMode.toDomain(): QuietMode = when (this) {
    ApiQuietMode.OFF -> QuietMode.OFF
    ApiQuietMode.NON_WORK -> QuietMode.NON_WORK
    ApiQuietMode.CUSTOM -> QuietMode.CUSTOM
}

fun ScoreSettings.toContract(): ApiScoreSettings = ApiScoreSettings(offHoursWeight, repeatWeight, repeatMinutes, observationDays)

fun ApiScoreSettings.toDomain(): ScoreSettings = ScoreSettings(offHoursWeight, repeatWeight, repeatMinutes, observationDays)

fun TimeWindow.toContract(): ApiTimeWindow = ApiTimeWindow(startMinute, endMinute)

fun ApiTimeWindow.toDomain(): TimeWindow = TimeWindow(startMinute, endMinute)

fun CallResult.toContract(): ApiCallResult = when (this) {
    CallResult.ANSWERED -> ApiCallResult.ANSWERED
    CallResult.MISSED -> ApiCallResult.MISSED
    CallResult.REJECTED -> ApiCallResult.REJECTED
    CallResult.BLOCKED -> ApiCallResult.BLOCKED
    CallResult.ANSWERED_ELSEWHERE -> ApiCallResult.ANSWERED_ELSEWHERE
    CallResult.PENDING -> ApiCallResult.PENDING
    CallResult.UNRESOLVED -> ApiCallResult.UNRESOLVED
    CallResult.UNKNOWN -> ApiCallResult.UNKNOWN
}
fun ApiCallResult.toDomain(): CallResult = when (this) {
    ApiCallResult.ANSWERED -> CallResult.ANSWERED
    ApiCallResult.MISSED -> CallResult.MISSED
    ApiCallResult.REJECTED -> CallResult.REJECTED
    ApiCallResult.BLOCKED -> CallResult.BLOCKED
    ApiCallResult.ANSWERED_ELSEWHERE -> CallResult.ANSWERED_ELSEWHERE
    ApiCallResult.PENDING -> CallResult.PENDING
    ApiCallResult.UNRESOLVED -> CallResult.UNRESOLVED
    ApiCallResult.UNKNOWN -> CallResult.UNKNOWN
}

fun CallDecision.toContract(): ApiCallDecision = when (this) {
    CallDecision.NORMAL_RINGING -> ApiCallDecision.NORMAL_RINGING
    CallDecision.WHITELIST_ALLOWED -> ApiCallDecision.WHITELIST_ALLOWED
    CallDecision.QUIET_SILENCED -> ApiCallDecision.QUIET_SILENCED
    CallDecision.CUSTOM_RINGTONE -> ApiCallDecision.CUSTOM_RINGTONE
    CallDecision.SYSTEM_RINGTONE_FALLBACK -> ApiCallDecision.SYSTEM_RINGTONE_FALLBACK
    CallDecision.SYSTEM_LOG_ONLY -> ApiCallDecision.SYSTEM_LOG_ONLY
    CallDecision.UNKNOWN -> ApiCallDecision.UNKNOWN
}
fun ApiCallDecision.toDomain(): CallDecision = when (this) {
    ApiCallDecision.NORMAL_RINGING -> CallDecision.NORMAL_RINGING
    ApiCallDecision.WHITELIST_ALLOWED -> CallDecision.WHITELIST_ALLOWED
    ApiCallDecision.QUIET_SILENCED -> CallDecision.QUIET_SILENCED
    ApiCallDecision.CUSTOM_RINGTONE -> CallDecision.CUSTOM_RINGTONE
    ApiCallDecision.SYSTEM_RINGTONE_FALLBACK -> CallDecision.SYSTEM_RINGTONE_FALLBACK
    ApiCallDecision.SYSTEM_LOG_ONLY -> CallDecision.SYSTEM_LOG_ONLY
    ApiCallDecision.UNKNOWN -> CallDecision.UNKNOWN
}

fun CallSource.toContract(): ApiCallSource = when (this) {
    CallSource.SCREENING -> ApiCallSource.SCREENING
    CallSource.SYSTEM_CALL_LOG -> ApiCallSource.SYSTEM_CALL_LOG
    CallSource.MERGED -> ApiCallSource.MERGED
    CallSource.UNKNOWN -> ApiCallSource.UNKNOWN
}
fun ApiCallSource.toDomain(): CallSource = when (this) {
    ApiCallSource.SCREENING -> CallSource.SCREENING
    ApiCallSource.SYSTEM_CALL_LOG -> CallSource.SYSTEM_CALL_LOG
    ApiCallSource.MERGED -> CallSource.MERGED
    ApiCallSource.UNKNOWN -> CallSource.UNKNOWN
}

fun ClassificationFact.toContract(): ApiClassificationFact = ApiClassificationFact(working, reason.toContract(), holidayName, calendarVerified, zoneId, configVersion, calendarVersion, leaveVersion)

fun ApiClassificationFact.toDomain(): ClassificationFact = ClassificationFact(working, reason.toDomain(), holidayName, calendarVerified, zoneId, configVersion, calendarVersion, leaveVersion)

fun WorkReason.toContract(): ApiWorkReason = when (this) {
    WorkReason.WORKING -> ApiWorkReason.WORKING
    WorkReason.MAKEUP_WORKING -> ApiWorkReason.MAKEUP_WORKING
    WorkReason.PERSONAL_LEAVE -> ApiWorkReason.PERSONAL_LEAVE
    WorkReason.HOLIDAY -> ApiWorkReason.HOLIDAY
    WorkReason.REST_DAY -> ApiWorkReason.REST_DAY
    WorkReason.OUTSIDE_WORK_HOURS -> ApiWorkReason.OUTSIDE_WORK_HOURS
    WorkReason.UNKNOWN -> ApiWorkReason.UNKNOWN
}
fun ApiWorkReason.toDomain(): WorkReason = when (this) {
    ApiWorkReason.WORKING -> WorkReason.WORKING
    ApiWorkReason.MAKEUP_WORKING -> WorkReason.MAKEUP_WORKING
    ApiWorkReason.PERSONAL_LEAVE -> WorkReason.PERSONAL_LEAVE
    ApiWorkReason.HOLIDAY -> WorkReason.HOLIDAY
    ApiWorkReason.REST_DAY -> WorkReason.REST_DAY
    ApiWorkReason.OUTSIDE_WORK_HOURS -> WorkReason.OUTSIDE_WORK_HOURS
    ApiWorkReason.UNKNOWN -> WorkReason.UNKNOWN
}

fun WorkStatus.toContract(): ApiWorkStatus = ApiWorkStatus(evaluatedAtEpochMillis, classification.toContract(), nextBoundaryEpochMillis, dayKind.toContract(), holidayName)

fun ApiWorkStatus.toDomain(): WorkStatus = WorkStatus(evaluatedAtEpochMillis, classification.toDomain(), nextBoundaryEpochMillis, dayKind.toDomain(), holidayName)

fun WorkDayKind.toContract(): ApiWorkDayKind = when (this) {
    WorkDayKind.WORKDAY -> ApiWorkDayKind.WORKDAY
    WorkDayKind.REST_DAY -> ApiWorkDayKind.REST_DAY
    WorkDayKind.HOLIDAY -> ApiWorkDayKind.HOLIDAY
}
fun ApiWorkDayKind.toDomain(): WorkDayKind = when (this) {
    ApiWorkDayKind.WORKDAY -> WorkDayKind.WORKDAY
    ApiWorkDayKind.REST_DAY -> WorkDayKind.REST_DAY
    ApiWorkDayKind.HOLIDAY -> WorkDayKind.HOLIDAY
}

fun DateOverrideKind.toContract(): ApiDateOverrideKind = when (this) {
    DateOverrideKind.REST -> ApiDateOverrideKind.REST
    DateOverrideKind.WORK -> ApiDateOverrideKind.WORK
}
fun ApiDateOverrideKind.toDomain(): DateOverrideKind = when (this) {
    ApiDateOverrideKind.REST -> DateOverrideKind.REST
    ApiDateOverrideKind.WORK -> DateOverrideKind.WORK
}

fun TimeRange.toContract(): ApiTimeRange = ApiTimeRange(startInclusiveEpochMillis, endExclusiveEpochMillis, zoneId)

fun ApiTimeRange.toDomain(): TimeRange = TimeRange(startInclusiveEpochMillis, endExclusiveEpochMillis, zoneId)

fun ScoreSummary.toContract(): ApiScoreSummary = ApiScoreSummary(points, totalCalls, offHoursCalls, repeatedCalls, offHoursWeight, repeatWeight, repeatMinutes, range.toContract())

fun ApiScoreSummary.toDomain(): ScoreSummary = ScoreSummary(points, totalCalls, offHoursCalls, repeatedCalls, offHoursWeight, repeatWeight, repeatMinutes, range.toDomain())

fun ReportTotals.toContract(): ApiReportTotals = ApiReportTotals(totalCalls, workingCalls, offHoursCalls, knownDurationSeconds, unknownDurationCount, missedCount, rejectedCount, pendingCount)

fun ApiReportTotals.toDomain(): ReportTotals = ReportTotals(totalCalls, workingCalls, offHoursCalls, knownDurationSeconds, unknownDurationCount, missedCount, rejectedCount, pendingCount)

private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun <K, V> frozen(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))

fun com.greatwall.callring.domain.ai.AiProtocol.toContract(): com.greatwall.callring.contracts.ai.AiProtocol = when (this) {
    com.greatwall.callring.domain.ai.AiProtocol.OPENAI -> com.greatwall.callring.contracts.ai.AiProtocol.OPENAI
    com.greatwall.callring.domain.ai.AiProtocol.ANTHROPIC -> com.greatwall.callring.contracts.ai.AiProtocol.ANTHROPIC
}
fun com.greatwall.callring.contracts.ai.AiProtocol.toDomain(): com.greatwall.callring.domain.ai.AiProtocol = when (this) {
    com.greatwall.callring.contracts.ai.AiProtocol.OPENAI -> com.greatwall.callring.domain.ai.AiProtocol.OPENAI
    com.greatwall.callring.contracts.ai.AiProtocol.ANTHROPIC -> com.greatwall.callring.domain.ai.AiProtocol.ANTHROPIC
}

fun com.greatwall.callring.domain.ai.ThinkingMode.toContract(): com.greatwall.callring.contracts.ai.ThinkingMode = when (this) {
    com.greatwall.callring.domain.ai.ThinkingMode.ENABLED -> com.greatwall.callring.contracts.ai.ThinkingMode.ENABLED
    com.greatwall.callring.domain.ai.ThinkingMode.DISABLED -> com.greatwall.callring.contracts.ai.ThinkingMode.DISABLED
}
fun com.greatwall.callring.contracts.ai.ThinkingMode.toDomain(): com.greatwall.callring.domain.ai.ThinkingMode = when (this) {
    com.greatwall.callring.contracts.ai.ThinkingMode.ENABLED -> com.greatwall.callring.domain.ai.ThinkingMode.ENABLED
    com.greatwall.callring.contracts.ai.ThinkingMode.DISABLED -> com.greatwall.callring.domain.ai.ThinkingMode.DISABLED
}

fun com.greatwall.callring.domain.ai.ThinkingSupport.toContract(): com.greatwall.callring.contracts.ai.ThinkingSupport = when (this) {
    com.greatwall.callring.domain.ai.ThinkingSupport.AVAILABLE -> com.greatwall.callring.contracts.ai.ThinkingSupport.AVAILABLE
    com.greatwall.callring.domain.ai.ThinkingSupport.CUSTOM_GATEWAY -> com.greatwall.callring.contracts.ai.ThinkingSupport.CUSTOM_GATEWAY
    com.greatwall.callring.domain.ai.ThinkingSupport.UNSUPPORTED -> com.greatwall.callring.contracts.ai.ThinkingSupport.UNSUPPORTED
    com.greatwall.callring.domain.ai.ThinkingSupport.UNRECOGNIZED -> com.greatwall.callring.contracts.ai.ThinkingSupport.UNRECOGNIZED
}
fun com.greatwall.callring.contracts.ai.ThinkingSupport.toDomain(): com.greatwall.callring.domain.ai.ThinkingSupport = when (this) {
    com.greatwall.callring.contracts.ai.ThinkingSupport.AVAILABLE -> com.greatwall.callring.domain.ai.ThinkingSupport.AVAILABLE
    com.greatwall.callring.contracts.ai.ThinkingSupport.CUSTOM_GATEWAY -> com.greatwall.callring.domain.ai.ThinkingSupport.CUSTOM_GATEWAY
    com.greatwall.callring.contracts.ai.ThinkingSupport.UNSUPPORTED -> com.greatwall.callring.domain.ai.ThinkingSupport.UNSUPPORTED
    com.greatwall.callring.contracts.ai.ThinkingSupport.UNRECOGNIZED -> com.greatwall.callring.domain.ai.ThinkingSupport.UNRECOGNIZED
}

fun com.greatwall.callring.domain.ai.ConversationCompatibility.toContract(): com.greatwall.callring.contracts.ai.ConversationCompatibility = when (this) {
    com.greatwall.callring.domain.ai.ConversationCompatibility.AUTO -> com.greatwall.callring.contracts.ai.ConversationCompatibility.AUTO
    com.greatwall.callring.domain.ai.ConversationCompatibility.STANDARD -> com.greatwall.callring.contracts.ai.ConversationCompatibility.STANDARD
    com.greatwall.callring.domain.ai.ConversationCompatibility.QWEN_FAST -> com.greatwall.callring.contracts.ai.ConversationCompatibility.QWEN_FAST
}
fun com.greatwall.callring.contracts.ai.ConversationCompatibility.toDomain(): com.greatwall.callring.domain.ai.ConversationCompatibility = when (this) {
    com.greatwall.callring.contracts.ai.ConversationCompatibility.AUTO -> com.greatwall.callring.domain.ai.ConversationCompatibility.AUTO
    com.greatwall.callring.contracts.ai.ConversationCompatibility.STANDARD -> com.greatwall.callring.domain.ai.ConversationCompatibility.STANDARD
    com.greatwall.callring.contracts.ai.ConversationCompatibility.QWEN_FAST -> com.greatwall.callring.domain.ai.ConversationCompatibility.QWEN_FAST
}

fun com.greatwall.callring.domain.ai.ProviderProfile.toContract(): com.greatwall.callring.contracts.ai.ProviderProfile = when (this) {
    com.greatwall.callring.domain.ai.ProviderProfile.STANDARD -> com.greatwall.callring.contracts.ai.ProviderProfile.STANDARD
    com.greatwall.callring.domain.ai.ProviderProfile.ANTHROPIC -> com.greatwall.callring.contracts.ai.ProviderProfile.ANTHROPIC
    com.greatwall.callring.domain.ai.ProviderProfile.QWEN_FAST -> com.greatwall.callring.contracts.ai.ProviderProfile.QWEN_FAST
}
fun com.greatwall.callring.contracts.ai.ProviderProfile.toDomain(): com.greatwall.callring.domain.ai.ProviderProfile = when (this) {
    com.greatwall.callring.contracts.ai.ProviderProfile.STANDARD -> com.greatwall.callring.domain.ai.ProviderProfile.STANDARD
    com.greatwall.callring.contracts.ai.ProviderProfile.ANTHROPIC -> com.greatwall.callring.domain.ai.ProviderProfile.ANTHROPIC
    com.greatwall.callring.contracts.ai.ProviderProfile.QWEN_FAST -> com.greatwall.callring.domain.ai.ProviderProfile.QWEN_FAST
}

fun com.greatwall.callring.contracts.ai.ConversationBudget.toDomain() = com.greatwall.callring.domain.ai.ConversationBudget(context, output)
