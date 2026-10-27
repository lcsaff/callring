package com.greatwall.callring.application.conversation.execution

import com.greatwall.callring.application.conversation.foreground.ForegroundActionService
import com.greatwall.callring.application.conversation.foreground.ForegroundCommandValidator
import com.greatwall.callring.application.conversation.foreground.ForegroundResourceRegistry
import com.greatwall.callring.application.conversation.operation.CapabilityCatalog
import com.greatwall.callring.application.conversation.operation.ToolRegistry
import com.greatwall.callring.application.conversation.query.QueryService
import com.greatwall.callring.application.conversation.query.ScopedToolReader
import com.greatwall.callring.application.conversation.query.json
import com.greatwall.callring.application.conversation.query.safeValue
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.requiredValue

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.application.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.application.ports.BackendFailure
import java.time.LocalDate
import com.greatwall.callring.contracts.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

data class ToolExecutionContext(val sessionId: String, val turnId: String, val generation: Long, val command: CommandContext)
class ToolExecutor(
    private val api: () -> CallringUiApi,
    private val coordinator: BusinessRevisionCoordinator,
    private val foreground: ForegroundActionService,
    private val resources: ForegroundResourceRegistry,
    val registry: ToolRegistry = ToolRegistry(),
    private val queries:QueryService,
    private val numberMatcher:com.greatwall.callring.domain.calling.NumberMatcher,
    private val documentWriteVerified:suspend(DocumentRef)->Boolean,
) {
    val foregroundReady:Boolean get()=foreground.hasHost
    suspend fun execute(call: ConversationToolCall, scope: ToolExecutionContext): ToolReceipt {
        val before = coordinator.capture()
        try {
            val definition = registry.validate(call)
            if(definition.id==OperationId.DISCOVER_TOOLS)return ToolReceipt(scope.command.operationId,call.id,ToolStatus.READ,CapabilityCatalog(registry).discover(call.arguments.optS("category"),(call.arguments["exactNames"] as? AiJson.Arr)?.values?.map{it.string()}?:emptyList(),call.arguments.optS("query"),call.arguments.optS("cursor"),(call.arguments.optL("limit")?:8).exactInt()).json())
            if(definition.execution==ExecutionStrategy.SOURCE) {
                val result=ScopedToolReader(queries,numberMatcher,registry.catalog).readWithEvidence(definition.id,call.arguments)
                return ToolReceipt(scope.command.operationId,call.id,ToolStatus.READ,result.value,verificationEvidence=result.evidence)
            }
            if(definition.execution==ExecutionStrategy.PLATFORM_READ) {
                val update=api().update
                val value=when(definition.id) {
                    OperationId.UPDATE_STATUS -> update.status().requiredValue()
                    OperationId.UPDATE_CHECK -> update.check().requiredValue()
                    else -> error("ai.unknown_tool")
                }
                return ToolReceipt(scope.command.operationId,call.id,ToolStatus.READ,safeValue(value))
            }
            if (definition.write) {
                require(foreground.hasHost) { "action.foreground_required" }
                coordinator.guard(scope.command) {}
            }
            val bound = api().commandApi(scope.command)
            var status = if(definition.write) ToolStatus.COMMITTED else ToolStatus.READ
            var noOp=false
            val actionEvidence=mutableListOf<AiJson>()
            suspend fun action(kind: ForegroundActionKind, parameters: AiJson.Obj = call.arguments, confirmation: ConfirmationTarget? = null): ForegroundHostResult {
                val result = foreground.request(scope.sessionId, scope.turnId, call.id, scope.generation, kind, parameters, scope.command, confirmation, scope.command.active)
                result.error?.let { throw BackendFailure(it) }
                actionEvidence+=aiObject("foregroundAction" to aiString(kind.name),"result" to aiString(result.status.name))
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
                val target = confirmationTarget(bound,definition.id,call.arguments)
                coordinator.guard(scope.command) {}
                action(ForegroundActionKind.CONFIRM, AiJson.Obj(call.arguments.fields + ("operation" to aiString(call.name))),target)
                coordinator.guard(scope.command) {}
            }
            val a = call.arguments
            val value: Any? = when (definition.id) {
                OperationId.SETTINGS_SAVE_NUMBER_RULE -> {
                    val base=a.obj("base").let { NumberRuleSettings(it.s("prefix"),it.l("length").exactInt(),it.l("revision")) }
                    val saved=bound.settings.saveNumberRule(SaveNumberRule(base,a.s("prefix"),a.l("length").exactInt())).requiredValue()
                    noOp=saved==base
                    saved
                }
                OperationId.CALLERS_SAVE_PROFILE -> {
                    val current = bound.callers.getEditor(a.s("number")).requiredValue().draft
                    val draft=current.copy(name=a.optS("name")?:current.name,department=a.optS("department")?:current.department,positionId=if(a.has("positionId"))a.optL("positionId")else current.positionId,whitelisted=a.optB("whitelisted")?:current.whitelisted)
                    bound.edits.profile(current,draft).requiredValue()
                }
                OperationId.CALLERS_SET_WHITELISTED -> bound.callers.setWhitelisted(a.s("number"), a.b("enabled")).requiredValue()
                OperationId.REPORTS_EXPORT_CSV -> { val target = document(ForegroundActionKind.EXPORT_CSV); val exported=bound.reports.exportCsv(ReportExportQuery(a.s("number"), a.range()), target).requiredValue();if(documentWriteVerified(target))actionEvidence+=aiObject("documentReadback" to aiBool(true));exported }
                OperationId.SETTINGS_SET_STATISTICS_ENABLED -> bound.settings.setStatisticsEnabled(a.b("enabled")).requiredValue()
                OperationId.SETTINGS_SET_OVERLAY_ENABLED -> bound.settings.setOverlayEnabled(a.b("enabled")).requiredValue()
                OperationId.SETTINGS_SAVE_QUIET_HOURS -> {
                    val old = bound.settings.getQuietHours().requiredValue()
                    bound.settings.saveQuietHours(old.copy(mode = a.optS("mode")?.let(QuietMode::valueOf) ?: old.mode, allNumbers = a.optB("allNumbers") ?: old.allNumbers,
                        weeklyWindows = if(a.has("weeklyWindows")) a.weekly("weeklyWindows") else old.weeklyWindows)).requiredValue()
                }
                OperationId.SETTINGS_SET_QUIET_MODE -> bound.settings.setQuietMode(QuietMode.valueOf(a.s("mode"))).requiredValue()
                OperationId.SETTINGS_SET_QUIET_ENABLED -> bound.settings.setQuietEnabled(a.b("enabled")).requiredValue()
                OperationId.SETTINGS_SAVE_POSITION -> {
                    val id = a.optL("id")
                    val old = id?.let { key -> bound.settings.getPositions().requiredValue().options.singleOrNull { it.id == key } ?: throw BackendFailure(ApiError(ErrorCode.NOT_FOUND, "position.not_found")) }
                    bound.settings.savePosition(SavePosition(id, a.optS("name") ?: old?.name ?: a.s("name"), a.optL("level")?.exactInt() ?: old?.level ?: throw IllegalArgumentException(), a.optB("active") ?: old?.active ?: true)).requiredValue()
                }
                OperationId.SETTINGS_DELETE_POSITION -> { confirm(); bound.settings.deletePosition(a.l("id")).requiredValue() }
                OperationId.SETTINGS_SET_MY_POSITION -> bound.settings.setMyPosition(a.optL("id")).requiredValue()
                OperationId.SETTINGS_SAVE_WORK_SCHEDULE -> {
                    val old = bound.settings.getWorkSchedule().requiredValue()
                    bound.settings.saveWorkSchedule(SaveWorkSchedule(a.optS("zoneId") ?: old.zoneId, if(a.has("weekly")) a.weekly("weekly") else old.weekly, if(a.has("makeup")) a.windows("makeup") else old.makeup)).requiredValue()
                }
                OperationId.SETTINGS_RESET_WORK_SCHEDULE -> bound.settings.resetWorkSchedule().requiredValue()
                OperationId.SETTINGS_SYNCHRONIZE_CALENDAR -> bound.settings.synchronizeCalendar(a.l("year").exactInt()).requiredValue()
                OperationId.SETTINGS_IMPORT_CALENDAR -> bound.settings.importCalendar(document(ForegroundActionKind.IMPORT_CALENDAR)).requiredValue()
                OperationId.SETTINGS_SAVE_DATE_OVERRIDE -> bound.settings.saveDateOverride(DateOverride(LocalDate.parse(a.s("date")), DateOverrideKind.valueOf(a.s("kind")), if(a.has("windows")) a.windows("windows") else emptyList())).requiredValue()
                OperationId.SETTINGS_DELETE_DATE_OVERRIDE -> bound.settings.deleteDateOverride(LocalDate.parse(a.s("date"))).requiredValue()
                OperationId.SETTINGS_SAVE_LEAVE -> {
                    val old = a.optL("id")?.let { id -> bound.settings.getLeaves().requiredValue().singleOrNull { it.id == id } ?: throw BackendFailure(ApiError(ErrorCode.NOT_FOUND, "leave.not_found")) }
                    val period = if(a.has("period")) {
                        val p = a.obj("period")
                        if(p.s("kind") == "ALL_DAY") LeavePeriod.AllDay(LocalDate.parse(p.s("startDate")), LocalDate.parse(p.s("endDateInclusive")), p.s("zoneId"))
                        else LeavePeriod.Timed(p.l("startInclusiveEpochMillis"), p.l("endExclusiveEpochMillis"))
                    } else old?.let { LeavePeriod.Timed(it.startInclusiveEpochMillis, it.endExclusiveEpochMillis) } ?: throw IllegalArgumentException()
                    bound.settings.saveLeave(SaveLeave(old?.id, period, a.optS("note") ?: old?.note ?: "")).requiredValue()
                }
                OperationId.SETTINGS_DELETE_LEAVE -> { confirm(); bound.settings.deleteLeave(a.l("id")).requiredValue() }
                OperationId.SETTINGS_SAVE_SCORE_SETTINGS -> {
                    val old = bound.settings.getScoreSettings().requiredValue()
                    bound.settings.saveScoreSettings(ScoreSettings(a.optL("offHoursWeight")?.exactInt() ?: old.offHoursWeight, a.optL("repeatWeight")?.exactInt() ?: old.repeatWeight,
                        a.optL("repeatMinutes")?.exactInt() ?: old.repeatMinutes, a.optL("observationDays")?.exactInt() ?: old.observationDays)).requiredValue()
                }
                OperationId.SETTINGS_RESET_SCORE_SETTINGS -> bound.settings.resetScoreSettings().requiredValue()
                OperationId.SETTINGS_SAVE_APPEARANCE -> {
                    val old = bound.settings.getAppearance().requiredValue()
                    val updated=AppearanceSettings(a.optS("selection")?.let(ThemeSelection::valueOf) ?: old.selection,a.optS("presetId") ?: old.presetId,if(a.has("customSeedRgb")) a.optL("customSeedRgb")?.exactInt() else old.customSeedRgb,a.optS("colorMode")?.let(ColorMode::valueOf) ?: old.colorMode)
                    noOp=old==updated
                    if(noOp){coordinator.guard(scope.command){};return ToolReceipt(scope.command.operationId,call.id,ToolStatus.COMMITTED,safeValue(old),noOp=true)}
                    bound.edits.appearance(old,updated).requiredValue()
                }
                OperationId.RINGTONE_SET_ENABLED -> bound.ringtone.setEnabled(a.b("enabled")).requiredValue()
                OperationId.RINGTONE_IMPORT_AUDIO -> bound.ringtone.importAudio(document(ForegroundActionKind.IMPORT_AUDIO)).requiredValue()
                OperationId.RINGTONE_RESTORE_BUILT_IN -> bound.ringtone.restoreBuiltIn().requiredValue()
                OperationId.RINGTONE_START_PREVIEW -> { status = ToolStatus.LAUNCHED; bound.ringtone.startPreview().requiredValue() }
                OperationId.RINGTONE_STOP_PLAYBACK -> { status = ToolStatus.LAUNCHED; bound.ringtone.stopPlayback().requiredValue() }
                OperationId.RUNTIME_REQUEST_CALL_LOG_SYNC -> { status = ToolStatus.QUEUED; bound.runtime.requestCallLogSync().requiredValue() }
                OperationId.WIDGETS_SAVE_BINDING -> bound.widgets.saveBinding(a.l("widgetId").exactInt(), a.s("number")).requiredValue()
                OperationId.WIDGETS_REMOVE_BINDING -> bound.widgets.removeBinding(a.l("widgetId").exactInt()).requiredValue()
                OperationId.RECORDINGS_SAVE_SETTINGS -> { val base = bound.aiSettings.getAiView().requiredValue(); bound.aiSettings.patchCombined(bound.aiSettings.getRecordingView().requiredValue(), a.optObj("recording")?.recordingPatch() ?: RecordingSettingsPatch(), base, a.optObj("ai")?.aiPatch(base.budget) ?: AiSettingsPatch()).requiredValue() }
                OperationId.RECORDINGS_TEST_CONNECTION -> { confirm(); status = ToolStatus.READ; bound.aiSettings.testSlot(AiSlot.valueOf(a.s("slot"))).requiredValue() }
                OperationId.RECORDINGS_IMPORT_RECORDING -> {
                    val identity = bound.recordings.getIdentity(a.l("callId")).requiredValue()
                    val source = if(a.has("handle")) RecordingSource.SYSTEM else RecordingSource.IMPORTED
                    val target = if(a.has("handle")) resources.resolveScoped(a.s("handle"),identity.callId){bound.recordings.inspectSystemRecording(it).requiredValue()} else document(ForegroundActionKind.IMPORT_RECORDING)
                    bound.recordings.importRecording(identity.callId, identity.number, identity.startedAtEpochMillis, target, source).requiredValue()
                }
                OperationId.RECORDINGS_RETRY -> { status = ToolStatus.QUEUED; bound.recordings.retry(a.l("callId")).requiredValue() }
                OperationId.RECORDINGS_DELETE -> { confirm(); bound.recordings.delete(a.l("callId")).requiredValue() }
                OperationId.RECORDINGS_PLAY -> bound.recordings.play(a.l("callId")).requiredValue()
                OperationId.RECORDINGS_STOP -> bound.recordings.stop().requiredValue()
                OperationId.RECORDINGS_SET_SYSTEM_FOLDER -> { val target = document(ForegroundActionKind.SELECT_FOLDER); bound.recordings.setSystemFolder(target).requiredValue(); bound.aiSettings.getRecordingView().requiredValue() }
                OperationId.RECORDINGS_ADOPT_IDENTITY -> { val archive = bound.recordings.getArchive(a.l("callId")).requiredValue() ?: throw BackendFailure(ApiError(ErrorCode.NOT_FOUND, "recording.not_found")); confirm(); bound.adoptIdentity(archive.callId, archive.generation).requiredValue() }
                OperationId.AI_PATCH_SETTINGS -> { val base = bound.aiSettings.getAiView().requiredValue(); bound.aiSettings.patchAi(base, a.aiPatch(base.budget)).requiredValue() }
                OperationId.AI_EDIT_CREDENTIAL -> { action(ForegroundActionKind.CREDENTIAL); api().aiSettings.getAiView().requiredValue() }
                OperationId.SYSTEM_REQUEST_CAPABILITY -> {
                    val result = action(ForegroundActionKind.CAPABILITY)
                    val capability = Capability.valueOf(a.s("capability"))
                    if (result.status != ForegroundResultStatus.VERIFIED) throw BackendFailure(ApiError(ErrorCode.PERMISSION_REQUIRED, "action.denied"))
                    val key = "runtime:capability:$capability"
                    var proof: RuntimeStatus? = null
                    for (attempt in 0 until 3) {
                        val observedAt = coordinator.marker()
                        val observed = bound.runtime.getStatus().requiredValue()
                        if (!coordinator.unchanged(setOf(key), observedAt)) continue
                        if (observed.capabilities[capability] !in setOf(AccessState.GRANTED, AccessState.NOT_REQUIRED))
                            throw BackendFailure(ApiError(ErrorCode.PERMISSION_REQUIRED, "action.denied"))
                        ForegroundCommandValidator(coordinator).recordCapability(scope.command, capability, observedAt)
                        proof = observed
                        break
                    }
                    proof ?: throw BackendFailure(ApiError(ErrorCode.CONFLICT, "business.changed", retryable = true))
                }
                OperationId.SYSTEM_OPEN_APPLICATION_SETTINGS -> { status = ToolStatus.LAUNCHED; action(ForegroundActionKind.APP_SETTINGS); bound.runtime.getStatus().requiredValue() }
                OperationId.SYSTEM_PIN_WIDGET -> { status = ToolStatus.LAUNCHED; action(ForegroundActionKind.PIN_WIDGET); bound.widgets.listBindings().requiredValue() }
                OperationId.SYSTEM_TEST_OVERLAY -> { status = ToolStatus.LAUNCHED; action(ForegroundActionKind.TEST_OVERLAY); Unit }
                OperationId.SYSTEM_PREVIEW_CALL -> { val archiveId = a.l("callId"); status = ToolStatus.LAUNCHED; action(ForegroundActionKind.PREVIEW_CALL, aiObject("callId" to aiNumber(archiveId))); Unit }
                OperationId.AI_COPY_CONNECTION -> { action(ForegroundActionKind.COPY_CONNECTION); api().aiSettings.getAiView().requiredValue() }
                OperationId.CALLERS_DELETE_NUMBER -> {
                    val impact = bound.numberDeletion.impact(a.s("number")).requiredValue()
                    confirm()
                    bound.numberDeletion.delete(
                        DeleteNumberCommand(impact.number, impact.callCount, impact.archiveCount, impact.widgetBindingCount),
                    ).requiredValue()
                    Unit
                }
                else -> error("ai.unknown_tool")
            }
            val after = coordinator.capture()
            return ToolReceipt(scope.command.operationId, call.id, status, safeValue(value), commitRevision = coordinator.committed(scope.command.operationId), changedDomains = if(before != after) DataDomain.values().toSet() else emptySet(),noOp=noOp,verificationEvidence=AiJson.Arr(actionEvidence))
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
private fun AiJson.Obj.range(): DateRange = obj("range").let { DateRange(LocalDate.parse(it.s("startDate")), LocalDate.parse(it.s("endDateInclusive")), it.optS("zoneId")) }
private fun AiJson.Obj.windows(key: String): List<TimeWindow> = (get(key) as AiJson.Arr).values.map { val item = it as AiJson.Obj; TimeWindow(item.l("startMinute").exactInt(), item.l("endMinute").exactInt()) }
private fun AiJson.Obj.weekly(key: String): Map<Int, List<TimeWindow>> = obj(key).let { values -> values.fields.keys.associate { it.toInt() to values.windows(it) } }
private fun <T> AiJson.Obj.patch(key: String, read: (AiJson) -> T): PatchField<T> = if(has(key)) PatchField.Set(read(get(key))) else PatchField.Keep
private fun AiJson.Obj.connectionPatch() = ConnectionPatch(patch("protocol") { AiProtocol.valueOf(it.string()) }, patch("url") { it.string() }, patch("model") { it.string() }, patch("thinkingMode") { ThinkingMode.valueOf(it.string()) })
private fun AiJson.Obj.aiPatch(base: ConversationBudget): AiSettingsPatch = AiSettingsPatch(optObj("analysis")?.connectionPatch() ?: ConnectionPatch(), optObj("transcription")?.connectionPatch() ?: ConnectionPatch(),
    if(has("context") || has("output")) PatchField.Set(ConversationBudget(optL("context") ?: base.context, optL("output") ?: base.output)) else PatchField.Keep,patch("compatibility"){ConversationCompatibility.valueOf(it.string())})
private fun AiJson.Obj.recordingPatch() = RecordingSettingsPatch(patch("recordingEnabled") { it.boolean() }, patch("automaticAnalysis") { it.boolean() }, patch("captureMode") { CaptureMode.valueOf(it.string()) })
