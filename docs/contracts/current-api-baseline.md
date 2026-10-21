# Current API and compatibility baseline

Baseline source commit: `af9e485307183e4ce7b9b0dbae3b6c08333de082` (2026-10-20, before layer-boundary migration). The declaration appendix below is a frozen source inventory of **all twelve current application/api files**, including methods, parameter/DTO defaults, fallback implementations, error/status enums and pure helpers. It is evidence, not a second compiled contract. The live Kotlin namespace is currently `com.greatwall.callring.application.api`.

## Compatibility boundary

The external AI tool names, O/X codes, argument fields/types/enums, required-vs-optional-vs-null semantics, discovery order and runtime omission behavior must remain compatible. `ai-operation-compatibility.json` captures all **81** discoverable definitions: O01–O72 (legacy indices 1–72) and X01–X09 (indices 73–81). Never infer code from a newly reordered list. The current T12 entry point is `ToolRegistry.definitions`, containing all 81 records from the single validated `OperationCatalog`; the old 72/81 dual API is removed. Explicit `OperationId` selects behavior. CapabilityCatalog filters in explicit displayOrder order and pages with catalog version `2`, `catalog2:<offset>` cursors, limit 8/default and 12/max. Initial provider-enabled tool subsets are separate from this discoverable inventory.

The golden records full actual registry JSON schemas. These schemas contain **no JSON Schema default keywords**: runtime omission behavior comes from ToolExecutor, ScopedToolReader, ToolBatchEngine and CapabilityCatalog. Missing optional patch fields normally preserve stored values, not a constant default. Explicit null is allowed only where the schema says so (for example positionId/customSeedRgb). Legacy tool schemas automatically add optional dependsOn, and write schemas add optional taskId; extensions do not automatically inherit those fields. This distinction must survive migration.

Persisted SQLite table/column meanings, JSON field names/versions, enum names, IDs, credentials, archive references and upgrade/default behavior remain compatible. Existing installation data must continue loading with no destructive reset. Source-query JSON and receipt/status semantics also remain compatible; a queued/launched receipt does not prove completion and a committed-but-unverified command must not be replayed merely because readback failed.

The current Kotlin interfaces are **internal source contracts allowed to change** during this repository-wide migration. Moving them to contracts, narrowing interfaces, replacing internal ports, splitting DTOs or removing backend dependencies is authorized when all callers/adapters/tests are migrated atomically and external/persistence behavior is preserved. The appendix is not a requirement to perpetuate unsafe source signatures or package names.

In particular, `RecordingApi.getSettings/saveSettings/testConnection/setSystemFolder` currently traffic in credential-bearing RecordingSettings/AiConnection. Those legacy internal methods may become internal ports; UI-facing contracts should use safe views, patch commands and local secret editing. O51/O52/O53/O60/O64/O65/O66/O72 behavior and stored configuration must remain compatible even when these Kotlin methods move. Do not expose raw credentials through safe AI views, discovery, receipts, logs or saved UI state.

`ui/.../feature/ai/CredentialDialog.kt` currently calls `resolveConversationProfile(AiConnection(...), AUTO)` when previewing a copy. This is business/provider resolution leaking into UI despite the temporary shared API placement. Resolution may move behind a safe contract query/preview DTO; preserve displayed copy consequences: compatibility reset to AUTO and old conversation invalidation. `ThinkingContract` is explicitly internal/derived, not persisted or displayed. Sharing enum names alone does not justify keeping resolver policy in UI.

## Public API method inventory guide

The exact signatures and defaults are reproduced in the appendix. Major surfaces are:

| Surface | Methods / properties |
| --- | --- |
| CallringUiApi | edits, conversation, aiSettings, numberDeletion, update, snapshots, recordings, home, callers, reports, settings, ringtone, runtime, widgets, changes; commandApi, adoptIdentity, checkNumber |
| HomeApi | load |
| CallersApi | getCallDetail, search, getEditor, getOverview, getLatestCall, saveProfile, setWhitelisted, numberDataImpact, deleteNumberCore |
| ReportsApi | load, exportCsv |
| SettingsApi | getSummary; setStatisticsEnabled/setOverlayEnabled; getQuietHours/saveQuietHours/setQuietMode/setQuietEnabled; getPositions/savePosition/deletePosition/setMyPosition; getWorkSchedule/saveWorkSchedule/resetWorkSchedule; getCalendar/synchronizeCalendar/importCalendar/saveDateOverride/deleteDateOverride; getLeaves/saveLeave/deleteLeave; getScoreSettings/saveScoreSettings/resetScoreSettings; getAppearance/saveAppearance |
| RingtoneApi / RuntimeApi | getSettings/setEnabled/importAudio/restoreBuiltIn/startPreview/stopPlayback; getStatus/getDiagnostics/requestCallLogSync |
| WidgetBindingsApi / ChangeFeed | listBindings/getBinding/saveBinding/removeBinding; observe |
| UiEditsApi | profile, quiet, work, score, appearance, position, leave (baseline/value conflict-aware commands) |
| RecordingApi | getIdentity/listArchives/getSettings/saveSettings/testConnection/getArchive/importRecording/retry/delete/play/stop/setSystemFolder/systemRecordings/inspectSystemRecording |
| AiSettingsApi | patchCombined/getRecordingView/patchRecording/getAiView/patchAi/copyConnection/testSlot/testDraft |
| ConversationApi / ForegroundActionApi | state/foreground/send/sendTo/continueTask/stop/newSession; pending/attachHost/detachHost/beginLaunch/deliver |
| AiSnapshotApi / AiQueryApi | captureComplete; query/get/aggregate/readText/reset |
| NumberDeletionApi / AppUpdateApi | Exact methods and command/receipt defaults in corresponding appendix sections |

Notable defaults: PageRequest(0,50), CallerSearch empty text, DateRange zoneId null (schedule zone), CallFilter.ALL, recording import source IMPORTED; optional facade extensions normally fall back to safe SYSTEM_UNAVAILABLE rather than silently succeed. UiEditsApi defaults fail via aiUnavailable. Recording getIdentity derives from archive or NOT_FOUND. Secret/patch defaults are Keep, which differs from Set(null)/Clear. ConversationBudget defaults context=49152/output=4096; bounds are context 8192..1048576, output 1024..16384 and output<context. Query defaults are projection `default`, empty filter, null cursor, limit50/maxBytes32768; readText maxChars4096 code points. See source appendix for every remaining constructor/default and fallback.

## D-domain inventory

