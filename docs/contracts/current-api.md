# Current internal Kotlin contracts (T03)

`:contracts` is a pure JVM module. `:ui` depends only on `:contracts`, including its JVM, shared-test and Android-test source sets. `:backend` explicitly depends on `:contracts`. `:app` explicitly depends on `:ui`, `:backend`, and `:contracts` and assembles platform capabilities. No legacy `application.api` aliases remain.

The facade is `com.greatwall.callring.contracts.CallringUiApi` in `CallringApi.kt`. Public declarations are split into `common`, `change`, `callers`, `settings`, `report`, `runtime`, `widget`, `recording`, `ai`, and `command`. Types preserve defaults, wire values, enum names, error codes, and persisted codec semantics. Pure `AiJson` value constructors, scalar accessors, and encoding remain with the wire model. Structural protocol invariants (including complete 21-domain snapshot shape) remain valid at construction. Contracts contain no platform adapters, raw stored credentials, provider policy, SQL, network implementation, business merge rules, or connection/budget validation.

## Current operation catalog (T12)

`backend/application/conversation/operation/OperationCatalog` owns all 81 explicit `OperationDefinition` records. `OperationId` is business identity; `operationCode`, `legacyIndex` and `displayOrder` are protocol metadata, with displayOrder used only for discovery sorting. `ToolRegistry.definitions` is the sole 81-item discovery entry point. Registry validation, execution, source reads, scopes, authoritative confirmation, batch orchestration and receipts resolve the same injected catalog. `ConversationRequest.tools` carries OperationDefinition, with no ToolDefinition alias or old constructor.

The catalog declares 46 writes and 35 nonwrites (31 source reads, two update observations, one catalog read, one in-memory plan). The 45 direct writes use direct scopes/readback; `CALLERS_SAVE_PROFILES` is a composite using each `CALLERS_SAVE_PROFILE` child's scope, confirmation policy and persisted readback, classified `BATCH_CHILD_PERSISTED_READBACK`. Startup rejects duplicate identities/protocol metadata, missing definitions/execution and incomplete or inconsistent read/write policies. Executor/ConversationService require QueryService and document readback verification; the old UI-read executor branch is deleted. All external schema/default/description text remains pinned by the frozen 81-operation golden.

The architecture guard rejects operation dispatch through numeric index/ordinal, discovery order or parsed O/X codes in registry consumers and the catalog itself. Fixed schema limits, capacity values, explicit protocol metadata, uniqueness checks and discovery sorting remain legal. The localized-text guard permits only a literal named `description` argument on a named OperationDefinition in the actual OperationCatalog; unrelated strings and computed descriptions are rejected, with no file-wide exemption.

## Approved internal signature changes

Number admission, direction and profile reset (2026-11-13): `CallDetail`, `CallArchive`, `RecordingIdentity` and archive-list rows carry an explicit `CallDirection`; legacy values default to INCOMING and newly discovered independent recordings use UNKNOWN until a unique call establishes direction. `CallArchive.resetPaused` is persisted internal processing state and is excluded from external AI views. `StorageManagementApi.previewNumberProfiles/confirmNumberProfiles/discardNumberProfiles` expose a local preview and confirmed application-only reset, with snapshot counts and an explicit recording-retention choice. Storage assembly requires a narrow reset service/port; live recording admission requires the original `NumberMatchFact`, automatic workflows require an authoritative direction resolver, and call-log import requires retained-recording reconciliation. Production and test consumers migrate together. Core schema 6 and recording schema 8 retain legacy data through the shared migration paths. The frozen external AI catalog, schemas and field shapes remain unchanged.

Real-device history/import correction (2026-11-12): `CallingReader.callerDeletedAt(number: String): Long?` reads the existing deletion boundary inside the calling transaction; `CallLogSyncJobController` no longer takes a statistics-start callback because history discovery is independent of score admission. `SystemRecordingImportStore.hasConflictingSystemSource` takes the nullable canonical `RecordingCall` target to distinguish independent-file ambiguity from selecting one recording for an existing call. All production/test consumers migrate together. No public contracts, external AI schemas, stored encodings or database schema change.

