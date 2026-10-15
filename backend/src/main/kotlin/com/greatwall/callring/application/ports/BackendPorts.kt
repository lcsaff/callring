package com.greatwall.callring.application.ports

import com.greatwall.callring.application.api.*
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId

/**
 * Implementations serialize reads and writes, use one SQL snapshot, and roll back on exceptions.
 * State belongs to the callback only; it must never escape or be reused by the adapter.
 */
interface BackendStore {
    fun <T> read(block: (BackendState) -> T): T

    fun <T> transaction(block: (BackendState) -> T): T
}

data class ProfileRecord(
    val number: String,
    val name: String = "",
    val department: String = "",
    val positionId: Long? = null,
    val whitelisted: Boolean = false,
)

data class PositionRecord(val id: Long, val name: String, val level: Int, val active: Boolean)

data class LeaveRecord(val id: Long, val startMillis: Long, val endMillis: Long, val note: String)

data class StatisticsInterval(val startMillis: Long, val endMillis: Long?)

data class CalendarImport(
    val metadata: CalendarYear,
    val holidays: Map<LocalDate, String>,
    val makeupDates: Set<LocalDate>,
    val rawJson: String? = null,
)

data class AuditRecord(
    val kind: String,
    val target: String,
    val raw: String,
    val createdMillis: Long,
)

data class RawCallRecord(
    val id: Long,
    val number: String,
    val time: Long,
    val duration: Long?,
    val result: CallResult,
    val decision: CallDecision,
    val source: CallSource,
    val logId: String?,
    val screeningKey: String?,
    val counted: Boolean,
    val classification: ClassificationFact,
    val originalClassification: ClassificationFact,
    val account: String? = null,
    val hidden: Boolean = false,
    val linkedScreenId: Long? = null,
    val decisionNote: String? = null,
    val legacyResult: String? = null,
    val legacyDecision: String? = null,
    val legacySource: String? = null,
)

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

interface PreferenceStore {
    fun getAppearance(): AppearanceSettings

    fun saveAppearance(settings: AppearanceSettings)

    fun getBinding(widgetId: Int): WidgetBinding?

    fun saveBinding(binding: WidgetBinding)

    fun removeBinding(widgetId: Int)
}

interface DocumentStore {
    fun <T> read(source: DocumentRef, block: (InputStream) -> T): T

    fun <T> write(destination: DocumentRef, block: (OutputStream) -> T): T

    /** Parse UTF-8 JSON with a hard 1 MiB limit; reject syntax errors. Core validates semantics. */
    fun readCalendar(source: DocumentRef): CalendarImport
}

interface RuntimeProbe {
    fun getStatus(): RuntimeStatus

    fun getDiagnostics(): List<DiagnosticEntry>
}

/**
 * Import/restore concurrency must use invocation generations to invalidate stale imports. Import
 * must validate <=20 MiB, playable duration, then atomically replace; failures preserve the
 * previous selection. No private paths in settings.
 */
interface AudioController {
    fun getSettings(): RingtoneSettings

    /**
     * The adapter persists first; disabling then stops playback on a best-effort basis. Playback
     * restrictions must not turn an already committed disable into a failure.
     */
    fun setEnabled(enabled: Boolean): RingtoneSettings

    fun importAudio(source: DocumentRef): RingtoneSettings = importAudio(source) {}

    /**
     * Checks run during preparation and immediately before atomic publication. Once published, the
     * controller returns without checking cancellation so the caller can notify the commit.
     */
    fun importAudio(source: DocumentRef, checkCancellation: () -> Unit): RingtoneSettings

    fun restoreBuiltIn(): RingtoneSettings

    fun startPreview()

    fun stopPlayback()
}

interface SyncScheduler {
    fun requestSync(atEpochMillis: Long): SyncRequestReceipt
}

/** Adapters may throw this to preserve a structured platform failure. */
class BackendFailure(val error: ApiError) : RuntimeException(error.message)

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