These are stable external source domains, not the UI DataDomain event enum. Evidence: `CoreSnapshotProjection.kt`, `bootstrap/CompleteSnapshotAssembly.kt`, `bootstrap/ScopedQueryAssembly.kt`, `platform/ai/RecordingScopedReadAdapter.kt`, `data/SqliteScopedReadAdapter.kt`, and provider prompt in `CompatibleConversationClient.kt`.

| Code | Stored/source meaning |
| --- | --- |
| D01 | Caller profiles; source inventory can include numbers present only in stored calls |
| D02 | Positions and my position |
| D03 | All stored call details, including hidden/unscored |
| D04 | Visible call details; projection can select counted |
| D05 | Authoritative statistics, intervals, totals and scores (not current UI page sums) |
| D06 | Current/history work schedules and evaluated work status |
| D07 | Calendar years and day data |
| D08 | Date overrides |
| D09 | Leave entries and version |
| D10 | Statistics/quiet/score/overlay settings and current position setting in scoped projection |
| D11 | Appearance fields plus read-only `presets`: `id`, `name`, `seedRgb`; shared built-in catalog for UI and AI |
| D12 | Ringtone settings/playback |
| D13 | Recording safe settings/folder access |
| D14 | AI safe settings, keyConfigured only |
| D15 | Recording events, links and deletions |
| D16 | Archive metadata and long-text references |
| D17 | Authorized directory handles, not arbitrary paths |
| D18 | Widget bindings and installed widget/access state |
| D19 | Runtime, capabilities, recording playback, processing/jobs where source supports them |
| D20 | Stored diagnostics |
| D21 | Audit records |

Full snapshot schemaVersion=1 requires all 21 domains, nonnegative itemCount and explicit COMPLETE/EMPTY/NOT_CONFIGURED/UNAVAILABLE states; capture retries changed revisions and redacts secrets. Scoped page schemaVersion=2 adds matchedTotal/returnedCount/nextCursor/complete/status/missingReason and SourceStamp. QueryStatus distinguishes COMPLETE, EMPTY, PARTIAL, NOT_CONFIGURED, UNAVAILABLE, DENIED. Follow nextCursor; complete=false is not the full result. Scope and consistency distinguish all-stored, authorized-tree observation and live probes. Preserve exact Long identity and Unicode text code-point boundaries.

## Error and notification behavior

Api ErrorCode values: VALIDATION, NOT_FOUND, IN_USE, PERMISSION_REQUIRED, ROLE_REQUIRED, CONFLICT, SYSTEM_RESTRICTED, SYSTEM_UNAVAILABLE, IO_FAILURE, STORAGE_FAILURE, INTERNAL. ApiError also carries message, fieldErrors, requiredCapabilities, retryable and optional ConversationFailure. Message keys/field paths are surfaced to UI/model repair, so migration must retain their meaning. The appendix includes every AiFailureKind, ToolStatus, VerificationStatus, ForegroundResultStatus and availability fallback. Registry argument validation includes strict additionalProperties, enum checks, bounded arrays, required fields, call-ID constraints and safe argument field paths; it must not echo invalid secret input.

Current DataDomain has 16 domains: CALLERS, POSITIONS, CALLS, REPORTS, WORK_CALENDAR, QUIET_HOURS, SCORE_SETTINGS, STATISTICS, OVERLAY_SETTINGS, RINGTONE, APPEARANCE, WIDGET_BINDINGS, RUNTIME, RECORDING_SETTINGS, AI_SETTINGS, RECORDING_ARCHIVES. DataChange carries dataRevision, domains, nullable numbers/widgetIds. Null keys mean global/unspecified; empty key sets are distinct. Future cumulative invalidation is an internal interface replacement, but must preserve relevant invalidations under coalescing and number/widget isolation. Current ToolExecutor receipt changedDomains can be all domains whenever any revision changed; this broad behavior must not hide the under-subscriptions documented in `../plans/layer-boundaries-evidence/subscription-matrix.md`.

## Persistence distinctions to retain

`data/WorkConfigCodec.kt` explicitly preserves schema versions 1–3. Work schedule snapshots use `zone`, `weekly`, `makeup`; never serialize a new DTO blindly over an existing codec. Calls retain current and original classification, config/calendar/leave versions, hidden/link fields and source IDs. D03 differs from D04; UI-only visibility/date pagination must not truncate source inventory or authoritative aggregates.

`platform/ai/AndroidRecordingStore.kt` uses archive payloads plus generation, deleted_archives, recording_events and recording_statistics_links. Connection JSON uses protocol/url/**key**/model/thinkingMode; missing thinkingMode defaults DISABLED. Recording conversation JSON version=1 stores context/output/compatibility; absent conversation object uses ConversationBudget defaults. Legacy QWEN_FAST remains accepted on read but normalizes to AUTO on read/write/patch (ConversationCompatibility.normalized); ProviderProfile resolution remains derived. Capture source, analysis protocol/model, transcription model and generation semantics are independent of safe UI views.

Quiet last-mode memory and active OFF mode differ; quiet enable/disable must preserve preferred mode semantics. Widget controls additionally persist `callring_widget_controls/last_quiet_mode`. The current widget displays global quiet/leave controls and has no per-number binding read; legacy bindings still exist as stored/API data. Credentials stay within the local secret/storage path and are not saved in SavedStateHandle. Baseline/value/dirtiness in edit commands guard concurrent changes; refreshing a snapshot must not silently replace the values that an edit began against.

## Verification scope

ToolCompatibilityBaselineTest compares actual `ToolRegistry.definitions` to the checked-in generated golden, including all schemas and discovery order. Initial creation requires explicit `CALLRING_BOOTSTRAP_TOOL_BASELINE=1` and refuses to overwrite an existing golden. Runtime default text and extension receipt classifications are source-reviewed inventory; the golden test is **not** execution coverage of each default, confirmation or receipt flow. Frozen unavailable fallback bodies are current behavior evidence, not endorsement of an unavailable implementation as the intended business behavior. Existing ToolRegistryTest, ConversationApiContractTest, ThinkingToolApiCompatibilityTest and persistence/codec tests provide complementary behavior coverage. RefreshIsolationTest implements three UI-loader isolation/characterization cases; the subscription matrix separately records broader deferred screen-DTO and cumulative-feed acceptance cases.

## Frozen declaration appendix

Review evidence: the parent-run S0 targeted Gradle run passed all three RefreshIsolationTest cases on 2026-10-20. Spec review subsequently corrected O10 export omission metadata: absent range.zoneId resolves to the current schedule zone. That metadata correction is separate from the earlier test-run evidence.

The following sections reproduce the baseline files verbatim. They preserve exact parameter names, defaults and fallback bodies without pretending the current package/API dependency graph is permanent.

### AiJson.kt

