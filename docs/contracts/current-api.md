# Current internal Kotlin contracts (T03)

`:contracts` is a pure JVM module. `:ui` depends only on `:contracts`, including its JVM, shared-test and Android-test source sets. `:backend` explicitly depends on `:contracts`. `:app` explicitly depends on `:ui`, `:backend`, and `:contracts` and assembles platform capabilities. No legacy `application.api` aliases remain.

The facade is `com.greatwall.callring.contracts.CallringUiApi` in `CallringApi.kt`. Public declarations are split into `common`, `change`, `callers`, `settings`, `report`, `runtime`, `widget`, `recording`, `ai`, and `command`. Types preserve defaults, wire values, enum names, error codes, and persisted codec semantics. Pure `AiJson` value constructors, scalar accessors, and encoding remain with the wire model. Structural protocol invariants (including complete 21-domain snapshot shape) remain valid at construction. Contracts contain no platform adapters, raw stored credentials, provider policy, SQL, network implementation, business merge rules, or connection/budget validation.

## Approved internal signature changes

T05b adds the internal cumulative change protocol (`InvalidationSnapshot`, domain-local entity revisions, subscriptions and projected tokens). `ChangeFeed.snapshots()` returns an optional `StateFlow` during migration; production `CallringBackend` and its command-bound facade supply the same real cumulative flow. Quiet consumers use that flow directly. Legacy/fake implementations may temporarily return null; the remaining producers/consumers and removal of this nullable transition belong to T11. Legacy `observe()` is adapted per collector from cumulative snapshots and conservatively reports only domains changed since that collector last resumed. The external 81-tool schema, D domains, persisted values and frozen v1 API snapshot are unchanged.


T06a adds internal typed score/overlay/statistics/position/summary transaction capabilities to the shared `CoreUnitOfWork`, with scoped readers/writers invalid after callbacks. `CallringBackend` requires one `CoreUnitOfWork` composition argument; there is no default unsupported implementation or dual quiet/simple assembly. Calculation-only tests explicitly supply their own unused-port fake. `PreferenceStore` now inherits narrow `AppearanceStore`/`AppearanceReader` capabilities. New appearance and summary services receive those projections and cannot access widget bindings. Preferences adapters persist and roll back appearance; `AppearanceService` is its sole business publisher. `BusinessRevisionCoordinator.publishResource` advances one resource version, precise field scopes and cumulative impact in one guarded publication. `SnapshotViewModel` gains an internal `refreshFromChanges` opt-in used by summary and positions; other consumers retain their prior mutation refresh behavior. Frozen product contracts and their hashes below are unchanged.


These are repository-internal changes approved by the layer-boundaries specification. `docs/contracts/CallringUiApi.kt` and `current-api-baseline.md` remain historical snapshots; they are not current source requirements.

- All public protocol packages move from `application.api` into the domain packages above; no long-term aliases are provided.
- `RecordingApi.getSettings/saveSettings/testConnection` move to backend `application.recording.model.RecordingConfigurationPort`. Raw `AiConnection` and `RecordingSettings` remain backend configuration models, retaining redacted `toString`, validation, and endpoint behavior. Platform `AndroidRecordingRuntime` implements both public operations and this internal port via `RecordingCapability`; its bound adapter preserves command guards for raw saves.
- `RecordingApi.setSystemFolder` returns `ApiResult<Unit>`; normal and bound adapters still authorize and persist the folder, and consumers read the safe recording view afterwards. O51 no longer fetches unused raw settings; its safe result stays unchanged. Its internal `OperationContract.sharedApi` provenance now names both `aiSettings.getRecordingView + aiSettings.getAiView`; the external legacy `recordings_getSettings` tool name and schema remain unchanged.
- `AiConnectionView.copyToAnalysisProfile` is a trailing nullable field (default null). `AiSettingsService` computes it from the real connection using AUTO, independently of the current compatibility. Copy dialogs consume this safe field and preserve preview wording. A source that resolves STANDARD under current settings can preview QWEN_FAST after copying into analysis.
- `CallringUiApi abstract capabilities`: edits, conversation, aiSettings, numberDeletion, update, snapshots, recordings no longer have backend singleton defaults. Core backend assembly explicitly supplies its supported capabilities and unavailable fallbacks; platform assembly supplies full capabilities. UI shared-test fixtures own their fallback objects and ticket driver.
- `ConversationBudget.validate` is a backend extension. Connection/configuration validation, compatibility normalization, profile resolution, ThinkingPolicyResolver/ThinkingPolicy/ThinkingParameter/ThinkingContract/blockReason are backend policy. Only the public mode/support/profile/protocol enums cross the contracts boundary.
- Source/query `.json()` projection helpers reside in backend application conversation code; immutable source-query value models remain contracts.

