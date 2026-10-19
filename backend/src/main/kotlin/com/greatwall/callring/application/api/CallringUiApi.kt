// API v1 declarations for UI/backend coordination. Not wired into the app yet.
package com.greatwall.callring.application.api

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface CallringUiApi {
    val edits: UiEditsApi get() = UnavailableUiEditsApi
    val conversation: ConversationApi get() = UnavailableConversationApi
    val aiSettings: AiSettingsApi get() = UnavailableAiSettingsApi
    val snapshots: AiSnapshotApi get() = UnavailableAiSnapshotApi
    fun commandApi(context: CommandContext): CallringUiApi = this
    suspend fun adoptIdentity(callId: Long, expectedGeneration: Long): ApiResult<CallerProfile> = aiUnavailable()
    val recordings: RecordingApi get() = UnavailableRecordingApi
    val home: HomeApi
    val callers: CallersApi
    val reports: ReportsApi
    val settings: SettingsApi
    val ringtone: RingtoneApi
    val runtime: RuntimeApi
    val widgets: WidgetBindingsApi
    val changes: ChangeFeed

    // 纯计算，可同步调用；不拨号、不记录事件、不访问系统通话记录。
    fun checkNumber(raw: String): NumberCheck
}

interface HomeApi {
    suspend fun load(): ApiResult<HomeData>
}

interface CallersApi {
    suspend fun getCallDetail(callId: Long): ApiResult<CallDetail> = aiUnavailable()
    suspend fun search(query: CallerSearch): ApiResult<Page<CallerProfile>>
    suspend fun getEditor(number: String?): ApiResult<CallerEditorData>
    suspend fun getOverview(query: OverviewQuery): ApiResult<CallerOverview>
    suspend fun getLatestCall(): ApiResult<CallDetail?>
    suspend fun saveProfile(command: SaveCallerProfile): ApiResult<CallerProfile>
    suspend fun setWhitelisted(number: String, enabled: Boolean): ApiResult<CallerProfile>
}

interface ReportsApi {
    suspend fun load(query: ReportQuery): ApiResult<CallerReport>
    suspend fun exportCsv(
        query: ReportExportQuery,
        destination: DocumentRef
    ): ApiResult<ExportReceipt>
}

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

interface RuntimeApi {
    suspend fun getStatus(): ApiResult<RuntimeStatus>
    suspend fun getDiagnostics(): ApiResult<List<DiagnosticEntry>>
    suspend fun requestCallLogSync(): ApiResult<SyncRequestReceipt>
}

interface WidgetBindingsApi {
    suspend fun listBindings(): ApiResult<WidgetBindingsListing> = aiUnavailable()
    suspend fun getBinding(widgetId: Int): ApiResult<WidgetBinding?>
    suspend fun saveBinding(widgetId: Int, number: String): ApiResult<WidgetBinding>
    suspend fun removeBinding(widgetId: Int): ApiResult<Unit>
}

interface ChangeFeed {
    fun observe(): Flow<DataChange>
}

sealed class ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>()
    data class Failure(val error: ApiError) : ApiResult<Nothing>()
}

data class ApiError(
    val code: ErrorCode,
    val message: String,
    val fieldErrors: Map<String, String> = emptyMap(),
    val requiredCapabilities: Set<Capability> = emptySet(),
    val retryable: Boolean = false,
    val aiFailure: ConversationFailure? = null,
)

enum class ErrorCode {
    VALIDATION, NOT_FOUND, IN_USE, PERMISSION_REQUIRED, ROLE_REQUIRED, CONFLICT,
    SYSTEM_RESTRICTED, SYSTEM_UNAVAILABLE, IO_FAILURE, STORAGE_FAILURE, INTERNAL
}

data class PageRequest(val pageIndex: Int = 0, val pageSize: Int = 50)

data class Page<T>(
    val items: List<T>,
    val pageIndex: Int,
    val pageSize: Int,
    val totalItems: Long,
    val meta: SnapshotMeta
)

data class SnapshotMeta(
    val dataRevision: Long,
    val loadedAtEpochMillis: Long
)

data class CallerSearch(
    val text: String = "",
    val page: PageRequest = PageRequest()
)

data class OverviewQuery(
    val number: String,
    val workAtEpochMillis: Long? = null
)

data class DateRange(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
    val zoneId: String? = null
)

data class TimeRange(
    val startInclusiveEpochMillis: Long,
    val endExclusiveEpochMillis: Long,
    val zoneId: String
)

data class ReportQuery(
    val number: String,
    val range: DateRange,
    val filter: CallFilter = CallFilter.ALL,
    val page: PageRequest = PageRequest()
)

data class ReportExportQuery(val number: String, val range: DateRange)

enum class CallFilter {
    ALL, WORKING, PERSONAL_LEAVE, HOLIDAY, REST_DAY, OUTSIDE_WORK_HOURS
}

data class DocumentRef(val uri: String)

data class NumberCheck(
    val normalized: String,
    val validProfileNumber: Boolean,
    val matchesRule: Boolean
)

data class PositionOption(
    val id: Long,
    val name: String,
    val level: Int,
    val active: Boolean,
    val relationToMe: PositionRelation
)

enum class PositionRelation { HIGHER, LOWER, SAME, UNKNOWN }

data class CallerProfile(
    val number: String,
    val stored: Boolean,
    val name: String,
    val department: String,
    val position: PositionOption?,
    val relation: PositionRelation,
    val whitelisted: Boolean
)

data class CallerDraft(
    val number: String,
    val name: String,
    val department: String,
    val positionId: Long?,
    val whitelisted: Boolean
)

data class CallerEditorData(
    val draft: CallerDraft,
    val selectablePositions: List<PositionOption>,
    val myPosition: PositionOption?
)