```kotlin
package com.greatwall.callring.application.api

/** A deliberately small JSON tree. Numbers preserve decimal Long identities. */
sealed class AiJson {
    data class Obj(val fields: Map<String, AiJson>) : AiJson() {
        operator fun get(key: String): AiJson = fields[key] ?: Null
    }
    data class Arr(val values: List<AiJson>) : AiJson()
    data class Str(val value: String) : AiJson()
    data class Num(val value: String) : AiJson()
    data class Bool(val value: Boolean) : AiJson()
    data object Null : AiJson()
    fun encode(): String = when (this) {
        is Obj -> fields.entries.joinToString(",", "{", "}") { quote(it.key) + ":" + it.value.encode() }
        is Arr -> values.joinToString(",", "[", "]") { it.encode() }
        is Str -> quote(value)
        is Num -> value
        is Bool -> value.toString()
        Null -> "null"
    }
    companion object {
        fun quote(value: String): String = buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            } }
            append('"')
        }
    }
}

fun aiObject(vararg values: Pair<String, AiJson>) = AiJson.Obj(linkedMapOf(*values))
fun aiString(value: String?) = value?.let(AiJson::Str) ?: AiJson.Null
fun aiNumber(value: Number?) = value?.let { AiJson.Num(it.toString()) } ?: AiJson.Null
fun aiBool(value: Boolean) = AiJson.Bool(value)

fun AiJson.string(): String = (this as? AiJson.Str)?.value ?: throw IllegalArgumentException("ai.argument_string")
fun AiJson.long(): Long = (this as? AiJson.Num)?.value?.toLongOrNull() ?: throw IllegalArgumentException("ai.argument_integer")
fun AiJson.boolean(): Boolean = (this as? AiJson.Bool)?.value ?: throw IllegalArgumentException("ai.argument_boolean")

```

### AiQueryApi.kt

```kotlin
package com.greatwall.callring.application.api

enum class AiDomain { D01,D02,D03,D04,D05,D06,D07,D08,D09,D10,D11,D12,D13,D14,D15,D16,D17,D18,D19,D20,D21 }
enum class QueryStatus { COMPLETE, EMPTY, PARTIAL, NOT_CONFIGURED, UNAVAILABLE, DENIED }
data class QueryFilter(val number:String?=null,val id:Long?=null,val text:String?=null,val start:Long?=null,val end:Long?=null,val year:Int?=null,val status:String?=null,val kind:String?=null,val date:String?=null,val zone:String?=null)
data class QuerySpec(val domain:AiDomain,val projection:String="default",val filter:QueryFilter=QueryFilter(),val cursor:String?=null,val limit:Int=50,val maxBytes:Int=32768)
data class AggregateSpec(val domain:AiDomain,val metric:String="count",val filter:QueryFilter=QueryFilter(),val groupBy:String?=null)
data class SourceStamp(val domain:AiDomain,val sourceVersion:Long,val observedAt:Long,val sourceScope:String,val consistency:String,val scopeRevision:Long=0,val projection:String="default",val filter:QueryFilter=QueryFilter())
data class ScopedPage(val items:List<AiJson.Obj>,val stamp:SourceStamp,val matchedTotal:Long?,val nextCursor:String?,val complete:Boolean,val status:QueryStatus,val missingReason:String?=null) {
    val schemaVersion=2
    val returnedCount get()=items.size
}
data class ScopedValue(val value:AiJson.Obj,val stamp:SourceStamp)
data class ScopedAggregate(val value:AiJson,val stamp:SourceStamp,val complete:Boolean=true)
data class TextChunk(val reference:String,val stamp:SourceStamp,val codePointStart:Long,val codePointEnd:Long,val totalCodePoints:Long,val nextCursor:String?,val complete:Boolean,val text:String)
interface AiQueryApi {
    suspend fun query(spec:QuerySpec):ApiResult<ScopedPage>
    suspend fun get(domain:AiDomain,key:String,projection:String="default"):ApiResult<ScopedValue>
    suspend fun aggregate(spec:AggregateSpec):ApiResult<ScopedAggregate>
    suspend fun readText(reference:String,cursor:String?=null,maxChars:Int=4096):ApiResult<TextChunk>
    fun reset()=Unit
}

fun QueryFilter.json()=AiJson.Obj(mapOf("number" to (number?.let(::aiString)?:AiJson.Null),"id" to aiNumber(id),"text" to (text?.let(::aiString)?:AiJson.Null),"start" to aiNumber(start),"end" to aiNumber(end),"year" to aiNumber(year?.toLong()),"status" to (status?.let(::aiString)?:AiJson.Null),"kind" to (kind?.let(::aiString)?:AiJson.Null),"date" to (date?.let(::aiString)?:AiJson.Null),"zone" to (zone?.let(::aiString)?:AiJson.Null)).filterValues{it!=AiJson.Null})
fun SourceStamp.json()=aiObject("domain" to aiString(domain.name),"sourceVersion" to aiNumber(sourceVersion),"observedAt" to aiNumber(observedAt),"sourceScope" to aiString(sourceScope),"consistency" to aiString(consistency),"scopeRevision" to aiNumber(scopeRevision),"projection" to aiString(projection),"filter" to filter.json())
fun ScopedPage.json()=aiObject("schemaVersion" to aiNumber(2),"items" to AiJson.Arr(items),"stamp" to stamp.json(),"matchedTotal" to (matchedTotal?.let(::aiNumber)?:AiJson.Null),"returnedCount" to aiNumber(items.size.toLong()),"nextCursor" to (nextCursor?.let(::aiString)?:AiJson.Null),"complete" to aiBool(complete),"status" to aiString(status.name),"missingReason" to (missingReason?.let(::aiString)?:AiJson.Null))
fun TextChunk.json()=aiObject("reference" to aiString(reference),"stamp" to stamp.json(),"codePointStart" to aiNumber(codePointStart),"codePointEnd" to aiNumber(codePointEnd),"totalCodePoints" to aiNumber(totalCodePoints),"nextCursor" to (nextCursor?.let(::aiString)?:AiJson.Null),"complete" to aiBool(complete),"text" to aiString(text))

```

### AiSettingsApi.kt