The same correction adds `hasConflictingSystemLink` and `releaseUnimportedSystemSource` to the internal import store for durable nearest-source selection and safe release of an uncompleted audio-free reservation. Discovery requires a cancellable narrow outgoing-call observation and supplies separate full-validation and lightweight-copy callbacks to `importAudio`; `RecordingService.importDiscovered` accepts both callbacks. `SystemRecordingScanner.withQueryCancellation` owns cancellation signals for SAF and call-log observations. These are internal implementation signatures only; current public contract inventory and AI protocol are unchanged.

CallHistoryQuery and CallHistoryCursor carry the optional exact number scope. CallHistoryEntry projects the optional caller name, region, self-reported position and configured position. Cursors bind the number, page size and call, profile and archive invalidation token. This internal query and projection leave the external AI protocol unchanged.

Unified call history (2026-11-10) adds CallersApi.queryCallHistory and CallersApi.getCallHistoryDetail with a cursor bound to calls and recording-archive revisions. RecordingApi adds owned playback sessions (getPlaybackSession, playOwned, stopPlaybackSession), analyzeTranscript, getCallerSuggestions and confirmCallerSuggestions. Archive analysis intent and confirmation state are stored in the local archive payload. The AI operation catalog continues to use recordings.retry and exposes no caller confirmation command.

System recording import window (2026-11-04): RecordingSettingsView appends systemImportDays=7 and RecordingSettingsPatch appends PatchField<Int>. Validation and field conflicts remain in AiSettingsService; external D13 projection and AI write schemas retain their reviewed shapes. Internal CallingReader.systemRecordingCalls adds a bounded number/time lookup; recording discovery uses explicit scan/import ports without widening CallringUiApi.

T11 makes `ChangeFeed.snapshots(): StateFlow<InvalidationSnapshot>` mandatory and removes `DataChange`, `observe()` and nullable/default feed implementations. Every production consumer uses cumulative tokens. `ChangeSubscription.entities` is a domain-local map of nonempty entity sets; each selected domain token is the maximum of its selected entity/wide revisions. Missing selectors mean the actual whole domain, while empty selectors are rejected. All selected domains must belong to the subscription. Epoch/incarnation invalidate every token. No single-string overload or legacy notification bridge remains. External tool schemas, D identities and persisted formats are unchanged.


T06a adds internal typed score/overlay/statistics/position/summary transaction capabilities to the shared `CoreUnitOfWork`, with scoped readers/writers invalid after callbacks. `CallringBackend` requires one `CoreUnitOfWork` composition argument; there is no default unsupported implementation or dual quiet/simple assembly. Calculation-only tests explicitly supply their own unused-port fake. `PreferenceStore` now inherits narrow `AppearanceStore`/`AppearanceReader` capabilities. New appearance and summary services receive those projections and cannot access widget bindings. Preferences adapters persist and roll back appearance; `AppearanceService` is its sole business publisher. `BusinessRevisionCoordinator.publishResource` advances one resource version, precise field scopes and cumulative impact in one guarded publication. T11 removes the temporary `SnapshotViewModel.refreshFromChanges` opt-in and its second loader; the feed is the single subscribed-query owner after successful commits. Frozen product contracts and their hashes below are unchanged.


These are repository-internal changes approved by the layer-boundaries specification. `docs/contracts/CallringUiApi.kt` and `current-api-baseline.md` remain historical snapshots; they are not current source requirements.

- All public protocol packages move from `application.api` into the domain packages above; no long-term aliases are provided.
- `RecordingApi.getSettings/saveSettings/testConnection` move to backend `application.recording.model.RecordingConfigurationPort`. Raw `AiConnection` and `RecordingSettings` remain backend configuration models, retaining redacted `toString`, validation, and endpoint behavior. Platform `AndroidRecordingRuntime` implements both public operations and this internal port via `RecordingCapability`; its bound adapter preserves command guards for raw saves.
- `RecordingApi.setSystemFolder` returns `ApiResult<Unit>`; normal and bound adapters still authorize and persist the folder, and consumers read the safe recording view afterwards. O51 no longer fetches unused raw settings; its safe result stays unchanged. Its internal `OperationDefinition.sharedApi` provenance now names both `aiSettings.getRecordingView + aiSettings.getAiView`; the external legacy `recordings_getSettings` tool name and schema remain unchanged.
- `AiConnectionView.copyToAnalysisProfile` is a trailing nullable field (default null). `AiSettingsService` computes it from the real connection using AUTO, independently of the current compatibility. Copy dialogs consume this safe field and preserve preview wording. A source that resolves STANDARD under current settings can preview QWEN_FAST after copying into analysis.
- `CallringUiApi abstract capabilities`: edits, conversation, aiSettings, numberDeletion, update, snapshots, recordings no longer have backend singleton defaults. Core backend assembly explicitly supplies its supported capabilities and unavailable fallbacks; platform assembly supplies full capabilities. UI shared-test fixtures own their fallback objects and ticket driver.
- `ConversationBudget.validate` is a backend extension. Connection/configuration validation, compatibility normalization, profile resolution, ThinkingPolicyResolver/ThinkingPolicy/ThinkingParameter/ThinkingContract/blockReason are backend policy. Only the public mode/support/profile/protocol enums cross the contracts boundary.
- Source/query `.json()` projection helpers reside in backend application conversation code; immutable source-query value models remain contracts.