data class SaveCallerProfile(
    val number: String,
    val name: String,
    val department: String,
    val positionId: Long?,
    val whitelisted: Boolean? = null,
    val baseline:CallerDraft?=null,
    val dirtyFields:Set<String>?=null,
)

data class CallerOverview(
    val profile: CallerProfile,
    val rollingScore: ScoreSummary,
    val lifetimeCallCount: Long,
    val latestCall: CallDetail?,
    val work: WorkStatus,
    val quietMode: QuietMode,
    val numberCheck: NumberCheck,
    val quality: DataQuality,
    val meta: SnapshotMeta
)

enum class WorkReason {
    WORKING, MAKEUP_WORKING, PERSONAL_LEAVE, HOLIDAY,
    REST_DAY, OUTSIDE_WORK_HOURS, UNKNOWN
}

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

enum class WorkDayKind { WORKDAY, REST_DAY, HOLIDAY }

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

enum class CallResult {
    ANSWERED, MISSED, REJECTED, BLOCKED, ANSWERED_ELSEWHERE,
    PENDING, UNRESOLVED, UNKNOWN
}

enum class CallDecision {
    NORMAL_RINGING, WHITELIST_ALLOWED, QUIET_SILENCED,
    CUSTOM_RINGTONE, SYSTEM_RINGTONE_FALLBACK, SYSTEM_LOG_ONLY, UNKNOWN
}

enum class CallSource { SCREENING, SYSTEM_CALL_LOG, MERGED, UNKNOWN }

data class CallDetail(
    val id: Long,
    val number: String,
    val startedAtEpochMillis: Long,
    val screeningAtEpochMillis: Long?,
    val durationSeconds: Long?,
    val result: CallResult,
    val decision: CallDecision,
    val source: CallSource,
    val counted: Boolean,
    val classification: ClassificationFact,
    val originalClassification: ClassificationFact,
    val corrected: Boolean,
    val decisionNote: String?
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

data class DataQuality(
    val statisticsEnabled: Boolean,
    val callLogAccess: AccessState,
    val hasUnknownDuration: Boolean,
    val hasPendingResults: Boolean,
    val hasUnresolvedRecords: Boolean,
    val hasUnverifiedCalendar: Boolean
)

data class CallerReport(
    val profile: CallerProfile,
    val range: TimeRange,
    val totals: ReportTotals,
    val score: ScoreSummary,
    val details: Page<CallDetail>,
    val quality: DataQuality,
    val meta: SnapshotMeta
)

data class ExportReceipt(
    val number: String,
    val range: TimeRange,
    val recordCount: Long,
    val mimeType: String
)

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

enum class Capability {
    PHONE_STATE, NOTIFICATIONS, CALL_LOG, CONTACTS, MICROPHONE,
    OVERLAY, CALL_SCREENING_ROLE, BATTERY_OPTIMIZATION_EXEMPTION
}

enum class AccessState { GRANTED, DENIED, NOT_REQUIRED, UNAVAILABLE, UNKNOWN }
enum class Audibility { AUDIBLE, SILENT, VIBRATE, SYSTEM_DND, ZERO_VOLUME, UNKNOWN }
enum class PhoneActivity { IDLE, RINGING, ACTIVE, UNKNOWN }
enum class PlaybackMode { NONE, PREVIEW, RINGTONE }
enum class PlaybackState { IDLE, PREPARING, PLAYING, UNKNOWN }

data class PlaybackStatus(
    val mode: PlaybackMode,
    val state: PlaybackState
)

data class RuntimeStatus(
    val capabilities: Map<Capability, AccessState>,
    val audibility: Audibility,
    val phoneActivity: PhoneActivity,
    val playback: PlaybackStatus,
    val pinWidgetSupported: Boolean,
    val checkedAtEpochMillis: Long
)

enum class AudioSource { BUILT_IN, IMPORTED }

data class RingtoneSettings(
    val enabled: Boolean,
    val source: AudioSource,
    val displayName: String,
    val previewSeconds: Int
)

data class HomeData(
    val ringtone: RingtoneSettings,
    val runtime: RuntimeStatus,
    val currentWork: WorkStatus,
    val latestCall: CallDetail?,
    val statisticsEnabled: Boolean,
    val overlayEnabled: Boolean,
    val quietMode: QuietMode,
    val meta: SnapshotMeta
)

data class DiagnosticEntry(
    val occurredAtEpochMillis: Long?,
    val legacyTimeText: String?,
    val message: String
)

enum class SyncRequestState { QUEUED, ALREADY_QUEUED }

data class SyncRequestReceipt(
    val state: SyncRequestState,
    val requestedAtEpochMillis: Long
)

data class WidgetBinding(val widgetId: Int, val number: String)
data class WidgetInstallState(val ids: List<Int>, val access: AccessState)
data class WidgetBindingsListing(val stored: List<WidgetBinding>, val installed: WidgetInstallState)

enum class DataDomain {
    CALLERS, POSITIONS, CALLS, REPORTS, WORK_CALENDAR,
    QUIET_HOURS, SCORE_SETTINGS, STATISTICS, OVERLAY_SETTINGS,
    RINGTONE, APPEARANCE, WIDGET_BINDINGS, RUNTIME,
    RECORDING_SETTINGS, AI_SETTINGS, RECORDING_ARCHIVES
}

data class DataChange(
    val dataRevision: Long,
    val domains: Set<DataDomain>,
    val numbers: Set<String>?,
    val widgetIds: Set<Int>?
)

interface IncomingCallUiSink {
    fun show(request: IncomingCallUiRequest)
}

data class IncomingCallUiRequest(
    val sessionId: String,
    val number: String,
    val startedAtEpochMillis: Long,
    val decision: CallDecision
)