```kotlin
package com.greatwall.callring.application.api

sealed class PatchField<out T> {
    data object Keep : PatchField<Nothing>()
    data class Set<T>(val value: T) : PatchField<T>()
}
sealed class LocalSecretChange {
    data object Keep : LocalSecretChange()
    class Replace(val value: String) : LocalSecretChange() { override fun toString() = "Replace([redacted])" }
    data object Clear : LocalSecretChange()
}
enum class AiSlot { ANALYSIS, TRANSCRIPTION }
data class ConversationBudget(val context: Long = 49152, val output: Long = 4096) {
    fun validate() { require(context in 8192..1048576 && output in 1024..16384 && output < context) { "ai.budget_invalid" } }
}
data class AiConnectionView(val protocol: AiProtocol, val url: String, val model: String, val keyConfigured: Boolean, val resolvedProfile: ProviderProfile = if(protocol == AiProtocol.ANTHROPIC) ProviderProfile.ANTHROPIC else ProviderProfile.STANDARD, val thinkingMode: ThinkingMode = ThinkingMode.DISABLED, val thinkingSupport: ThinkingSupport = ThinkingSupport.AVAILABLE, val thinkingNotice: String = "")
data class AiSettingsView(val analysis: AiConnectionView, val transcription: AiConnectionView, val budget: ConversationBudget, val revision: Long, val compatibility: ConversationCompatibility = ConversationCompatibility.AUTO)
data class RecordingFolderView(val displayName: String?, val access: AccessState)
data class RecordingSettingsView(val recordingEnabled: Boolean, val automaticAnalysis: Boolean, val captureMode: CaptureMode, val folderConfigured: Boolean, val revision: Long, val folderDisplayName: String? = null, val folderAccess: AccessState = if(folderConfigured) AccessState.UNKNOWN else AccessState.NOT_REQUIRED)
data class ConnectionPatch(val protocol: PatchField<AiProtocol> = PatchField.Keep, val url: PatchField<String> = PatchField.Keep, val model: PatchField<String> = PatchField.Keep, val thinkingMode: PatchField<ThinkingMode> = PatchField.Keep)
data class AiSettingsPatch(val analysis: ConnectionPatch = ConnectionPatch(), val transcription: ConnectionPatch = ConnectionPatch(), val budget: PatchField<ConversationBudget> = PatchField.Keep, val compatibility: PatchField<ConversationCompatibility> = PatchField.Keep)
data class RecordingSettingsPatch(val recordingEnabled: PatchField<Boolean> = PatchField.Keep, val automaticAnalysis: PatchField<Boolean> = PatchField.Keep, val captureMode: PatchField<CaptureMode> = PatchField.Keep)
interface AiSettingsApi {
    suspend fun patchCombined(recordingBase: RecordingSettingsView, recording: RecordingSettingsPatch, aiBase: AiSettingsView, ai: AiSettingsPatch, analysisSecret: LocalSecretChange = LocalSecretChange.Keep, transcriptionSecret: LocalSecretChange = LocalSecretChange.Keep): ApiResult<Unit> = aiUnavailable()
    suspend fun getRecordingView(): ApiResult<RecordingSettingsView>
    suspend fun patchRecording(base: RecordingSettingsView, patch: RecordingSettingsPatch): ApiResult<RecordingSettingsView>
    suspend fun getAiView(): ApiResult<AiSettingsView>
    suspend fun patchAi(base: AiSettingsView, patch: AiSettingsPatch, analysisSecret: LocalSecretChange = LocalSecretChange.Keep, transcriptionSecret: LocalSecretChange = LocalSecretChange.Keep): ApiResult<AiSettingsView>
    suspend fun copyConnection(from: AiSlot, to: AiSlot, expectedRevision: Long): ApiResult<AiSettingsView>
    suspend fun testSlot(slot: AiSlot): ApiResult<Unit>
    suspend fun testDraft(base: AiSettingsView, patch: AiSettingsPatch, slot: AiSlot): ApiResult<Unit> = aiUnavailable()
}
object UnavailableAiSettingsApi : AiSettingsApi {
    override suspend fun getRecordingView() = aiUnavailable()
    override suspend fun patchRecording(base: RecordingSettingsView, patch: RecordingSettingsPatch) = aiUnavailable()
    override suspend fun getAiView() = aiUnavailable()
    override suspend fun patchAi(base: AiSettingsView, patch: AiSettingsPatch, analysisSecret: LocalSecretChange, transcriptionSecret: LocalSecretChange) = aiUnavailable()
    override suspend fun copyConnection(from: AiSlot, to: AiSlot, expectedRevision: Long) = aiUnavailable()
    override suspend fun testSlot(slot: AiSlot) = aiUnavailable()
}
fun aiUnavailable(): ApiResult.Failure = ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "ai.unavailable"))

```

### AiSnapshotApi.kt

```kotlin
package com.greatwall.callring.application.api

data class RevisionVector(val incarnation: String, val values: Map<String, Long>)
/** Composite deletion can advance through its own guarded commits; foreign changes still conflict. */
data class CommandContext(val operationId: String, val expectedVersions: RevisionVector, val sessionGeneration: Long, val mayCommit: () -> Boolean = { true }, val expectedScopes:Map<String,Long>?=null, val continueOwnCommits: Boolean = false, val active: () -> Boolean)
enum class SnapshotDomainStatus { COMPLETE, EMPTY, NOT_CONFIGURED, UNAVAILABLE }
data class SnapshotDomain(val id: String, val status: SnapshotDomainStatus, val itemCount: Long, val sourceVersion: Long, val collectedAt: Long, val sourceScope: String, val digest: String, val data: AiJson)
data class AiBusinessSnapshot(val snapshotId: String, val capturedAt: Long, val zoneId: String, val revision: RevisionVector, val domains: List<SnapshotDomain>, val redactedCount: Int = 0) {
    val schemaVersion: Int = 1
    init { require(domains.size == 21 && domains.all { it.itemCount >= 0 } && domains.map { it.id }.toSet() == (1..21).map { "D%02d".format(it) }.toSet()) { "ai.snapshot_domain_failed" } }
}
interface AiSnapshotApi { suspend fun captureComplete(): ApiResult<AiBusinessSnapshot> }
object UnavailableAiSnapshotApi : AiSnapshotApi { override suspend fun captureComplete() = aiUnavailable() }

```

### AppUpdateApi.kt

