# T07 callers implementation and verification

2026-10-21. Production base `c5d62d5`; final verification ran at documentation HEAD `4b4d8eb` with the uncommitted T07 changes described here. This report is implementation evidence, not review approval, a commit, release authorization, or completion of the whole decoupling plan. The main plan, execution record and removal ledger remain parent-owned.

## Implemented boundary

Every CallersApi operation now uses backend/application/callers/CallerQueryService or CallerCommandService through CallersApiAssembly. The services instantiate with typed caller read/query/transaction capabilities, coordinator, dispatcher, necessary runtime observation and clock/whitelist callback. They have no BackendContext, BackendState, SQL, platform resource or universal replacement context. CallerQueryService receives CallerReads and CallerQueryTransactions rather than a writable calendar port or CoreUnitOfWork. CoreReadSession/CoreWriteSession explicitly compose the necessary capabilities; no unsupported/default production assembly was added.

The app/data/callers/SqliteCallerStore adapter shares SqliteCoreUnitOfWork's original SqlAccess, connection, transaction and active/read-only lease checks. Cursor consumption stays inside the session. Escaped profile readers reject access. Actual single-row inserts/updates/deletes, unlink counts and deleted call counts are checked, including RAISE(IGNORE), and all failure paths roll back before publication.

## Reads and consistency

Editor reads the target stored profile, the small role/options closure and my role. Active options plus the current retained inactive role remain selectable; relation compares caller/my levels, and level/name/id sorting remains Kotlin/JDK sorting.

UI search reads only stored profiles, never call-derived identities. Empty search uses SQL COUNT, ORDER BY number, LIMIT and Long OFFSET. Nonempty search visits batches of at most 256 number/name/department projections, applies the original trimmed Kotlin contains(ignoreCase=true) rule, counts every match and keeps only the requested page's numbers. It then loads that bounded page and its roles in batches; it never creates a list of all CallerProfiles, reads calls or adds an AI cursor. The Unicode regression compares the original predicate against SQLite-backed results for dotted/dotless I, sigma forms, Kelvin K, sharp S, supplementary-plane case pairs, literal % _ * ? [ ], combining characters, NUL, whitespace trim, multiple matching fields, historical number formats, empty/out-of-range pages and Int.MAX_VALUE page indexes.

Detail reads the target ID and its direct linked screen. Original classification, screening time, decision and note come from that screen; source becomes MERGED, and current classification stays the provider's. Latest uses hidden=0, time DESC/id DESC, LIMIT 1 and the same direct link closure. The adapter's RawCallRecord result is deliberately a **read projection**: it omits screeningKey/account and raw legacy tokens unnecessary for CallDetail. T08 must not reuse it as a complete write model or erase those original columns; no unused codec/framework was introduced in anticipation of T08.

Overview streams all visible A facts in ascending time/id order using a seven-field CallerOverviewFact projection. The existing StreamingReportAccumulator alone computes the range score, so calls before the range cannot establish a previous-call penalty. Lifetime count, latest and quality still use all visible A facts, including uncounted/out-of-range quality facts where appropriate. Only latest receives a full read/detail/link closure. WorkHistory, score, quiet, statistics and runtime capabilities provide the necessary work/quality inputs. No B call history, recording or unrelated profile is loaded. WorkClassifier and the existing score formula remain the sole algorithms.

Versioned reads capture a marker before the SQL session and recheck relevant scopes after it closes, retrying at most three times. SQL/read callbacks never acquire the coordinator fence. Metadata retains the **before** marker, including when an unrelated core commit happens during the read. Editor uses profile/position/my-position scopes; search uses a new profiles collection scope; overview uses its A calls/profile and settings/work/runtime closure. New caller writers publish profiles, and the still-live legacy coreScopeValues producer also marks the profiles collection, so legacy incoming/import writes cannot evade search consistency. That legacy aggregate is removed with T08/T09's remaining producers. Detail/latest need no revision metadata and use their single SQLite snapshot without a coordinator fence.