## Built-in ringtone selection (2026-10-27)

Reviewed internal change: `RingtoneApi.restoreBuiltIn(ringtone: BuiltInRingtone = MELODY)` and the matching platform port select one of MELODY/CHIME/GENTLE/CLASSIC/DIGITAL. Existing AI restore operation still calls the default and its schema is unchanged. `RingtoneSettings.builtInRingtone` identifies the built-in choice; `previewSeconds` is nullable and null explicitly means continuous preview. Safe source projections include both fields, with no paths or callable URIs. `RingtoneApi.stopPreview()` and the mandatory port stop only preview, preserving real calls. All production and test consumers migrate together. The old preference keys and imported files retain their meanings; a new optional enum preference defaults to the existing melody without rewriting unknown values.

## Compatibility and verification

The 83-tool compatibility test remains `backend/ToolCompatibilityBaselineTest`: it compares all tool names and complete schemas plus O/X identifiers against the reviewed `ai-operation-compatibility.json`. Contract package movement does not change external AI or persisted formats, Android component FQCNs, or product behavior. `check_backend_boundaries.py` additionally pins the existing AI golden digest and verifies the current contract inventory below. Hashes cover normalized source with quoted wire strings preserved; adding/removing files, signature/default changes, or code changes require updating this reviewed inventory.

UI host rotation tests use a shared-test ticket driver to verify host UI attachment and no relaunch. Backend `ForegroundActionServiceTest` exercises the real service's host claim, detach/reattach, no duplicate launch, invalidation, and late-result handling. This separates UI host assertions from real-service lifecycle coverage.

T03 architecture guards cover dependency direction in every source set, including test dependencies, contracts isolation, UI imports, backend JVM isolation, and localized text. Backend domain-to-application rules remain transitional until T04; no blanket bootstrap allowlist is introduced. T04 native-domain cleanup, T05 storage redesign, and BackendContext/scoped SQL behavior are pending and unchanged by this extraction.

T10c removes only missing-capability method bodies: `UiEditsApi`'s seven edits, `CallersApi.getCallDetail/numberDataImpact/deleteNumberCore`, `WidgetBindingsApi.listBindings`, `RecordingApi.inspectSystemRecording`, `AiSettingsApi.patchCombined/testDraft`, `ConversationApi.sendTo/continueTask`, and `CallringUiApi.commandApi/adoptIdentity` now require explicit overrides. `RecordingApi.getIdentity/listArchives`, `SettingsApi.setQuietEnabled/synchronizeCalendar`, and `AiQueryApi.reset` also require explicit implementations, removing the second archive-derived identity path and missing-capability/no-op defaults. The unused `aiUnavailable` helper is deleted. All real method signatures, parameter defaults (including `LocalSecretChange.Keep`), errors and wire protocols are unchanged. Production assembly explicitly supplies every capability; omitted capabilities are no longer silently accepted. Test-only fixtures explicitly implement the required declarations. The historical T02 v1 snapshots and pinned AI operation golden are unchanged.

## Reviewed current contract inventory

2026-10-21 theme preset correction adds `settings/ThemePresets.kt`: immutable id/name/RGB catalog shared by UI and AI. `ThemePreset.name` is catalog data; the localized-literal guard permits only that static constructor field in this contract, with positive and negative tests. D11 current/scoped/full projections add read-only `presets` while retaining all existing appearance fields. O36/O37 descriptions now expose all three names and ids and explain PRESET selection and color-mode preservation. Tool identities, schemas, confirmation, write behavior and omission defaults are unchanged; only those two description values are updated in the reviewed external golden, with its pinned digest updated explicitly. Historical extraction baselines remain unchanged.

