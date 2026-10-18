package com.greatwall.callring.application.conversation

import com.greatwall.callring.application.*
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.ports.BackendFailure
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

data class ToolExecutionContext(val sessionId: String, val turnId: String, val generation: Long, val command: CommandContext)
class ToolExecutor(
    private val api: () -> CallringUiApi,
    private val coordinator: BusinessRevisionCoordinator,
    private val foreground: ForegroundActionService,
    private val resources: ForegroundResourceRegistry,
    val registry: ToolRegistry = ToolRegistry(),
) {
    suspend fun execute(call: ConversationToolCall, scope: ToolExecutionContext): ToolReceipt {
        val before = coordinator.capture()
        try {
            val definition = registry.validate(call)
            if (definition.write) {
                require(foreground.hasHost) { "action.foreground_required" }
                coordinator.guard(scope.command) {}
            }
            val bound = api().commandApi(scope.command)
            var status = if(definition.write) ToolStatus.COMMITTED else ToolStatus.READ
            suspend fun action(kind: ForegroundActionKind, parameters: AiJson.Obj = call.arguments, confirmation: ConfirmationTarget? = null): ForegroundHostResult {
                val result = foreground.request(scope.sessionId, scope.turnId, call.id, scope.generation, kind, parameters, scope.command, confirmation, scope.command.active)
                result.error?.let { throw BackendFailure(it) }
                when (result.status) {
                    ForegroundResultStatus.CANCELLED -> throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED, "action.cancelled"))
                    ForegroundResultStatus.DENIED -> throw BackendFailure(ApiError(ErrorCode.PERMISSION_REQUIRED, "action.denied"))
                    ForegroundResultStatus.UNAVAILABLE -> throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "action.unavailable"))
                    ForegroundResultStatus.STALE -> throw BackendFailure(ApiError(ErrorCode.CONFLICT, "action.stale"))
                    ForegroundResultStatus.EXPIRED -> throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED, "action.expired"))
                    ForegroundResultStatus.UNKNOWN -> throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "action.unknown"))
                    else -> Unit
                }
                while(!foreground.hasHost) { if(!scope.command.active()) throw CancellationException("ai.cancelled"); delay(100) }
                return result
            }
            suspend fun document(kind: ForegroundActionKind): DocumentRef {
                val result = action(kind)
                coordinator.guard(scope.command) {}
                return result.document ?: throw BackendFailure(ApiError(ErrorCode.IO_FAILURE, "action.document_missing"))
            }
            suspend fun confirm() {
                val target = confirmationTarget(bound,definition.index,call.arguments)
                coordinator.guard(scope.command) {}
                action(ForegroundActionKind.CONFIRM, AiJson.Obj(call.arguments.fields + ("operation" to aiString(call.name))),target)
                coordinator.guard(scope.command) {}
            }
            val a = call.arguments
            val value: Any? = when (definition.index) {
                1 -> bound.checkNumber(a.s("raw"))
                2 -> bound.home.load().requiredValue()
                3 -> bound.callers.search(CallerSearch(a.optS("text") ?: "", a.page())).requiredValue()
                4 -> bound.callers.getEditor(a.optS("number")).requiredValue()
                5 -> bound.callers.getOverview(OverviewQuery(a.s("number"), a.optL("workAtEpochMillis"))).requiredValue()
                6 -> bound.callers.getLatestCall().requiredValue()
                7 -> {
                    val current = bound.callers.getEditor(a.s("number")).requiredValue().draft
                    bound.callers.saveProfile(SaveCallerProfile(current.number, a.optS("name") ?: current.name, a.optS("department") ?: current.department,
                        if(a.has("positionId")) a.optL("positionId") else current.positionId, a.optB("whitelisted") ?: current.whitelisted)).requiredValue()
                }
                8 -> bound.callers.setWhitelisted(a.s("number"), a.b("enabled")).requiredValue()
                9 -> bound.reports.load(ReportQuery(a.s("number"), a.range(), a.optS("filter")?.let(CallFilter::valueOf) ?: CallFilter.ALL, a.page())).requiredValue()
                10 -> { val target = document(ForegroundActionKind.EXPORT_CSV); bound.reports.exportCsv(ReportExportQuery(a.s("number"), a.range()), target).requiredValue() }
                11 -> bound.settings.getSummary().requiredValue()
                12 -> bound.settings.setStatisticsEnabled(a.b("enabled")).requiredValue()
                13 -> bound.settings.setOverlayEnabled(a.b("enabled")).requiredValue()
                14 -> bound.settings.getQuietHours().requiredValue()
                15 -> {
                    val old = bound.settings.getQuietHours().requiredValue()
                    bound.settings.saveQuietHours(old.copy(mode = a.optS("mode")?.let(QuietMode::valueOf) ?: old.mode, allNumbers = a.optB("allNumbers") ?: old.allNumbers,
                        weeklyWindows = if(a.has("weeklyWindows")) a.weekly("weeklyWindows") else old.weeklyWindows)).requiredValue()
                }
                16 -> bound.settings.setQuietMode(QuietMode.valueOf(a.s("mode"))).requiredValue()
                17 -> bound.settings.setQuietEnabled(a.b("enabled")).requiredValue()
                18 -> bound.settings.getPositions().requiredValue()
                19 -> {
                    val id = a.optL("id")
                    val old = id?.let { key -> bound.settings.getPositions().requiredValue().options.singleOrNull { it.id == key } ?: throw BackendFailure(ApiError(ErrorCode.NOT_FOUND, "position.not_found")) }
                    bound.settings.savePosition(SavePosition(id, a.optS("name") ?: old?.name ?: a.s("name"), a.optL("level")?.exactInt() ?: old?.level ?: throw IllegalArgumentException(), a.optB("active") ?: old?.active ?: true)).requiredValue()
                }
                20 -> { confirm(); bound.settings.deletePosition(a.l("id")).requiredValue() }
                21 -> bound.settings.setMyPosition(a.optL("id")).requiredValue()
                22 -> bound.settings.getWorkSchedule().requiredValue()
                23 -> {
                    val old = bound.settings.getWorkSchedule().requiredValue()
                    bound.settings.saveWorkSchedule(SaveWorkSchedule(a.optS("zoneId") ?: old.zoneId, if(a.has("weekly")) a.weekly("weekly") else old.weekly, if(a.has("makeup")) a.windows("makeup") else old.makeup)).requiredValue()
                }
                24 -> bound.settings.resetWorkSchedule().requiredValue()
                25 -> bound.settings.getCalendar().requiredValue()
                26 -> bound.settings.synchronizeCalendar(a.l("year").exactInt()).requiredValue()
                27 -> bound.settings.importCalendar(document(ForegroundActionKind.IMPORT_CALENDAR)).requiredValue()
                28 -> bound.settings.saveDateOverride(DateOverride(LocalDate.parse(a.s("date")), DateOverrideKind.valueOf(a.s("kind")), if(a.has("windows")) a.windows("windows") else emptyList())).requiredValue()
                29 -> bound.settings.deleteDateOverride(LocalDate.parse(a.s("date"))).requiredValue()
                30 -> bound.settings.getLeaves().requiredValue()
                31 -> {
                    val old = a.optL("id")?.let { id -> bound.settings.getLeaves().requiredValue().singleOrNull { it.id == id } ?: throw BackendFailure(ApiError(ErrorCode.NOT_FOUND, "leave.not_found")) }
                    val period = if(a.has("period")) {
                        val p = a.obj("period")
                        if(p.s("kind") == "ALL_DAY") LeavePeriod.AllDay(LocalDate.parse(p.s("startDate")), LocalDate.parse(p.s("endDateInclusive")), p.s("zoneId"))
                        else LeavePeriod.Timed(p.l("startInclusiveEpochMillis"), p.l("endExclusiveEpochMillis"))
                    } else old?.let { LeavePeriod.Timed(it.startInclusiveEpochMillis, it.endExclusiveEpochMillis) } ?: throw IllegalArgumentException()
                    bound.settings.saveLeave(SaveLeave(old?.id, period, a.optS("note") ?: old?.note ?: "")).requiredValue()
                }
                32 -> { confirm(); bound.settings.deleteLeave(a.l("id")).requiredValue() }
                33 -> bound.settings.getScoreSettings().requiredValue()
                34 -> {
                    val old = bound.settings.getScoreSettings().requiredValue()
                    bound.settings.saveScoreSettings(ScoreSettings(a.optL("offHoursWeight")?.exactInt() ?: old.offHoursWeight, a.optL("repeatWeight")?.exactInt() ?: old.repeatWeight,
                        a.optL("repeatMinutes")?.exactInt() ?: old.repeatMinutes, a.optL("observationDays")?.exactInt() ?: old.observationDays)).requiredValue()
                }
                35 -> bound.settings.resetScoreSettings().requiredValue()
                36 -> bound.settings.getAppearance().requiredValue()
                37 -> {
                    val old = bound.settings.getAppearance().requiredValue()
                    bound.edits.appearance(old, AppearanceSettings(a.optS("selection")?.let(ThemeSelection::valueOf) ?: old.selection, a.optS("presetId") ?: old.presetId,
                        if(a.has("customSeedRgb")) a.optL("customSeedRgb")?.exactInt() else old.customSeedRgb, a.optS("colorMode")?.let(ColorMode::valueOf) ?: old.colorMode)).requiredValue()
                }
                38 -> bound.ringtone.getSettings().requiredValue()
                39 -> bound.ringtone.setEnabled(a.b("enabled")).requiredValue()
                40 -> bound.ringtone.importAudio(document(ForegroundActionKind.IMPORT_AUDIO)).requiredValue()
                41 -> bound.ringtone.restoreBuiltIn().requiredValue()
                42 -> { status = ToolStatus.LAUNCHED; bound.ringtone.startPreview().requiredValue() }
                43 -> { status = ToolStatus.LAUNCHED; bound.ringtone.stopPlayback().requiredValue() }
                44 -> bound.runtime.getStatus().requiredValue()
                45 -> bound.runtime.getDiagnostics().requiredValue()
                46 -> { status = ToolStatus.QUEUED; bound.runtime.requestCallLogSync().requiredValue() }
                47 -> bound.widgets.getBinding(a.l("widgetId").exactInt()).requiredValue()
                48 -> bound.widgets.saveBinding(a.l("widgetId").exactInt(), a.s("number")).requiredValue()
                49 -> bound.widgets.removeBinding(a.l("widgetId").exactInt()).requiredValue()
                50 -> bound.recordings.listArchives().requiredValue()
                51 -> { bound.recordings.getSettings().requiredValue(); mapOf("recording" to bound.aiSettings.getRecordingView().requiredValue(), "ai" to bound.aiSettings.getAiView().requiredValue()) }
                52 -> { val base = bound.aiSettings.getAiView().requiredValue(); bound.aiSettings.patchCombined(bound.aiSettings.getRecordingView().requiredValue(), a.optObj("recording")?.recordingPatch() ?: RecordingSettingsPatch(), base, a.optObj("ai")?.aiPatch(base.budget) ?: AiSettingsPatch()).requiredValue() }
                53 -> { confirm(); status = ToolStatus.READ; bound.aiSettings.testSlot(AiSlot.valueOf(a.s("slot"))).requiredValue() }
                54 -> bound.recordings.getArchive(a.l("callId")).requiredValue()
                55 -> {
                    val identity = bound.recordings.getIdentity(a.l("callId")).requiredValue()
                    val source = if(a.has("handle")) RecordingSource.SYSTEM else RecordingSource.IMPORTED
                    val target = if(a.has("handle")) resources.resolve(a.s("handle"), identity.callId, bound.recordings.systemRecordings().requiredValue()) else document(ForegroundActionKind.IMPORT_RECORDING)
                    bound.recordings.importRecording(identity.callId, identity.number, identity.startedAtEpochMillis, target, source).requiredValue()
                }
                56 -> { status = ToolStatus.QUEUED; bound.recordings.retry(a.l("callId")).requiredValue() }
                57 -> { confirm(); bound.recordings.delete(a.l("callId")).requiredValue() }
                58 -> bound.recordings.play(a.l("callId")).requiredValue()
                59 -> bound.recordings.stop().requiredValue()
                60 -> { val target = document(ForegroundActionKind.SELECT_FOLDER); bound.recordings.setSystemFolder(target).requiredValue(); bound.aiSettings.getRecordingView().requiredValue() }
                61 -> resources.project(bound.recordings.systemRecordings().requiredValue())
                62 -> bound.widgets.listBindings().requiredValue()
                63 -> { val archive = bound.recordings.getArchive(a.l("callId")).requiredValue() ?: throw BackendFailure(ApiError(ErrorCode.NOT_FOUND, "recording.not_found")); confirm(); bound.adoptIdentity(archive.callId, archive.generation).requiredValue() }
                64 -> bound.aiSettings.getAiView().requiredValue()
                65 -> { val base = bound.aiSettings.getAiView().requiredValue(); bound.aiSettings.patchAi(base, a.aiPatch(base.budget)).requiredValue() }
                66 -> { action(ForegroundActionKind.CREDENTIAL); api().aiSettings.getAiView().requiredValue() }
                67 -> { action(ForegroundActionKind.CAPABILITY); bound.runtime.getStatus().requiredValue() }
                68 -> { status = ToolStatus.LAUNCHED; action(ForegroundActionKind.APP_SETTINGS); bound.runtime.getStatus().requiredValue() }
                69 -> { status = ToolStatus.LAUNCHED; action(ForegroundActionKind.PIN_WIDGET); bound.widgets.listBindings().requiredValue() }
                70 -> { status = ToolStatus.LAUNCHED; action(ForegroundActionKind.TEST_OVERLAY); Unit }
                71 -> { val archiveId = a.l("callId"); status = ToolStatus.LAUNCHED; action(ForegroundActionKind.PREVIEW_CALL, aiObject("callId" to aiNumber(archiveId))); Unit }
                72 -> { action(ForegroundActionKind.COPY_CONNECTION); api().aiSettings.getAiView().requiredValue() }
                else -> error("ai.unknown_tool")
            }
            val after = coordinator.capture()
            return ToolReceipt(scope.command.operationId, call.id, status, safeValue(value), commitRevision = coordinator.committed(scope.command.operationId) ?: if(status == ToolStatus.COMMITTED) after else null, changedDomains = if(before != after) DataDomain.values().toSet() else emptySet())
        } catch(e: CancellationException) { throw e }
        catch(e: BackendFailure) { return failure(call, scope, e.error) }
        catch(_: IllegalArgumentException) { return failure(call, scope, ApiError(ErrorCode.VALIDATION, "ai.invalid_tool_arguments")) }
        catch(_: Exception) { return failure(call, scope, ApiError(ErrorCode.INTERNAL, "ai.tool_failed")) }
    }
    private fun failure(call: ConversationToolCall, scope: ToolExecutionContext, error: ApiError): ToolReceipt {
        val committed = coordinator.committed(scope.command.operationId)
        return ToolReceipt(scope.command.operationId,call.id, if(committed != null) ToolStatus.COMMITTED else when(error.message) { "action.cancelled" -> ToolStatus.CANCELLED; "action.denied" -> ToolStatus.DENIED; "action.unknown", "audio.launch_state_changed" -> ToolStatus.UNKNOWN; else -> ToolStatus.FAILED }, error = error, commitRevision = committed)
    }
}
internal fun AiJson.Obj.has(key: String) = fields.containsKey(key)
internal fun AiJson.Obj.s(key: String) = get(key).string()
internal fun AiJson.Obj.l(key: String) = get(key).long()
internal fun AiJson.Obj.b(key: String) = get(key).boolean()
internal fun AiJson.Obj.optS(key: String) = if(get(key) == AiJson.Null) null else s(key)
internal fun AiJson.Obj.optL(key: String) = if(get(key) == AiJson.Null) null else l(key)
internal fun AiJson.Obj.optB(key: String) = if(get(key) == AiJson.Null) null else b(key)
internal fun AiJson.Obj.obj(key: String) = get(key) as? AiJson.Obj ?: throw IllegalArgumentException()
internal fun AiJson.Obj.optObj(key: String) = get(key) as? AiJson.Obj
internal fun Long.exactInt(): Int { require(this in Int.MIN_VALUE..Int.MAX_VALUE); return toInt() }
private fun AiJson.Obj.page(): PageRequest = optObj("page")?.let { PageRequest(it.optL("pageIndex")?.exactInt() ?: 0, it.optL("pageSize")?.exactInt() ?: 50) } ?: PageRequest()
private fun AiJson.Obj.range(): DateRange = obj("range").let { DateRange(LocalDate.parse(it.s("startDate")), LocalDate.parse(it.s("endDateInclusive")), it.optS("zoneId")) }
private fun AiJson.Obj.windows(key: String): List<TimeWindow> = (get(key) as AiJson.Arr).values.map { val item = it as AiJson.Obj; TimeWindow(item.l("startMinute").exactInt(), item.l("endMinute").exactInt()) }
private fun AiJson.Obj.weekly(key: String): Map<Int, List<TimeWindow>> = obj(key).let { values -> values.fields.keys.associate { it.toInt() to values.windows(it) } }
private fun <T> AiJson.Obj.patch(key: String, read: (AiJson) -> T): PatchField<T> = if(has(key)) PatchField.Set(read(get(key))) else PatchField.Keep
private fun AiJson.Obj.connectionPatch() = ConnectionPatch(patch("protocol") { AiProtocol.valueOf(it.string()) }, patch("url") { it.string() }, patch("model") { it.string() })
private fun AiJson.Obj.aiPatch(base: ConversationBudget): AiSettingsPatch = AiSettingsPatch(optObj("analysis")?.connectionPatch() ?: ConnectionPatch(), optObj("transcription")?.connectionPatch() ?: ConnectionPatch(),
    if(has("context") || has("output")) PatchField.Set(ConversationBudget(optL("context") ?: base.context, optL("output") ?: base.output)) else PatchField.Keep)
private fun AiJson.Obj.recordingPatch() = RecordingSettingsPatch(patch("recordingEnabled") { it.boolean() }, patch("automaticAnalysis") { it.boolean() }, patch("captureMode") { CaptureMode.valueOf(it.string()) })