## Writes and deletion

Direct saves, UiEdits.profile and command-bound callers use one command service. The final transaction reads current values, merges dirty fields, checks the baseline/field whitelist and retained inactive role, trims final name/department, writes only actual changes and builds MutationDelta. Untouched fields retain final current values, nullable whitelist retains current, and an absent profile with empty dirty fields is still created. Direct dirty-field conflicts remain VALIDATION; UI draft conflicts remain CONFLICT. Name/department/position/whitelist first-error ordering is retained, including the formatted-draft-number case where the old UI premerge detected a dirty conflict before the later direct baseline identity check. Direct invalid number validation precedes the command guard and touches no storage; draft validation follows the guard/final merge. Cancellation is checked before commit processing.

CallerEditorViewModel now **requires** the draft edit function. Both the app page and OverlayController explicitly pass api.edits::profile. The nullable injection, direct full-field saveProfile fallback and whitelistTouched side channel were deleted. Stable commits refresh through the cumulative feed once; no-ops cause zero reloads. A delayed save completion that follows an already observed external change is reconciled without allowing that old result to become the final draft.

Actual changes advance core/revision once and publish the affected number to CALLERS/REPORTS, plus CALLS for a real call deletion. Profile existence/field/position-reference and actual deleted call ID/raw-number conflict scopes are included. No-op saves do not write SQL rows, advance revision or invalidate UI, but command completion is still recorded. Name/department/position changes do not rebuild decision inputs. Whitelist changes patch only the latest warm inputs' whitelist set; cold inputs remain cold. Deleting a whitelisted target applies the same patch.

Number impact/deletion keep the original NumberIdentity normalization rule for call.number and canonical-only profile deletion. A sequential-character GLOB is a **candidate superset** over DISTINCT stored number values; the original normalizer makes final membership decisions. Counts and ID/number projections, unlink and delete use only verified variants with bounded IN batches. Leading '*' can cause SQLite to scan its calls/index internally: this implementation narrows returned/decoded projections, and does **not** claim zero engine scans. Counts accumulate as Long and are rechecked inside the final write session, not reused from preview. All target outgoing links are cleared across **all** batches before any batch delete starts. Non-target incoming links retain FK protection, so abnormal external references fail and roll back rather than rewriting B or disabling constraints. Canonical profile-only deletion works, and variant profiles remain untouched. SQL AUTOINCREMENT sequences are not changed by deletion.

## RED/GREEN and actual verification

Logs live in build/qa/layer-boundaries. Successful intermediate gates are not the final-source gate.

| Evidence | Observed result |
|---|---|
| t07-access-red.log | Real BackendGraph editor at both 1k/10k fails the zero-full-state assertion on the old caller path. |
| t07-consistency-red.log | Independent narrow-port tests fail because the query holds the coordinator fence and invalid direct number loses first-error precedence to an inactive command guard. |
| t07-ui-red.log | Actual Graph + editor/report models expose an extra editor read on no-op save. |
| t07-concurrent-delete-red.log | Delayed save completion overwrites an external newer editor snapshot. This run stops at UI failure; it is not evidence that the app scope case ran. |
| t07-delete-scope-red.log | Separate real SQLite delete proves missing call:id conflict invalidation. |
| t07-ui-error-order-red.log | Formatted UI draft + dirty conflict returns the wrong first failure before the ordering fix. |
| t07-overlay-red.log | Actual OverlayController bypasses the shared draft edit function: expected one edit call, observed zero. |
| t07-service-edges.log / t07-error-order-green.log | Independent typed-port merge/guard/no-op/consistency tests and then complete backend/caller targeted tests pass. |
| t07-edges-green.log / t07-final-directed.log / t07-overlay-green.log | Relevant UI/backend gates and real SQLite/Graph caller cases pass as their corresponding implementation evolves. |
| t07-final-full-gate.log | Final formatted source, required overlay draft binding and dead-code removals pass the complete requested gate. |

