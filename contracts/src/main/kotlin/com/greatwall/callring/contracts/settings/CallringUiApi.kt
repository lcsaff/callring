package com.greatwall.callring.contracts.settings

import com.greatwall.callring.contracts.common.*
import java.time.LocalDate

interface SettingsApi {
    suspend fun getSummary(): ApiResult<SettingsSummary>
    suspend fun setStatisticsEnabled(enabled: Boolean): ApiResult<Unit>
    suspend fun setOverlayEnabled(enabled: Boolean): ApiResult<Unit>

    suspend fun getQuietHours(): ApiResult<QuietSettings>
    suspend fun saveQuietHours(settings: QuietSettings): ApiResult<QuietSettings>
    suspend fun setQuietMode(mode: QuietMode): ApiResult<QuietSettings>
    suspend fun setQuietEnabled(enabled: Boolean): ApiResult<QuietSettings> =
        ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "quiet.enable_unavailable"))

    suspend fun getPositions(): ApiResult<PositionSettings>
    suspend fun savePosition(command: SavePosition): ApiResult<PositionOption>
    suspend fun deletePosition(id: Long): ApiResult<Unit>
    suspend fun setMyPosition(id: Long?): ApiResult<PositionSettings>

    suspend fun getWorkSchedule(): ApiResult<WorkScheduleSettings>
    suspend fun saveWorkSchedule(command: SaveWorkSchedule): ApiResult<WorkScheduleSettings>
    suspend fun resetWorkSchedule(): ApiResult<WorkScheduleSettings>

    suspend fun getCalendar(): ApiResult<CalendarInfo>
    suspend fun synchronizeCalendar(year: Int): ApiResult<CalendarInfo> =
        ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "calendar.sync_unavailable", retryable = true))
    suspend fun importCalendar(source: DocumentRef): ApiResult<CalendarInfo>
    suspend fun saveDateOverride(command: DateOverride): ApiResult<CalendarInfo>
    suspend fun deleteDateOverride(date: LocalDate): ApiResult<CalendarInfo>

    suspend fun getLeaves(): ApiResult<List<LeaveEntry>>
    suspend fun saveLeave(command: SaveLeave): ApiResult<LeaveEntry>
    suspend fun deleteLeave(id: Long): ApiResult<Unit>

    suspend fun getScoreSettings(): ApiResult<ScoreSettings>
    suspend fun saveScoreSettings(settings: ScoreSettings): ApiResult<ScoreSettings>
    suspend fun resetScoreSettings(): ApiResult<ScoreSettings>

    suspend fun getAppearance(): ApiResult<AppearanceSettings>
    suspend fun saveAppearance(settings: AppearanceSettings): ApiResult<AppearanceSettings>
}

interface RingtoneApi {
    suspend fun getSettings(): ApiResult<RingtoneSettings>
    suspend fun setEnabled(enabled: Boolean): ApiResult<RingtoneSettings>
    suspend fun importAudio(source: DocumentRef): ApiResult<RingtoneSettings>
    suspend fun restoreBuiltIn(): ApiResult<RingtoneSettings>
    suspend fun startPreview(): ApiResult<Unit>
    suspend fun stopPlayback(): ApiResult<Unit>
}

data class PositionOption(
    val id: Long,
    val name: String,
    val level: Int,
    val active: Boolean,
    val relationToMe: PositionRelation
)

enum class PositionRelation { HIGHER, LOWER, SAME, UNKNOWN }

enum class QuietMode { OFF, NON_WORK, CUSTOM }

data class TimeWindow(val startMinute: Int, val endMinute: Int)

data class QuietSettings(
    val mode: QuietMode,
    val allNumbers: Boolean,
    val weeklyWindows: Map<Int, List<TimeWindow>>
)

data class PositionSettings(
    val options: List<PositionOption>,
    val myPositionId: Long?
)

data class SavePosition(
    val id: Long? = null,
    val name: String,
    val level: Int,
    val active: Boolean
)

data class WorkScheduleSettings(
    val zoneId: String,
    val weekly: Map<Int, List<TimeWindow>>,
    val makeup: List<TimeWindow>,
    val configVersion: Long,
    val effectiveFromEpochMillis: Long
)

data class SaveWorkSchedule(
    val zoneId: String,
    val weekly: Map<Int, List<TimeWindow>>,
    val makeup: List<TimeWindow>
)

data class CalendarYear(
    val year: Int,
    val source: String,
    val published: String,
    val version: String
)

enum class DateOverrideKind { REST, WORK }

data class DateOverride(
    val date: LocalDate,
    val kind: DateOverrideKind,
    val windows: List<TimeWindow>
)

data class CalendarInfo(
    val years: List<CalendarYear>,
    val overrides: List<DateOverride>
)

enum class LeaveState { UPCOMING, ACTIVE, FINISHED }

sealed class LeavePeriod {
    data class AllDay(
        val startDate: LocalDate,
        val endDateInclusive: LocalDate,
        val zoneId: String
    ) : LeavePeriod()

    data class Timed(
        val startInclusiveEpochMillis: Long,
        val endExclusiveEpochMillis: Long
    ) : LeavePeriod()
}

data class SaveLeave(
    val id: Long? = null,
    val period: LeavePeriod,
    val note: String
)

data class LeaveEntry(
    val id: Long,
    val startInclusiveEpochMillis: Long,
    val endExclusiveEpochMillis: Long,
    val note: String,
    val state: LeaveState
)

data class ScoreSettings(
    val offHoursWeight: Int,
    val repeatWeight: Int,
    val repeatMinutes: Int,
    val observationDays: Int
)

enum class ThemeSelection { PRESET, CUSTOM }

enum class ColorMode { FOLLOW_SYSTEM, LIGHT, DARK }

data class AppearanceSettings(
    val selection: ThemeSelection,
    val presetId: String,
    val customSeedRgb: Int?,
    val colorMode: ColorMode
)

data class SettingsSummary(
    val statisticsEnabled: Boolean,
    val overlayEnabled: Boolean,
    val quietMode: QuietMode,
    val myPosition: PositionOption?,
    val workZoneId: String,
    val score: ScoreSettings,
    val appearance: AppearanceSettings,
    val meta: SnapshotMeta
)

enum class AudioSource { BUILT_IN, IMPORTED }

data class RingtoneSettings(
    val enabled: Boolean,
    val source: AudioSource,
    val displayName: String,
    val previewSeconds: Int
)