## V1-GAPS approved internal signature changes

The approved 2026-10-28 plan replaces the internal full-archive listing with `queryArchives` and bounded, generation-stamped list DTOs. Archive cursors bind the storage owner, exact archive revision and page size; preparation is explicitly incomplete. The facade requires injected `backup` and `storage` capabilities in every production assembly and command-bound facade. Backup progress is a structured phase observation; passwords remain transient CharArray inputs. Cleanup previews bind a frozen selection and target generations, and receipts distinguish committed, failed and unstarted items. Runtime samples carry `CoreReadiness`; the sole backend projection derives it from observed phone-state and screening-role prerequisites, with raw/unknown samples remaining UNKNOWN. These internal APIs add no AI operations, permissions, defaults or provider fallbacks; the external 85-operation compatibility golden is unchanged. The catalog's internal shared API label for O50 follows the new recording query; its external tool name/schema and existing D16 source pagination remain unchanged.

## Silent enrichment contract review (2026-11-03)

Reviewed breaking catalog revision 3 retires O13/O63/O70/O71 (overlay toggle, identity adoption, overlay test and call preview) without reusing their IDs. The overlay capability, invalidation domain, foreground actions, incoming UI sink and confirmation adoption payload are removed with all consumers. Existing phone/recording/system entrypoint identities remain.

CallerProfile/CallerDraft expose selfReportedPosition, region and four-field identityMetadata (revision, disputed, archive ID/generation and verbatim evidence). SaveCallerProfile accepts the new nullable text patches and disputedFields; omission preserves existing data. UiEditsApi.profile retains its single merge entrypoint. O07/X07 gain these fields, and O07 rejects marking a changed text value disputed in the same command before any commit. Identity text edits guard metadata scope because they clear dispute/provenance. No organization profile field is introduced; legacy analysis organization is preserved only as analysis data.

CallAnalysis adds department, region and fieldEvidence; CallArchive adds the durable identity baseline/source generation. The baseline explicitly marks ineligible recording numbers; these bypass profile storage while transcription and analysis continue, and legacy baselines default to eligible. ENRICHING/ENRICHMENT_FAILED distinguish local completion from paid analysis. Old JSON reads with empty new claims/evidence and no baseline, so it is not silently backfilled. Database version 4 upgrades legacy callers without changing department/position/whitelist, and historical core backups migrate in private staging before schema and content verification.

Offline transcription adds the internal `RecordingApi.transcribeOffline` and `cancelOfflineTranscription` commands. The detail projection carries transient run state, and `importRecording` accepts a default-false offline-only intent. These changes retain the stored archive encoding and external AI operation schema.