## Compatibility and verification

The 81-tool compatibility test remains `backend/ToolCompatibilityBaselineTest`: it compares all tool names and complete schemas plus O/X identifiers against the unchanged `ai-operation-compatibility.json`. Contract package movement does not change external AI or persisted formats, Android component FQCNs, or product behavior. `check_backend_boundaries.py` additionally pins the existing AI golden digest and verifies the current contract inventory below. Hashes cover normalized source with quoted wire strings preserved; adding/removing files, signature/default changes, or code changes require updating this reviewed inventory.

UI host rotation tests use a shared-test ticket driver to verify host UI attachment and no relaunch. Backend `ForegroundActionServiceTest` exercises the real service's host claim, detach/reattach, no duplicate launch, invalidation, and late-result handling. This separates UI host assertions from real-service lifecycle coverage.

T03 architecture guards cover dependency direction in every source set, including test dependencies, contracts isolation, UI imports, backend JVM isolation, and localized text. Backend domain-to-application rules remain transitional until T04; no blanket bootstrap allowlist is introduced. T04 native-domain cleanup, T05 storage redesign, and BackendContext/scoped SQL behavior are pending and unchanged by this extraction.

## Reviewed current contract inventory

<!-- contract-inventory -->
```json
{
  "CallringApi.kt": {
    "sha256": "e138e7bf8dad25280e20273b69a926f3096d51c9972cf23d59dd7ec23f786e9e",
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
    "sha256": "4e9bb679fc929416475b8d04d2d0f214887db4113da2aa8688cceec77053ba4e",
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
    "sha256": "db225b56f88594fac2344ffc7b8175bd2be32cf70d134004d2e2d1bc6e3538c3",
    "declarations": [
      "PatchField",
      "LocalSecretChange",
      "AiSlot",
      "ConversationBudget",
      "AiConnectionView",
      "AiSettingsView",
      "ConnectionPatch",
      "AiSettingsPatch",
      "AiSettingsApi",
      "aiUnavailable"
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
    "sha256": "ae106f6bb8bf41dde5692d634298e7f6caabc0e390bf0a072a96cbe03b940e5e",
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
  "callers/CallringUiApi.kt": {
    "sha256": "a1def73b5ebb605c5a4badfdd1cfe13db00f688871566a81bce12105ba95ff89",
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
  "change/CallringUiApi.kt": {
    "sha256": "c80cb9314f7277d26a90b8c9ccfc3c846301e7936ef10bf2f2830df26b70d292",
    "declarations": [
      "ChangeFeed",
      "DataDomain",
      "DataChange"
    ]
  },
  "change/InvalidationSnapshot.kt": {
    "sha256": "06e9d4e2496ca79346d7269c0db13bfd23bab46a92805e45e8267cb433644ad1",
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
    "sha256": "393421c1ea6d16c79ba92b7fcd47565a67ca821e8284e154c8240af6e4599978",
    "declarations": [
      "UiEditsApi"
    ]
  },
  "common/CallringUiApi.kt": {
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
    "sha256": "5e4d41d968a113338a8563f15dd3f64cc5edc5efc9216447cb09055d4c69ea9a",
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
  "report/CallringUiApi.kt": {
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
  "runtime/CallringUiApi.kt": {
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
  "settings/CallringUiApi.kt": {
    "sha256": "7c9a36405b9709482150f9dab30a20d66420b2e8eb752c7494c9a66621debafc",
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
      "RingtoneSettings"
    ]
  },
  "widget/CallringUiApi.kt": {
    "sha256": "457a360530eb659a100b683c80ce7751873d87e072a464a0bef9e01612da33bb",
    "declarations": [
      "WidgetBindingsApi",
      "WidgetBinding",
      "WidgetInstallState",
      "WidgetBindingsListing"
    ]
  }
}
```