Compilation/preparation issues were corrected separately: new test field-name mistakes, stale removed helper imports, initially missing CoreSession fake capabilities, a copied legacyResult in a newly cloned pending fixture, missing variant profile FK rows, and a probe assertion that originally included fixture seeding. These were not counted as architectural/behavioral REDs. The old failed-commit test now explicitly warms decision inputs before asserting failed-write preservation; no production cold-to-warm fallback remains. Mechanical transfers of existing pure algorithms are covered by their existing regressions and new input/output samples; this report does not claim a separate RED for every transferred function.

The nine independent narrow-service cases cover final-current dirty merge, direct/UI conflict codes and field order, absent empty-dirty creation, no-op command recording, retained inactive references, baseline whitelist/identity validation, cancellation/foreground guard zero storage, related-scope retry, unrelated-core commit metadata and bounded rejection of continuing relevant churn. The nine SQLite semantics cases cover Unicode/pages, linked detail/latest ordering, overview all-A versus range score/quality, 406 verified formatted-number variants and cross-batch links, external FK/ignored unlink/delete rollback, ignored profile insert/update/delete, escaped leases, actual call identity scopes, warm/cold whitelist preservation and preview-to-delete count change. Two real Graph access tests run at 1k/10k.

The actual CallerEditorViewModel and ReportViewModel A/B models wrap the production Graph APIs with loader counters. One A name commit reloads A editor/report once; B editor/report add zero reads. No-op adds no reload. An unreferenced new role reloads both editors' legitimate complete options, while both reports add zero reads. Reports no longer subscribe to the entire POSITIONS domain for irrelevant options; PositionService's actual CALLERS/REPORTS impacts carry affected profile/my-position changes, and report runtime capability dependency is explicitly subscribed.

## Safe scope evidence

Copied from the output of the final complete gate; historical baselines/goldens were not changed:

- caller-access-1000.json
- caller-access-10000.json
- sql-caller-1000.json
- sql-caller-10000.json

These contain only fixed operation/table/tag labels and counts, with no caller values, query text, parameters or private text. Audit reads/seeding occur outside measured production operations. Every measured caller operation has zero full-state loads/complete captures; every unrelated B raw/current/original/profile fact is compared unchanged.

Counts are SELECT invocations / successfully consumed result rows, **not SQLite engine scans**. Both scales have identical local closures:

| Operation | 1k and 10k reads/rows | writes/affected rows |
|---|---:|---:|
| editor | 3 / 2 | 0 / 0 |
| empty search | 4 / 4 | 0 / 0 |
| text search | 4 / 5 | 0 / 0 |
| save target | 3 / 2 | 1 / 1 |
| no-op save | 3 / 2 | 0 / 0 |
| detail | 1 / 1 | 0 / 0 |
| latest | 1 / 1 | 0 / 0 |
| overview | 15 / 48 | 0 / 0 |
| impact | 3 / 3 | 0 / 0 |

Overview's 48 rows include the necessary work/calendar/leave configuration closure, two A projections (stream fact and latest detail), role/profile and settings; it does not grow with B history. Nonempty search necessarily visits all stored profile search fields to obtain an exact total, but retains O(pageSize) requested-page state. The 603-profile Unicode fixture crosses multiple batches; no call reads are added.

## Deleted paths and remaining named consumers

Deleted: application/CallersService.kt and all its full-state business bodies; old application/CallerProjection.kt; its state-based relation/profile/detail/latest/validatePosition wrappers; UiEdits.profile premerge/merge/profileDirty/edit path; BackendContext.mutate and its automatic full-state decision capture; unused BackendContext.allDomains/events; VM nullable draft writer/full-field fallback/whitelistTouched path. Pure CallerProfile and CallDetail mapping now exists once under application/callers and is shared directly by remaining report/full-snapshot consumers. No feature flag, legacy forwarding service, unavailable default, parallel business implementation or production overload for old tests remains.