<!-- contract-inventory -->
```json
{
  "CallringApi.kt": {
    "sha256": "926c6b4b044cf0f30adf2fc10b4ab2017c21e704c770dece9a456142e3243752",
    "declarations": [
      "CallringUiApi"
    ]
  },
  "ai/AiJson.kt": {
    "sha256": "8f360e569f7cb5a5ba352ec33421af117b68427f9ad7a6fdd221a215ef06a53b",
    "declarations": [
      "AiJson",
      "aiObject",
      "aiString",
      "aiNumber",
      "aiBool",
      "string",
      "long",
      "boolean"
    ]
  },
  "ai/AiProtocol.kt": {
    "sha256": "0130594bb092b4be6855692286d58966bc9eb13769033a16b315c3ccf01238db",
    "declarations": [
      "AiProtocol"
    ]
  },
  "ai/AiQueryApi.kt": {
    "sha256": "8baad4fe2cf6aec8e2b41742f53f4fbc2cf8610c96ae19b8e4abcbd24934ea2a",
    "declarations": [
      "AiDomain",
      "QueryStatus",
      "QueryFilter",
      "QuerySpec",
      "AggregateSpec",
      "SourceStamp",
      "ScopedPage",
      "ScopedValue",
      "ScopedAggregate",
      "TextChunk",
      "AiQueryApi"
    ]
  },
  "ai/AiSettingsApi.kt": {
    "sha256": "91ee4435d4cbdc9973d270bd5ed97268e5979593f6f3fb28ee7ff3db44d4f8b8",
    "declarations": [
      "PatchField",
      "LocalSecretChange",
      "AiSlot",
      "ConversationBudget",
      "AiConnectionView",
      "AiSettingsView",
      "ConnectionPatch",
      "AiSettingsPatch",
      "AiSettingsApi"
    ]
  },
  "ai/AiSnapshotApi.kt": {
    "sha256": "eff7319889ddfc06f318c75fe07b86d968fb540b19fe3704809942be0318a73e",
    "declarations": [
      "SnapshotDomainStatus",
      "SnapshotDomain",
      "AiBusinessSnapshot",
      "AiSnapshotApi"
    ]
  },
  "ai/ConversationApi.kt": {
    "sha256": "07f170e57221769998ceb96dffe11d5032ff5842495a0b4d0101991a4a4ae5ab",
    "declarations": [
      "ConversationPhase",
      "ToolStatus",
      "VerificationStatus",
      "ToolReceipt",
      "ConversationMessage",
      "ConversationToolCall",
      "ConversationPhaseSpan",
      "ConversationState",
      "ConversationApi",
      "ForegroundActionKind",
      "ForegroundResultStatus",
      "ConfirmationTarget",
      "ForegroundTicket",
      "ForegroundHostResult",
      "ForegroundActionApi"
    ]
  },
  "ai/ConversationCompatibility.kt": {
    "sha256": "196dcdb603797e8880311f8161db4b34f38a6e9c9ba5b375d99f9d04a6a23a3c",
    "declarations": [
      "ConversationCompatibility",
      "ProviderProfile",
      "AiFailureKind",
      "ConversationFailure",
      "ModelUsage",
      "ReplyDiagnostics",
      "ExchangeMetrics"
    ]
  },
  "ai/ThinkingPolicy.kt": {
    "sha256": "cf8489ff839b34820547e857a6bd4752e6cfdfc97fdbfab26af026f3e209bf78",
    "declarations": [
      "ThinkingMode",
      "ThinkingSupport"
    ]
  },
  "backup/BackupApi.kt": {
    "sha256": "a4451568467404658419a28615869ff4bb5b047033ebf06beb1b477daf263258",
    "declarations": [
      "BackupSummary",
      "BackupPreview",
      "RestoreReceipt",
      "BackupPhase",
      "BackupProgress",
      "BackupApi"
    ]
  },
  "callers/CallHistory.kt": {
    "sha256": "353490f8fa87db72d52d3840e2c6d4bc517556df3e6a24233f88e741d97f1102",
    "declarations": [
      "CallHistoryQuery",
      "CallHistoryCursor",
      "CallHistoryPage",
      "CallHistoryEntry",
      "CallArchiveAvailability",
      "CallHistoryRecording"
    ]
  },
  "callers/CallersApi.kt": {
    "sha256": "3aad9ebdc2e69902dbb6329f02f929e22b7eae9be637e3e14c6f16821b8a9dd9",
    "declarations": [
      "HomeApi",
      "CallersApi",
      "CallerSearch",
      "OverviewQuery",
      "NumberCheck",
      "CallerFieldMetadata",
      "CallerProfile",
      "CallerDraft",
      "CallerEditorData",
      "SaveCallerProfile",
      "CallerOverview",
      "WorkReason",
      "ClassificationFact",
      "WorkDayKind",
      "WorkStatus",
      "CallResult",
      "CallDecision",
      "CallDirection",
      "CallSource",
      "CallDetail",
      "HomeData"
    ]
  },
  "change/ChangeFeed.kt": {
    "sha256": "2de616c4a9e89db723c5ea9e096f416a831933ddcd8ae7c457e82e5c0f408213",
    "declarations": [
      "ChangeFeed",
      "DataDomain"
    ]
  },
  "change/InvalidationSnapshot.kt": {
    "sha256": "f073599edeb0cb6dfa71f5c53ed7d7c084f9c58611b09f838858b011de79e64a",
    "declarations": [
      "DomainInvalidation",
      "InvalidationSnapshot",
      "ChangeSubscription",
      "InvalidationToken"
    ]
  },
  "command/CommandContext.kt": {
    "sha256": "2919fd4cceee96e363c139ed48b6c045e56db4f9e445eb50acb1d1e5a2057dba",
    "declarations": [
      "RevisionVector",
      "CommandContext"
    ]
  },
  "command/NumberDeletionApi.kt": {
    "sha256": "6c737f88b8eb27ca125bf82d5dd8b8feab9a8ed9e52745514a2e1fdb081692ce",
    "declarations": [
      "NumberDeletionImpact",
      "DeleteNumberCommand",
      "NumberDeletionApi",
      "NumberCoreImpact"
    ]
  },
  "command/UiEditsApi.kt": {
    "sha256": "31cffda6f07996b2fc61fae459941ed8a013c071d48c44b26599f03c14af8b77",
    "declarations": [
      "UiEditsApi"
    ]
  },
  "common/ApiTypes.kt": {
    "sha256": "11da8f9e87272fc72088a22e4bbc050aec1630fa147a2bd191a203c19f460c50",
    "declarations": [
      "ApiResult",
      "ApiError",
      "ErrorCode",
      "PageRequest",
      "Page",
      "SnapshotMeta",
      "DateRange",
      "TimeRange",
      "DocumentRef"
    ]
  },
  "recording/RecordingApi.kt": {
    "sha256": "32c2b42651441cf288412ea23c8495dae7b3db955b4fd1a35155cd9cbd1d2f4a",
    "declarations": [
      "CaptureMode",
      "ArchiveStatus",
      "RecordingSource",
      "OfflineTranscriptionPhase",
      "OfflineTranscriptionRun",
      "CallerIdentityBaseline",
      "CallAnalysis",
      "CallArchive",
      "ManualAnalysisState",
      "ArchiveAnalysisIntent",
      "CallerSuggestionField",
      "CallerSuggestion",
      "CallerSuggestions",
      "ConfirmCallerSuggestions",
      "CallerSuggestionConfirmation",
      "RecordingFile",
      "RecordingPlaybackSession",
      "RecordingApi",
      "RecordingIdentity",
      "ArchiveListItem",
      "ArchiveListCursor",
      "ArchiveListQuery",
      "ArchiveListPage"
    ]
  },
  "recording/RecordingSettingsView.kt": {
    "sha256": "db33e8f4b1c6cf57b0c3f3b492f005014febb5b3a6677186da90ec282db38637",
    "declarations": [
      "RecordingFolderView",
      "RecordingSettingsView",
      "RecordingSettingsPatch"
    ]
  },
  "report/ReportsApi.kt": {
    "sha256": "e6c85b6df2158d2f6c82d1ca5a38cd6dc0c9fae103f7fb9e0fa37edf8184ecd1",
    "declarations": [
      "ReportsApi",
      "ReportQuery",
      "ReportExportQuery",
      "CallFilter",
      "ScoreSummary",
      "ReportTotals",
      "DataQuality",
      "CallerReport",
      "ExportReceipt"
    ]
  },
  "runtime/AppUpdateApi.kt": {
    "sha256": "69d07fdeb74ac85382113dbcbb08f58c4edbd06e93cef705fed4d5b6bd14cad9",
    "declarations": [
      "AppVersionInfo",
      "UpdatePhase",
      "AppReleaseInfo",
      "UpdateStatus",
      "AppUpdateApi",
      "CALLRING_RELEASES_URL"
    ]
  },
  "runtime/RuntimeApi.kt": {
    "sha256": "f6004bb0c41c32907b22393593e2b2d2838b48bc4b9c30c3fbf45ce6d0c84c11",
    "declarations": [
      "RuntimeApi",
      "Capability",
      "AccessState",
      "Audibility",
      "CoreReadiness",
      "PhoneActivity",
      "PlaybackMode",
      "PlaybackState",
      "PlaybackStatus",
      "RuntimeStatus",
      "DiagnosticEntry",
      "SyncRequestState",
      "SyncRequestReceipt"
    ]
  },
  "settings/SettingsApi.kt": {
    "sha256": "dce2feae003b4ca7b82d1f431ef2c9937fe3d5808360b780333cfcd2e6a132dd",
    "declarations": [
      "SettingsApi",
      "RingtoneApi",
      "PositionOption",
      "PositionRelation",
      "QuietMode",
      "TimeWindow",
      "QuietSettings",
      "PositionSettings",
      "SavePosition",
      "WorkScheduleSettings",
      "SaveWorkSchedule",
      "CalendarYear",
      "DateOverrideKind",
      "DateOverride",
      "CalendarInfo",
      "LeaveState",
      "LeavePeriod",
      "SaveLeave",
      "LeaveEntry",
      "ScoreSettings",
      "ThemeSelection",
      "ColorMode",
      "AppearanceSettings",
      "SettingsSummary",
      "BuiltInRingtone",
      "AudioSource",
      "RingtoneSettings",
      "NumberRuleEntry",
      "NumberRuleSettings",
      "SaveNumberRule"
    ]
  },
  "settings/ThemePresets.kt": {
    "sha256": "084c82dcdba8654083089ed6701dca84ccc523260605eb0982608f731a818d95",
    "declarations": [
      "ThemePreset",
      "ThemePresets"
    ]
  },
  "storage/StorageManagementApi.kt": {
    "sha256": "30568a70005cc857483730747bffbd23299243adf4794caca5f713b4c95a50a4",
    "declarations": [
      "StorageInventory",
      "ArchiveCleanupSelection",
      "CleanupTarget",
      "ArchiveCleanupPreview",
      "CleanupItemStatus",
      "CleanupItemReceipt",
      "ArchiveCleanupReceipt",
      "NumberProfileResetPreview",
      "NumberProfileResetReceipt",
      "StorageManagementApi"
    ]
  },
  "widget/WidgetBindingsApi.kt": {
    "sha256": "11a687bc2e90ad62c99d3f02d50d2132a84108428c0fc9f90452d1c5f1d1a663",
    "declarations": [
      "WidgetBindingsApi",
      "WidgetBinding",
      "WidgetInstallState",
      "WidgetBindingsListing"
    ]
  }
}
```

