package com.greatwall.callring.ui.testing

import com.greatwall.callring.ui.testing.TestChangeFeed
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import java.time.LocalDate

/** Deterministic screen fixtures. No business rules or production wiring live in this source set. */
open class UiFakeApi(private val overlayEnabled: Boolean = false) : CallringUiApi, HomeApi, CallersApi, ReportsApi, SettingsApi, RingtoneApi, RuntimeApi, WidgetBindingsApi, ChangeFeed {
    override suspend fun getNumberRule(): ApiResult<NumberRuleSettings> = unexpected()
    override suspend fun saveNumberRule(command: SaveNumberRule): ApiResult<NumberRuleSettings> = unexpected()
    override suspend fun setQuietEnabled(enabled: Boolean): ApiResult<QuietSettings> = unexpected()
    override suspend fun synchronizeCalendar(year: Int): ApiResult<CalendarInfo> = unexpected()
    override fun commandApi(context: CommandContext): CallringUiApi = this
    override suspend fun adoptIdentity(callId: Long, expectedGeneration: Long): ApiResult<CallerProfile> = unexpected()
    override suspend fun getCallDetail(callId: Long): ApiResult<CallDetail> = unexpected()
    override suspend fun numberDataImpact(number: String): ApiResult<NumberCoreImpact> = unexpected()
    override suspend fun deleteNumberCore(number: String, expectedCallCount: Int): ApiResult<Unit> = unexpected()
    override suspend fun listBindings(): ApiResult<WidgetBindingsListing> = unexpected()
    override val conversation: ConversationApi = FixtureConversationApi
    override val aiSettings: AiSettingsApi = FixtureAiSettingsApi
    override val numberDeletion: NumberDeletionApi = FixtureNumberDeletionApi
    override val update: AppUpdateApi = FixtureAppUpdateApi
    override val snapshots: AiSnapshotApi = FixtureAiSnapshotApi
    override val recordings: RecordingApi = FixtureRecordingApi
    override val home: HomeApi = this
    override val callers: CallersApi = this
    override val reports: ReportsApi = this
    override val settings: SettingsApi = this
    var profileEdits = 0
        private set
    override val edits: UiEditsApi = object : UiEditsApi {
        override suspend fun profile(base: CallerDraft, value: CallerDraft): ApiResult<CallerProfile> {
            profileEdits++
            return saveProfile(SaveCallerProfile(value.number, value.name, value.department,
                value.positionId, value.whitelisted, baseline = base))
        }
        override suspend fun quiet(base: QuietSettings, value: QuietSettings) = saveQuietHours(value)
        override suspend fun work(base: WorkScheduleSettings, value: WorkScheduleSettings) = saveWorkSchedule(SaveWorkSchedule(value.zoneId, value.weekly, value.makeup))
        override suspend fun score(base: ScoreSettings, value: ScoreSettings) = saveScoreSettings(value)
        override suspend fun appearance(base: AppearanceSettings, value: AppearanceSettings) = saveAppearance(value)
        override suspend fun position(base: SavePosition?, value: SavePosition) = savePosition(value)
        override suspend fun leave(base: SaveLeave?, value: SaveLeave) = saveLeave(value)
    }
    override val ringtone: RingtoneApi = this
    override val runtime: RuntimeApi = this
    override val widgets: WidgetBindingsApi = this
    override val changes: ChangeFeed = this
    protected val feed = TestChangeFeed()
    override fun snapshots() = feed.snapshots()
    override fun checkNumber(raw: String) = NumberCheck(raw, raw == "100", false)
    var appearance = DefaultAppearance
        private set
    var profile = CallerProfile("100", true, "测试档案", "示例部门", null, PositionRelation.UNKNOWN, false)
        private set
    var savedProfile: SaveCallerProfile? = null
        private set
    private val meta = SnapshotMeta(0, 0)
    private val time = TimeRange(0, 1, "Asia/Shanghai")
    private val fact = ClassificationFact(false, WorkReason.REST_DAY, null, true, "Asia/Shanghai", 1, null, 1)
    private val sound = RingtoneSettings(false, AudioSource.BUILT_IN, "测试铃声", 8)
    private val status = RuntimeStatus(Capability.entries.associateWith { AccessState.GRANTED }, Audibility.AUDIBLE,
        PhoneActivity.IDLE, PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE), true, 0)
    private val score = ScoreSettings(5, 2, 10, 30)
    private val work = WorkScheduleSettings("Asia/Shanghai", mapOf(1 to listOf(TimeWindow(540, 1080))), listOf(TimeWindow(540, 1080)), 1, 0)

    override suspend fun load() = ApiResult.Success(HomeData(sound, status, WorkStatus(0, fact, null), null, false, overlayEnabled, QuietMode.OFF, meta))
    override suspend fun search(query: CallerSearch): ApiResult<Page<CallerProfile>> = ApiResult.Success(Page(listOf(profile), 0, 50, 1, meta))
    override suspend fun getEditor(number: String?) = ApiResult.Success(CallerEditorData(
        if (number == null) CallerDraft("", "", "", null, false) else CallerDraft(profile.number, profile.name, profile.department, null, profile.whitelisted), emptyList(), null))
    override suspend fun getOverview(query: OverviewQuery): ApiResult<CallerOverview> = ApiResult.Success(CallerOverview(profile,
        ScoreSummary(80, 999, 0, 0, 5, 2, 10, time), 999, null, WorkStatus(0, fact, null), QuietMode.OFF, checkNumber(query.number),
        DataQuality(true, AccessState.GRANTED, false, false, false, false), meta))
    override suspend fun getLatestCall(): ApiResult<CallDetail?> = ApiResult.Success(null)
    override suspend fun saveProfile(command: SaveCallerProfile): ApiResult<CallerProfile> {
        savedProfile = command
        profile = profile.copy(number = command.number, name = command.name, department = command.department, whitelisted = command.whitelisted ?: profile.whitelisted)
        changed(DataDomain.CALLERS)
        return ApiResult.Success(profile)
    }
    override suspend fun setWhitelisted(number: String, enabled: Boolean): ApiResult<CallerProfile> {
        profile = profile.copy(whitelisted = enabled); changed(DataDomain.CALLERS); return ApiResult.Success(profile)
    }
    override suspend fun load(query: ReportQuery) = ApiResult.Success(CallerReport(profile, time,
        ReportTotals(999, 999, 0, 0, 0, 0, 0, 0), ScoreSummary(80, 999, 0, 0, 5, 2, 10, time),
        Page(emptyList(), 0, 50, 0, meta), DataQuality(true, AccessState.GRANTED, false, false, false, false), meta))
    override suspend fun exportCsv(query: ReportExportQuery, destination: DocumentRef): ApiResult<ExportReceipt> = unexpected()
    override suspend fun getSummary() = ApiResult.Success(SettingsSummary(false, overlayEnabled, QuietMode.OFF, null, "Asia/Shanghai", score, appearance, meta))
    override suspend fun setStatisticsEnabled(enabled: Boolean): ApiResult<Unit> = unexpected()
    override suspend fun setOverlayEnabled(enabled: Boolean): ApiResult<Unit> = unexpected()
    override suspend fun getQuietHours() = ApiResult.Success(QuietSettings(QuietMode.OFF, false, emptyMap()))
    override suspend fun saveQuietHours(settings: QuietSettings): ApiResult<QuietSettings> = unexpected()
    override suspend fun setQuietMode(mode: QuietMode): ApiResult<QuietSettings> = unexpected()
    override suspend fun getPositions() = ApiResult.Success(PositionSettings(emptyList(), null))
    override suspend fun savePosition(command: SavePosition): ApiResult<PositionOption> = unexpected()
    override suspend fun deletePosition(id: Long): ApiResult<Unit> = unexpected()
    override suspend fun setMyPosition(id: Long?): ApiResult<PositionSettings> = unexpected()
    override suspend fun getWorkSchedule() = ApiResult.Success(work)
    override suspend fun saveWorkSchedule(command: SaveWorkSchedule): ApiResult<WorkScheduleSettings> = unexpected()
    override suspend fun resetWorkSchedule(): ApiResult<WorkScheduleSettings> = unexpected()
    override suspend fun getCalendar() = ApiResult.Success(CalendarInfo(emptyList(), emptyList()))
    override suspend fun importCalendar(source: DocumentRef): ApiResult<CalendarInfo> = unexpected()
    override suspend fun saveDateOverride(command: DateOverride): ApiResult<CalendarInfo> = unexpected()
    override suspend fun deleteDateOverride(date: LocalDate): ApiResult<CalendarInfo> = unexpected()
    override suspend fun getLeaves(): ApiResult<List<LeaveEntry>> = ApiResult.Success(emptyList())
    override suspend fun saveLeave(command: SaveLeave): ApiResult<LeaveEntry> = unexpected()
    override suspend fun deleteLeave(id: Long): ApiResult<Unit> = unexpected()
    override suspend fun getScoreSettings() = ApiResult.Success(score)
    override suspend fun saveScoreSettings(settings: ScoreSettings): ApiResult<ScoreSettings> = unexpected()
    override suspend fun resetScoreSettings(): ApiResult<ScoreSettings> = unexpected()
    override suspend fun getAppearance() = ApiResult.Success(appearance)
    override suspend fun saveAppearance(settings: AppearanceSettings): ApiResult<AppearanceSettings> {
        appearance = settings; changed(DataDomain.APPEARANCE); return ApiResult.Success(settings)
    }
    override suspend fun getSettings() = ApiResult.Success(sound)
    override suspend fun setEnabled(enabled: Boolean): ApiResult<RingtoneSettings> = unexpected()
    override suspend fun importAudio(source: DocumentRef): ApiResult<RingtoneSettings> = unexpected()
    override suspend fun restoreBuiltIn(): ApiResult<RingtoneSettings> = unexpected()
    override suspend fun startPreview(): ApiResult<Unit> = unexpected()
    override suspend fun stopPlayback(): ApiResult<Unit> = unexpected()
    override suspend fun getStatus() = ApiResult.Success(status)
    override suspend fun getDiagnostics() = ApiResult.Success(emptyList<DiagnosticEntry>())
    override suspend fun requestCallLogSync(): ApiResult<SyncRequestReceipt> = unexpected()
    override suspend fun getBinding(widgetId: Int): ApiResult<WidgetBinding?> = unexpected()
    override suspend fun saveBinding(widgetId: Int, number: String): ApiResult<WidgetBinding> = unexpected()
    override suspend fun removeBinding(widgetId: Int): ApiResult<Unit> = unexpected()
    private fun changed(domain: DataDomain) { feed.publish(setOf(domain)) }
    private fun unexpected(): Nothing = error("This operation has no fixture in this test")
}
