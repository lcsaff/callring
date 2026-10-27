# Current internal Kotlin contracts (T03)

`:contracts` is a pure JVM module. `:ui` depends only on `:contracts`, including its JVM, shared-test and Android-test source sets. `:backend` explicitly depends on `:contracts`. `:app` explicitly depends on `:ui`, `:backend`, and `:contracts` and assembles platform capabilities. No legacy `application.api` aliases remain.

The facade is `com.greatwall.callring.contracts.CallringUiApi` in `CallringApi.kt`. Public declarations are split into `common`, `change`, `callers`, `settings`, `report`, `runtime`, `widget`, `recording`, `ai`, and `command`. Types preserve defaults, wire values, enum names, error codes, and persisted codec semantics. Pure `AiJson` value constructors, scalar accessors, and encoding remain with the wire model. Structural protocol invariants (including complete 21-domain snapshot shape) remain valid at construction. Contracts contain no platform adapters, raw stored credentials, provider policy, SQL, network implementation, business merge rules, or connection/budget validation.

## Current operation catalog (T12)

`backend/application/conversation/operation/OperationCatalog` owns all 83 explicit `OperationDefinition` records. `OperationId` is business identity; `operationCode`, `legacyIndex` and `displayOrder` are protocol metadata, with displayOrder used only for discovery sorting. `ToolRegistry.definitions` is the sole 83-item discovery entry point. Registry validation, execution, source reads, scopes, authoritative confirmation, batch orchestration and receipts resolve the same injected catalog. `ConversationRequest.tools` carries OperationDefinition, with no ToolDefinition alias or old constructor.

The catalog declares 50 writes and 33 nonwrites (31 source reads, one catalog read, one in-memory plan). The 49 direct writes use direct scopes/readback; `CALLERS_SAVE_PROFILES` is a composite using each `CALLERS_SAVE_PROFILE` child's scope, confirmation policy and persisted readback, classified `BATCH_CHILD_PERSISTED_READBACK`. Startup rejects duplicate identities/protocol metadata, missing definitions/execution and incomplete or inconsistent read/write policies. Executor/ConversationService require QueryService and document readback verification; the old UI-read executor branch is deleted. All external schema/default/description text remains pinned by the frozen 83-operation golden.

The architecture guard rejects operation dispatch through numeric index/ordinal, discovery order or parsed O/X codes in registry consumers and the catalog itself. Fixed schema limits, capacity values, explicit protocol metadata, uniqueness checks and discovery sorting remain legal. The localized-text guard permits only a literal named `description` argument on a named OperationDefinition in the actual OperationCatalog; unrelated strings and computed descriptions are rejected, with no file-wide exemption.

## Approved internal signature changes

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

## Compatibility and verification

The 83-tool compatibility test remains `backend/ToolCompatibilityBaselineTest`: it compares all tool names and complete schemas plus O/X identifiers against the reviewed `ai-operation-compatibility.json`. Contract package movement does not change external AI or persisted formats, Android component FQCNs, or product behavior. `check_backend_boundaries.py` additionally pins the existing AI golden digest and verifies the current contract inventory below. Hashes cover normalized source with quoted wire strings preserved; adding/removing files, signature/default changes, or code changes require updating this reviewed inventory.

UI host rotation tests use a shared-test ticket driver to verify host UI attachment and no relaunch. Backend `ForegroundActionServiceTest` exercises the real service's host claim, detach/reattach, no duplicate launch, invalidation, and late-result handling. This separates UI host assertions from real-service lifecycle coverage.

T03 architecture guards cover dependency direction in every source set, including test dependencies, contracts isolation, UI imports, backend JVM isolation, and localized text. Backend domain-to-application rules remain transitional until T04; no blanket bootstrap allowlist is introduced. T04 native-domain cleanup, T05 storage redesign, and BackendContext/scoped SQL behavior are pending and unchanged by this extraction.

T10c removes only missing-capability method bodies: `UiEditsApi`'s seven edits, `CallersApi.getCallDetail/numberDataImpact/deleteNumberCore`, `WidgetBindingsApi.listBindings`, `RecordingApi.inspectSystemRecording`, `AiSettingsApi.patchCombined/testDraft`, `ConversationApi.sendTo/continueTask`, and `CallringUiApi.commandApi/adoptIdentity` now require explicit overrides. `RecordingApi.getIdentity/listArchives`, `SettingsApi.setQuietEnabled/synchronizeCalendar`, and `AiQueryApi.reset` also require explicit implementations, removing the second archive-derived identity path and missing-capability/no-op defaults. The unused `aiUnavailable` helper is deleted. All real method signatures, parameter defaults (including `LocalSecretChange.Keep`), errors and wire protocols are unchanged. Production assembly explicitly supplies every capability; omitted capabilities are no longer silently accepted. Test-only fixtures explicitly implement the required declarations. The historical T02 v1 snapshots and pinned AI operation golden are unchanged.

## Reviewed current contract inventory

2026-10-21 theme preset correction adds `settings/ThemePresets.kt`: immutable id/name/RGB catalog shared by UI and AI. `ThemePreset.name` is catalog data; the localized-literal guard permits only that static constructor field in this contract, with positive and negative tests. D11 current/scoped/full projections add read-only `presets` while retaining all existing appearance fields. O36/O37 descriptions now expose all three names and ids and explain PRESET selection and color-mode preservation. Tool identities, schemas, confirmation, write behavior and omission defaults are unchanged; only those two description values are updated in the reviewed external golden, with its pinned digest updated explicitly. Historical extraction baselines remain unchanged.

<!-- contract-inventory -->
```json
{
  "CallringApi.kt": {
    "sha256": "6e89ff3b315350d6efded784279ae4dd3d932be1135346df0a11fb5eee8f5588",
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
    "sha256": "e8c8e13bd5a890dc7292a474b559a2dac3cfa8bac6fd84b521a0fbc35d865980",
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
  "callers/CallersApi.kt": {
    "sha256": "87155410e1b49edf19774ebecdf0c8ab62e50461abef5fcad1230f3ce8e4ca6c",
    "declarations": [
      "HomeApi",
      "CallersApi",
      "CallerSearch",
      "OverviewQuery",
      "NumberCheck",
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
      "CallSource",
      "CallDetail",
      "HomeData",
      "IncomingCallUiSink",
      "IncomingCallUiRequest"
    ]
  },
  "change/ChangeFeed.kt": {
    "sha256": "178950eba34fea2449a7b55d8c34cdb1a1210cf2e8e21ebf84b38b413bb46a21",
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
    "sha256": "26c950ce370fcaa83827b35a5c71d77be97a9fcb7747f47cf336b48e673d7751",
    "declarations": [
      "CaptureMode",
      "ArchiveStatus",
      "RecordingSource",
      "CallAnalysis",
      "CallArchive",
      "RecordingFile",
      "RecordingApi",
      "RecordingIdentity"
    ]
  },
  "recording/RecordingSettingsView.kt": {
    "sha256": "73978a216255befd5c8dafb1f3eedacff101bbc676a6e49ab44bd7192ff3ed62",
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
    "sha256": "ae0d7f6936f1de198ef5aed37367ad6a5af69b8e2ff45d244cdaea5dfa929803",
    "declarations": [
      "RuntimeApi",
      "Capability",
      "AccessState",
      "Audibility",
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
    "sha256": "b695c49bdd91015b86d49622c1a1c15d556bc75f9dc4edf863e2f2283be8bc47",
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