## Reviewed configurable number rule AI delta (2026-10-23)

The approved number-rule plan appends `settings_getNumberRule` (X10, index/order82) and `settings_saveNumberRule` (X11,83). All earlier names, identifiers, argument schemas and ordering remain unchanged. O01 `root_checkNumber` keeps `raw` and its three result fields (`normalized`, `validProfileNumber`, `matchesRule`), with receipt classification changed from COMPUTATION to SOURCE_READ and description naming the configured rule. It reads the observed D10 configuration numberRule once, uses the common number mapping and the matcher injected from BackendConfiguration, and attaches the actual source stamp as receipt evidence.

GET returns prefix/length/revision. SAVE requires base{prefix,length,revision}, prefix and length, delegates to the bound formal SettingsApi command, uses only the numberRule scope and no separate confirmation, and verifies durable D10 prefix/length plus the committed rule revision. Existing records stay unchanged; later synchronization may import historical calls. Cancelled commands return established FAILED/ai.cancelled before writes/publication. Failed submissions remain cached within a task; retry in a new task requires a current base. Committed repeats reuse the ledger receipt; continuation retries readback without replaying the command. Mismatch remains COMMITTED_UNVERIFIED. The formal command's durable base revision check independently protects against stale writes.

## Configurable number rule contract update (2026-10-23)