```kotlin
package com.greatwall.callring.application.api

/** 发布页地址由后端提供，UI 不硬编码。 */
const val CALLRING_RELEASES_URL = "https://github.com/lcsaff/callring/releases"

/** 当前安装版本（来自系统应用信息，不在 UI 硬编码）。 */
data class AppVersionInfo(val versionName: String, val versionCode: Long)

/** 更新页状态机的全部状态；同一时刻只有一个主操作。 */
enum class UpdatePhase {
    IDLE, CHECKING, UP_TO_DATE, UPDATE_AVAILABLE, UNSUPPORTED_DEVICE,
    NO_INSTALLABLE, CHECK_FAILED, DOWNLOADING, VERIFYING, VERIFIED,
    VERIFY_FAILED, INSTALL_PERMISSION_REQUIRED, INSTALL_CANCELLED,
    INSTALL_PENDING_VERIFY,
}

/** 从 Release 元数据解析出的可用版本。 */
data class AppReleaseInfo(
    val versionName: String,
    val versionCode: Long,
    val publishedAtEpochMillis: Long?,
    val notes: String,
    val assetName: String,
    val sizeBytes: Long,
    val sha256: String,
    val downloadUrl: String,
)

/** 一次读取的不可变状态。 */
data class UpdateStatus(
    val current: AppVersionInfo,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val latest: AppReleaseInfo? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val failureReason: String? = null,
    val failureDetail: String? = null,
    val checkedAtEpochMillis: Long? = null,
    val releasePageUrl: String = CALLRING_RELEASES_URL,
)

/**
 * 应用内更新契约。UI 只消费状态与操作，不直接访问网络或系统安装器；
 * 版本比较只按 versionCode，versionName 仅用于展示。
 */
interface AppUpdateApi {
    suspend fun status(): ApiResult<UpdateStatus>
    suspend fun check(): ApiResult<UpdateStatus>
    suspend fun download(): ApiResult<UpdateStatus>
    suspend fun cancel(): ApiResult<UpdateStatus>
    suspend fun install(): ApiResult<UpdateStatus>
}

object UnavailableAppUpdateApi : AppUpdateApi {
    private fun unavailable() = ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "update.unavailable"))
    override suspend fun status() = unavailable()
    override suspend fun check() = unavailable()
    override suspend fun download() = unavailable()
    override suspend fun cancel() = unavailable()
    override suspend fun install() = unavailable()
}

```

### CallringUiApi.kt

```kotlin
// API v1 declarations for UI/backend coordination. Not wired into the app yet.
package com.greatwall.callring.application.api

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface CallringUiApi {
    val edits: UiEditsApi get() = UnavailableUiEditsApi
    val conversation: ConversationApi get() = UnavailableConversationApi
    val aiSettings: AiSettingsApi get() = UnavailableAiSettingsApi
    val numberDeletion: NumberDeletionApi get() = UnavailableNumberDeletionApi
    val update: AppUpdateApi get() = UnavailableAppUpdateApi
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
    /** 删除前预览：档案是否存在、该号码的通话记录条数。 */
    suspend fun numberDataImpact(number: String): ApiResult<NumberCoreImpact> = aiUnavailable()
    /** 单事务删除档案与该号码全部通话记录；expectedCallCount 不一致时拒绝。 */
    suspend fun deleteNumberCore(number: String, expectedCallCount: Int): ApiResult<Unit> = aiUnavailable()
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

```

### ConversationApi.kt

```kotlin
package com.greatwall.callring.application.api

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class ConversationPhase { IDLE, PREPARING, DISCOVERING, READING, REPAIRING, PARTIAL, CAPTURING, BUDGETING, REQUESTING, VALIDATING, EXECUTING, WAITING_FOREGROUND, COMPLETED, STOPPING, STOPPED, FAILED }
enum class ToolStatus { READ, COMMITTED, QUEUED, LAUNCHED, CANCELLED, DENIED, FAILED, UNKNOWN, SKIPPED }
enum class VerificationStatus { NOT_REQUIRED, PENDING, VERIFIED, COMMITTED_UNVERIFIED, FAILED }
data class ToolReceipt(val operationId: String, val toolCallId: String, val status: ToolStatus, val value: AiJson = AiJson.Null, val error: ApiError? = null, val commitRevision: RevisionVector? = null, val changedDomains: Set<DataDomain> = emptySet(), val observedAt: Long = System.currentTimeMillis(),val verification:VerificationStatus=VerificationStatus.NOT_REQUIRED,val noOp:Boolean=false,val verificationEvidence:AiJson=AiJson.Null)
data class ConversationMessage(val role: String, val text: String, val toolCalls: List<ConversationToolCall> = emptyList(), val receipts: List<ToolReceipt> = emptyList(), val blocks: List<AiJson> = emptyList(),val presentationKey:String?=null)
data class ConversationToolCall(val id: String, val name: String, val arguments: AiJson.Obj, val argumentError: ApiError? = null, val wireArguments: String? = null)
data class ConversationPhaseSpan(val phase:ConversationPhase,val startMillis:Long,val endMillis:Long)

data class ConversationState(val sessionId: String, val phase: ConversationPhase = ConversationPhase.IDLE, val messages: List<ConversationMessage> = emptyList(), val receipts: List<ToolReceipt> = emptyList(), val error: ApiError? = null, val target: String = "", val budgetBytes: Long = 0,val exchanges:List<ExchangeMetrics> = emptyList(),val sourceQueries:Long=0,val workCount:Int=0,val outcome:AiJson=AiJson.Null,val continuableTasks:List<String> = emptyList(),val phaseSpans:List<ConversationPhaseSpan> = emptyList(),val diagnosticsComplete:Boolean=true) {
    val busy get() = phase !in setOf(ConversationPhase.IDLE, ConversationPhase.COMPLETED, ConversationPhase.PARTIAL, ConversationPhase.STOPPED, ConversationPhase.FAILED)
}
interface ConversationApi {
    val state: StateFlow<ConversationState>
    val foreground: ForegroundActionApi
    suspend fun send(expectedSessionId: String, text: String): ApiResult<Unit>
    suspend fun sendTo(expectedSessionId: String, text: String, disclosedTarget: AiConnectionView): ApiResult<Unit> = aiUnavailable()
    suspend fun continueTask(expectedSessionId:String,taskId:String,disclosedTarget:AiConnectionView):ApiResult<Unit> = aiUnavailable()
    suspend fun stop(expectedSessionId: String): ApiResult<Unit>
    suspend fun newSession(expectedSessionId: String): ApiResult<Unit>
}
enum class ForegroundActionKind { CAPABILITY, APP_SETTINGS, PIN_WIDGET, TEST_OVERLAY, PREVIEW_CALL, IMPORT_AUDIO, IMPORT_RECORDING, IMPORT_CALENDAR, SELECT_FOLDER, EXPORT_CSV, CONFIRM, CREDENTIAL, COPY_CONNECTION, TEST_CONNECTION }
enum class ForegroundResultStatus { VERIFIED, LAUNCHED, CANCELLED, DENIED, UNAVAILABLE, STALE, EXPIRED, UNKNOWN }
sealed class ConfirmationTarget {
    data class Position(val id: Long, val name: String, val level: Int, val active: Boolean) : ConfirmationTarget()
    data class Leave(val id: Long, val start: Long, val end: Long, val note: String) : ConfirmationTarget()
    data class Adoption(val previousName: String, val previousOrganization: String, val name: String?, val organization: String?)
    data class Recording(val id: Long, val number: String, val startedAt: Long, val generation: Long, val adoption: Adoption? = null) : ConfirmationTarget()
    data class Connection(val slot: AiSlot, val connection: AiConnectionView) : ConfirmationTarget()
    data class NumberDeletion(
        val number: String,
        val profileExists: Boolean,
        val callCount: Int,
        val archiveCount: Int,
        val audioBytes: Long,
        val widgetBindingCount: Int,
    ) : ConfirmationTarget()
}
data class ForegroundTicket(val requestId: String, val sessionId: String, val turnId: String, val toolCallId: String, val generation: Long, val kind: ForegroundActionKind, val parameters: AiJson.Obj, val createdAt: Long, val expiresAt: Long, val launched: Boolean = false, val commandContext: CommandContext? = null, val confirmation: ConfirmationTarget? = null)
data class ForegroundHostResult(val status: ForegroundResultStatus, val document: DocumentRef? = null, val error: ApiError? = null)
interface ForegroundActionApi {
    val pending: StateFlow<ForegroundTicket?>
    fun attachHost(): String
    fun detachHost(token: String)
    fun beginLaunch(requestId: String, token: String): Boolean
    fun deliver(requestId: String, result: ForegroundHostResult): Boolean
}
object UnavailableForegroundActionApi : ForegroundActionApi {
    override val pending = MutableStateFlow<ForegroundTicket?>(null)
    override fun attachHost() = ""
    override fun detachHost(token: String) = Unit
    override fun beginLaunch(requestId: String, token: String) = false
    override fun deliver(requestId: String, result: ForegroundHostResult) = false
}
object UnavailableConversationApi : ConversationApi {
    override val state = MutableStateFlow(ConversationState("unavailable"))
    override val foreground = UnavailableForegroundActionApi
    override suspend fun send(expectedSessionId: String, text: String) = aiUnavailable()
    override suspend fun stop(expectedSessionId: String) = aiUnavailable()
    override suspend fun newSession(expectedSessionId: String) = aiUnavailable()
}

```