Still live, with actual consumers and deletion gates:

- ReportCalculator.visible(BackendState): ReportsService/ReportCalculator, HomeService and CoreSnapshotProjection still require full-state visible-call assembly. CoreSnapshotProjection.options(BackendState) has that one real full-snapshot consumer. T09 replaces these consumers and deletes the helpers. ReportCalculator's existing currentSchedule/requireIssues consumers remain; CoreSnapshotProjection still uses scheduleCopy/quietCopy/windowsCopy. These helpers are not called by the new caller services.
- ProfileStore.load/saveReferences/deleteUnreferenced and other legacy table snapshots: StorageSession still serves BackendContext.recordIncoming/importSystemCalls/updateDecision, HomeService, ReportsService, CoreSnapshotProjection/captureCore, CallringDatabase.onCreate and StorageMigration.upgrade. Migrate the incoming/import/upgrade consumers in T08 and report/home/full-snapshot/initialization consumers in T09/T13, then remove the full-state adapters.
- coreScopeValues/changedScopeKeys/profiles aggregate and BackendContext.coreTransaction/notifyChanged/refreshDecisionSnapshot: recordIncoming/importSystemCalls/updateDecision and explicit decision prewarming remain live. T08 migrates those writers/warming; T09/T13 removes their final full-state/snapshot infrastructure. New caller operations use none of them.
- BackendContext.write remains used by RingtoneService and WidgetBindingsService; it was not deleted as unused. Their T10/T11 migration remains separate from the removed full-state mutate path.
- OverlayViewModel's broad position/work dependencies and local whitelist-success refresh; HomeViewModel/SnapshotViewModel's legacy refresh branch; UiWidgetRefresh/WidgetUpdates' broad widget refresh and remaining platform publishers: T09/T11 must complete their consumer-specific dependency/coalescing work. The overlay editor's full-field fallback has already been removed here. This slice proves actual caller editor/report A/B isolation, not complete project-wide refresh isolation.
- Runtime-wide publication and other legacy DataChange/null ChangeFeed branches remain for T11; this slice only marks the necessary runtime consistency scope and report subscription.

The 81-tool schema/goldens/pins, persisted codes, schema, Android component identities, classification/scoring formulas and CSV encoding are unchanged. T08's new write codec must preserve the omitted raw columns mentioned above.

## Final gate and limits

JAVA_HOME=D:/Apps/zulu17.54.21-ca-jdk17.0.13-win_x64; ANDROID_HOME=D:/Apps/AndroidSDK.

The final-source command was:

```text
:contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest
:app:assembleDebug :ui:lintDebug :app:lintDebug
:app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin --console=plain
```

t07-final-full-gate.log reports BUILD SUCCESSFUL in 1m36s, 146 tasks (41 executed, 105 up-to-date). Fresh XML: backend 56 suites / 272 tests; UI 70 / 296; app 54 / 254; all failures/errors/skips 0. contracts:test is NO-SOURCE. Both lint reports have no errors: UI 31 Warning/3 Hint, app 6 Warning. Debug assembly, BackendQa Kotlin and UI AndroidTest Kotlin compile passed. The earlier t07-full-gate.log records a preceding source checkpoint and is explicitly **not** the final-source gate.

Both architecture guards and the 17 architecture + 4 backend-boundary Python tests pass; git diff --check is clean. New Kotlin production/service/adapter and new caller tests were formatted with a temporary official ktfmt 0.65 --kotlinlang-style tool under ignored build/qa; no formatter dependency was added to the repository.

No device tests, real SIM/standby/reboot, download/install/upgrade acceptance or APK publication was performed. No commit/merge/push/release was made. This working tree is ready to freeze for parent-owned specification review followed by quality review.