Reviewed additions are `NumberRuleSettings`, `SaveNumberRule`, `SettingsApi.getNumberRule/saveNumberRule` and `DataDomain.NUMBER_RULES`. The two affected contract inventory records above are refreshed for these approved additions; existing declarations retain their wire identities. `CallringUiApi.checkNumber` is synchronous and reads prepared memory; clients first await `settings.getNumberRule`.

Number deletion scope correction (2026-11-05): NumberDeletionRecordingReads exposes only archive summaries; number deletion retains recording archives, audio, transcripts and analysis. archiveCount/audioBytes report retained items; expectedArchiveCount is wire-compatible informational input, not a deletion authorization or conflict check, and busy is false. X09 keeps its name, schema, confirmation and receipt shape; only its reviewed description changes, with catalogVersion 4. The compatibility golden is updated only for that description and catalogVersion, with its guard pin updated deliberately. Recording mutations no longer guard this command or need to disappear for a verified receipt.

CallerReader.callerDeletedAt / CallerWriter.markCallerDeleted store a durable per-number cutoff in existing core settings within the deletion transaction. RecordingIdentityEnrichment.capture now requires recordedAt; old retained recordings cannot recreate or write into deleted/recreated profiles, while future recordings retain normal enrichment. No database schema version or public wire shape changes.