### ConversationCompatibility.kt

```kotlin
package com.greatwall.callring.application.api

import java.net.URI

enum class ConversationCompatibility { AUTO, STANDARD, QWEN_FAST }
enum class ProviderProfile { STANDARD, ANTHROPIC, QWEN_FAST }

/**
 * 共享的安全 profile 解析；凭证与任意端点子串不参与判断。
 * QWEN_FAST 已废弃：为兼容旧配置仍接受，但只作为能力提示，不再让任何保存/连接失败。
 */
fun resolveConversationProfile(connection: AiConnection, compatibility: ConversationCompatibility = ConversationCompatibility.AUTO): ProviderProfile {
    if (connection.protocol == AiProtocol.ANTHROPIC) return ProviderProfile.ANTHROPIC
    if (compatibility == ConversationCompatibility.STANDARD) return ProviderProfile.STANDARD
    val supported = connection.model == "qwen3.8-flash" || connection.model.matches(Regex("qwen3\\.8-flash-\\d{4}-\\d{2}-\\d{2}"))
    val uri = runCatching { URI(connection.url.trim()) }.getOrNull()
    val official = uri?.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1,443) &&
        uri.host?.lowercase() in setOf("dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com")
    return if(supported && official) ProviderProfile.QWEN_FAST else ProviderProfile.STANDARD
}

/** 旧“高级兼容”已废弃：QWEN_FAST 一律归一为 AUTO，读取、写入与补丁都走这里。 */
fun ConversationCompatibility.normalized(): ConversationCompatibility =
    if (this == ConversationCompatibility.QWEN_FAST) ConversationCompatibility.AUTO else this

enum class AiFailureKind { TRANSPORT, TIMEOUT, HTTP, AUTH, RATE, CONTEXT, PROTOCOL_JSON, PROTOCOL_SHAPE, PROTOCOL_STOP, OUTPUT_TRUNCATED, RESPONSE_TOO_LARGE, TOOL_COUNT, TOOL_ID, UNKNOWN_TOOL, NOT_ENABLED, ARGUMENT_JSON, SCHEMA, DOMAIN_VALIDATION, CONFLICT, CAPACITY, PROVIDER_MODE, COMMITTED_UNVERIFIED, UNKNOWN }
data class ConversationFailure(val kind: AiFailureKind, val code: String, val httpStatus: Int? = null, val providerCode: String? = null, val fieldPath: String? = null)
data class ModelUsage(val input: Long?, val output: Long?, val total: Long?,val cachedInput:Long?=null,val reasoningOutput:Long?=null,val cacheWriteInput:Long?=null)
data class ReplyDiagnostics(val finishReason: String = "", val usage: ModelUsage? = null)
data class ExchangeMetrics(val requestBytes: Int, val responseBytes: Int, val httpStatus: Int?, val profile: ProviderProfile, val elapsedMillis: Long, val diagnostics: ReplyDiagnostics? = null, val failure: AiFailureKind? = null,val requestFlags:Map<String,Boolean> = emptyMap(),val schemaCount:Int?=null,val contextMessages:Int?=null)

```

### NumberDeletionApi.kt

```kotlin
package com.greatwall.callring.application.api

/** 删除号码前的影响预览：数量用于二次确认与冲突校验。 */
data class NumberDeletionImpact(
    val number: String,
    val profileExists: Boolean,
    val callCount: Int,
    val archiveCount: Int,
    val audioBytes: Long,
    val widgetBindingCount: Int,
    val busy: Boolean,
)

/** 删除号码时的期望计数；与实时计数不一致表示状态已变化，必须重新预览。 */
data class DeleteNumberCommand(
    val number: String,
    val expectedCallCount: Int,
    val expectedArchiveCount: Int,
    val expectedWidgetBindingCount: Int,
)

/** 删除号码及其全部关联数据（档案、通话记录、录音归档与音频、小组件绑定）。 */
interface NumberDeletionApi {
    suspend fun impact(number: String): ApiResult<NumberDeletionImpact>
    suspend fun delete(command: DeleteNumberCommand): ApiResult<Unit>
}

object UnavailableNumberDeletionApi : NumberDeletionApi {
    private fun unavailable() = ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "number.delete_unavailable"))
    override suspend fun impact(number: String) = unavailable()
    override suspend fun delete(command: DeleteNumberCommand) = unavailable()
}

/** 核心数据（档案 + 通话记录）的删除影响，由 backend 计算。 */
data class NumberCoreImpact(val number: String, val profileExists: Boolean, val callCount: Int)

```

### RecordingApi.kt

```kotlin
package com.greatwall.callring.application.api

import java.net.URI

enum class AiProtocol { OPENAI, ANTHROPIC }

data class AiConnection(
    val protocol: AiProtocol = AiProtocol.OPENAI,
    val url: String = "",
    val apiKey: String = "",
    val model: String = "",
    val thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
) {
    fun validate() {
        val parsed = URI(url.trim())
        require(parsed.scheme == "https" && !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null &&
            parsed.rawQuery == null && parsed.rawFragment == null) { "ai.invalid_url" }
        require(apiKey.isNotBlank() && '\r' !in apiKey && '\n' !in apiKey) { "ai.invalid_key" }
        require(model.isNotBlank() && model.length <= 256) { "ai.invalid_model" }
    }
    fun endpoint(path: String): String = url.trim().trimEnd('/') + "/" + path
    override fun toString() = "AiConnection(protocol=$protocol, url=$url, apiKey=[redacted], model=$model, thinkingMode=$thinkingMode)"
}

enum class CaptureMode { SYSTEM_ONLY, MICROPHONE_EXPERIMENTAL }
data class RecordingSettings(
    val recordingEnabled: Boolean = false,
    val automaticAnalysis: Boolean = false,
    val analysis: AiConnection = AiConnection(),
    val transcription: AiConnection = AiConnection(),
    val captureMode: CaptureMode = CaptureMode.SYSTEM_ONLY,
    val systemFolder: String? = null,
    val conversationBudget: ConversationBudget = ConversationBudget(),
    val conversationCompatibility: ConversationCompatibility = ConversationCompatibility.AUTO,
) {
    fun validate() {
        conversationBudget.validate()
        require(transcription.protocol == AiProtocol.OPENAI) { "ai.transcription_requires_openai" }
        require(transcription.thinkingMode == ThinkingMode.DISABLED) { "ai.transcription_thinking_disabled" }
        if (analysis.url.isNotBlank() || analysis.apiKey.isNotBlank() || analysis.model.isNotBlank()) analysis.validate()
        if (transcription.url.isNotBlank() || transcription.apiKey.isNotBlank() || transcription.model.isNotBlank()) transcription.validate()
        if (automaticAnalysis) { analysis.validate(); transcription.validate() }
    }
}

enum class ArchiveStatus { WAITING_RECORDING, READY, QUEUED, TRANSCRIBING, ANALYZING, COMPLETE, FAILED }
enum class RecordingSource { SYSTEM, IMPORTED, MICROPHONE }
data class CallAnalysis(
    val name: String = "", val organization: String = "", val position: String = "",
    val purpose: String = "", val evidence: String = "", val inferredIdentity: String = "",
    val summary: String = "", val actionItems: List<String> = emptyList(),
)
data class CallArchive(
    val callId: Long, val number: String, val startedAtEpochMillis: Long,
    val generation: Long = 0, val status: ArchiveStatus = ArchiveStatus.WAITING_RECORDING,
    val source: RecordingSource = RecordingSource.IMPORTED, val audioPath: String? = null,
    val audioQuality: String = "recording.unverified", val durationMillis: Long? = null,
    val transcript: String = "", val analysis: CallAnalysis? = null,
    val error: String? = null, val attempts: Int = 0, val nextAttemptAt: Long = 0,
    val analyzedAt: Long? = null, val analysisModel: String = "", val transcriptionModel: String = "",
    val analysisProtocol: AiProtocol? = null,
    val manualAnalysis: Boolean = false,
)
data class RecordingFile(val uri: String, val name: String, val modifiedAt: Long, val size: Long? = null, val modifiedKnown: Boolean = true)

interface RecordingApi {
    suspend fun getIdentity(callId: Long): ApiResult<RecordingIdentity> = when(val result = getArchive(callId)) {
        is ApiResult.Failure -> result
        is ApiResult.Success -> result.value?.let { ApiResult.Success(RecordingIdentity(it.callId, it.number, it.startedAtEpochMillis)) } ?: ApiResult.Failure(ApiError(ErrorCode.NOT_FOUND, "recording.not_found"))
    }
    suspend fun listArchives(): ApiResult<List<CallArchive>> =
        ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "recording.unavailable"))
    suspend fun getSettings(): ApiResult<RecordingSettings>
    suspend fun saveSettings(settings: RecordingSettings): ApiResult<RecordingSettings>
    suspend fun testConnection(connection: AiConnection, transcription: Boolean): ApiResult<Unit>
    suspend fun getArchive(callId: Long): ApiResult<CallArchive?>
    suspend fun importRecording(callId: Long, number: String, startedAt: Long, document: DocumentRef, source: RecordingSource = RecordingSource.IMPORTED): ApiResult<CallArchive>
    suspend fun retry(callId: Long): ApiResult<Unit>
    suspend fun delete(callId: Long): ApiResult<Unit>
    suspend fun play(callId: Long): ApiResult<Unit>
    suspend fun stop(): ApiResult<Unit>
    suspend fun setSystemFolder(document: DocumentRef): ApiResult<RecordingSettings>
    suspend fun systemRecordings(): ApiResult<List<RecordingFile>>
    suspend fun inspectSystemRecording(document:DocumentRef):ApiResult<RecordingFile> = aiUnavailable()
}

data class RecordingIdentity(val callId: Long, val number: String, val startedAtEpochMillis: Long)

object UnavailableRecordingApi : RecordingApi {
    private fun unavailable() = ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "recording.unavailable"))
    override suspend fun getSettings() = unavailable()
    override suspend fun saveSettings(settings: RecordingSettings) = unavailable()
    override suspend fun testConnection(connection: AiConnection, transcription: Boolean) = unavailable()
    override suspend fun getArchive(callId: Long) = unavailable()
    override suspend fun importRecording(callId: Long, number: String, startedAt: Long, document: DocumentRef, source: RecordingSource) = unavailable()
    override suspend fun retry(callId: Long) = unavailable()
    override suspend fun delete(callId: Long) = unavailable()
    override suspend fun play(callId: Long) = unavailable()
    override suspend fun stop() = unavailable()
    override suspend fun setSystemFolder(document: DocumentRef) = unavailable()
    override suspend fun systemRecordings() = unavailable()
}

```

### ThinkingPolicy.kt

```kotlin
package com.greatwall.callring.application.api

import java.net.URI

/** 用户可见的思考模式：只有开与关。 */
enum class ThinkingMode { ENABLED, DISABLED }

/** 解析结论：可用、非官方网关（可用但参数可能不被执行）、不支持、未识别。 */
enum class ThinkingSupport { AVAILABLE, CUSTOM_GATEWAY, UNSUPPORTED, UNRECOGNIZED }

/** 内部派生的厂商合同，不落库、不展示。 */
enum class ThinkingContract { QWEN, DEEPSEEK, MIMO, UNCONFIRMED }

/** 一个思考参数；HTTP JSON 请求体顶层字段，值按 JSON 文本保存。 */
data class ThinkingParameter(val key: String, val jsonValue: String)

/** 一次解析的不可变结论，供客户端按开关状态取参数。 */
data class ThinkingPolicy(
    val contract: ThinkingContract,
    val protocol: AiProtocol,
    val support: ThinkingSupport,
    val disable: List<ThinkingParameter>,
    val enable: List<ThinkingParameter>,
    val reason: String,
    val officialHost: Boolean,
) {
    fun parameters(mode: ThinkingMode): List<ThinkingParameter> = when (mode) {
        ThinkingMode.DISABLED -> disable
        ThinkingMode.ENABLED -> enable
    }
}

/**
 * 三家厂商 × 两种协议的思考开关解析。纯函数、无网络、无密钥。
 * 依据：Qwen `enable_thinking`、DeepSeek/MiMo `thinking.type`；Anthropic 协议同样用 body 的 `thinking` 对象。
 */
object ThinkingPolicyResolver {

    private val OFFICIAL_HOSTS = mapOf(
        ThinkingContract.QWEN to setOf(
            "dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com",
        ),
        ThinkingContract.DEEPSEEK to setOf("api.deepseek.com"),
        ThinkingContract.MIMO to setOf("api.xiaomimimo.com"),
    )

    private val MIMO_THINKING_MODELS = setOf(
        "mimo-v2.6-flash", "mimo-v2.6-pro", "mimo-v2.6-pro-ultraspeed", "mimo-v2.5-pro", "mimo-v2.5",
    )
    private val QWEN_FAST = "qwen3.8-flash"
    private val QWEN_FAST_DATED = Regex("^qwen3\\.8-flash-\\d{4}-\\d{2}-\\d{2}$")

    private fun thinkingType(value: String) = listOf(ThinkingParameter("thinking", "{\"type\":\"$value\"}"))
    private val QWEN_DISABLE = listOf(
        ThinkingParameter("enable_thinking", "false"),
        ThinkingParameter("preserve_thinking", "false"),
    )
    private val QWEN_ENABLE = listOf(ThinkingParameter("enable_thinking", "true"))
    private val DISABLE = thinkingType("disabled")
    private val ENABLE = thinkingType("enabled")

    fun contractOf(model: String): ThinkingContract {
        val value = model.trim()
        return when {
            value.startsWith("qwen", ignoreCase = true) -> ThinkingContract.QWEN
            value.startsWith("deepseek", ignoreCase = true) -> ThinkingContract.DEEPSEEK
            value.startsWith("mimo", ignoreCase = true) -> ThinkingContract.MIMO
            else -> ThinkingContract.UNCONFIRMED
        }
    }

    fun resolve(protocol: AiProtocol, url: String, model: String): ThinkingPolicy {
        val contract = contractOf(model)
        if (contract == ThinkingContract.UNCONFIRMED) {
            return policy(contract, protocol, ThinkingSupport.UNRECOGNIZED, emptyList(), emptyList(), "thinking.unrecognized", false)
        }
        val official = isOfficialHost(contract, url)
        val support = if (official) ThinkingSupport.AVAILABLE else ThinkingSupport.CUSTOM_GATEWAY

        if (contract == ThinkingContract.QWEN) {
            if (protocol != AiProtocol.OPENAI) {
                return policy(contract, protocol, ThinkingSupport.UNSUPPORTED, emptyList(), emptyList(), "thinking.unsupported.protocol", official)
            }
            val name = model.trim()
            val known = name == QWEN_FAST || QWEN_FAST_DATED.matches(name)
            return if (known) policy(contract, protocol, support, QWEN_DISABLE, QWEN_ENABLE, "thinking.available", official)
            else policy(contract, protocol, ThinkingSupport.UNSUPPORTED, emptyList(), emptyList(), "thinking.model_not_verified", official)
        }

        if (contract == ThinkingContract.MIMO && model.trim().lowercase() !in MIMO_THINKING_MODELS) {
            return policy(contract, protocol, ThinkingSupport.UNSUPPORTED, emptyList(), emptyList(), "thinking.model_not_verified", official)
        }

        return policy(contract, protocol, support, DISABLE, ENABLE, "thinking.available", official)
    }

    private fun policy(
        contract: ThinkingContract,
        protocol: AiProtocol,
        support: ThinkingSupport,
        disable: List<ThinkingParameter>,
        enable: List<ThinkingParameter>,
        reason: String,
        official: Boolean,
    ) = ThinkingPolicy(contract, protocol, support, disable, enable, reason, official)

    private fun isOfficialHost(contract: ThinkingContract, url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.rawUserInfo != null) return false
        if (uri.port !in listOf(-1, 443)) return false
        val host = uri.host?.lowercase() ?: return false
        return OFFICIAL_HOSTS[contract]?.contains(host) == true
    }
}

/** 门禁助手：只根据解析结论返回可发送状态与原因，供客户端与后台共用。 */
fun ThinkingPolicy.blockReason(): String? = when (support) {
    ThinkingSupport.AVAILABLE, ThinkingSupport.CUSTOM_GATEWAY -> null
    ThinkingSupport.UNSUPPORTED -> reason
    ThinkingSupport.UNRECOGNIZED -> reason
}

```

### UiEditsApi.kt

```kotlin
package com.greatwall.callring.application.api

/** Baselines are the values shown when editing began, never a refreshed snapshot. */
interface UiEditsApi {
    suspend fun profile(base: CallerDraft, value: CallerDraft): ApiResult<CallerProfile> = aiUnavailable()
    suspend fun quiet(base: QuietSettings, value: QuietSettings): ApiResult<QuietSettings> = aiUnavailable()
    suspend fun work(base: WorkScheduleSettings, value: WorkScheduleSettings): ApiResult<WorkScheduleSettings> = aiUnavailable()
    suspend fun score(base: ScoreSettings, value: ScoreSettings): ApiResult<ScoreSettings> = aiUnavailable()
    suspend fun appearance(base: AppearanceSettings, value: AppearanceSettings): ApiResult<AppearanceSettings> = aiUnavailable()
    suspend fun position(base: SavePosition?, value: SavePosition): ApiResult<PositionOption> = aiUnavailable()
    suspend fun leave(base: SaveLeave?, value: SaveLeave): ApiResult<LeaveEntry> = aiUnavailable()
}
object UnavailableUiEditsApi : UiEditsApi

```
