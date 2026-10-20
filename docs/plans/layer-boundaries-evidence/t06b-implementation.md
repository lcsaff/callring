# T06b history settings implementation

2026-10-21. Production starting point: `db9b9a2`; parent-owned documentation checkpoints advanced HEAD during implementation without changing that production base. This file records the implementation and executed verification. It does not mark T06 complete, claim review approval, or authorize a commit/release.

## Implemented boundary

`WorkScheduleService`, `CalendarService`, and `LeaveService` live in backend/application/settings and instantiate independently with typed work/calendar/leave transactions, the existing coordinator/clock/dispatcher, and the required preparation capabilities. They do not depend on BackendContext, BackendState, SQL, profiles, recordings or a substitute universal service context. CallringBackend's already-required CoreUnitOfWork supplies the projected capabilities; production has no unavailable/default implementation or legacy fallback.

The new app/data/settings adapters share SqliteCoreUnitOfWork's original SqlAccess, SQLite connection, transaction and lease checks. The callback owns all cursor consumption; escaped readers/writers reject further access. Configuration, leave revision, audit and current-classification changes commit in one necessary transaction. Inserts use SQLite's persistent AUTOINCREMENT allocation. Ignored inserts and expected single-row writes are checked, so RAISE(IGNORE) cannot report a successful mutation.

The public facade, UiEdits.work/leave, and command-bound APIs call the same services. Work/leave draft merges read final current values and validate inside that transaction. Actual MutationDelta keys include schedule, calendar, overrides/override:date, leaves/leave:id, audit, and only rewritten calls/call:id/calls:number. Existing source/core resource marking remains so unconverted command creators still conflict correctly. One actual commit advances core version and revision once; a true no-op records its command completion with no revision, SQL row write or audit. A work append remains a real operation even when content is identical.

## Preserved semantics

Work reads select only the latest row, with a domain default for empty history. Appends calculate effective=max(clock,latestEffective+1), using 0 for an absent persistent row; a negative clock with no stored schedule is explicitly covered. SQLite allocates the version ID. Reset appends too. No schedule operation reclassifies existing calls. The original ordered zone/weekly/makeup validations remain, including direct invalid inputs failing before storage.

Calendar network/document preparation runs outside both coordinator fence and SQL transaction through runInterruptible. UseCaseRunner.io accepts a suspend block while retaining its existing exception mapping, and write still checks cancellation before its noncancellable commit boundary. The service depends only on CalendarDocumentReader, extracted as the required reading capability of DocumentStore. A cancelled blocked source is interrupted and cannot publish a configuration change.

Synchronization selects the effective metadata again inside the final transaction. A stored matching source/version preserves its published value, including when the freshly fetched published text is invalid and should be ignored. A concurrent import during preparation supplies the publication date retained by the eventual sync. Only actual metadata/day differences append audit and correct history. The data adapter retains an existing working makeup day's name when its working status is unchanged. REST override persistence remains an empty windows array. Calendar and leave output collections are frozen; nested override/work windows use the existing frozen mapping. Leave status samples clock once per entry.

Every actual leave change, including note alone, increments leaveVersion. Every stored call receives the current version as part of necessary classification correction. IDs are not reused after deletion. Identity/baseline/deleted draft errors and period/note dirty merge remain. An unchanged period takes the final stored bounds. Direct missing IDs keep NOT_FOUND. Existing DomainRegressionTest, DraftMergeTest and WorkClassifierTest remain in the complete gate.

Historical correction preserves the old **global** repair behavior: all current call classifications, including hidden, uncounted, unrelated-date and stale future calls, are reconsidered. This is business-required full-history processing, not a constant-size local operation. Complete historical schedules, calendar years/days, overrides, leaves and leaveVersion form a typed WorkHistory implementing the original domain WorkInputs. Each call batch materializes at most 256 rows containing only id, number (required for actual conflict/impact keys), time and current classification. Profiles, recordings, result/source/decision and original classification columns are not loaded by the batch. The existing WorkClassifier.classify performs the calculation; SQL contains no classification formula and no BackendState is constructed.

Only differing current columns are updated. Original columns are never touched. A reason/holiday pair that did not change does not write reason, preserving its raw persisted text even when another current column changes. Real SQLite tests cover a custom unknown raw reason with only leaveVersion changed, and preservation of an existing working-day name. Historical zone tests include America/New_York DST overlap/gap, crossing midnight, Pacific/Kiritimati and a cross-year local date, together with hidden and uncounted rows. A failure at call ID 300, beyond the first batch, rolls back configuration, audits, all earlier current updates and publication.

Cold decision inputs remain cold. An already warm decision snapshot is recaptured from the **latest** inputs, patching work schedules, calendar/overrides or leaves/leaveVersion as applicable. Quiet, overlay, whitelist and statistics remain intact. The real Graph test warms outside measured correction windows, changes quiet first, and checks those unrelated input values after every correction.

## UI invalidation

WORK_CALENDAR now uses explicit domain-local entity keys: schedule, calendar (years and overrides), leaves. WorkScheduleViewModel subscribes to schedule; CalendarViewModel to calendar; LeavesViewModel to leaves. Calendar and Leaves use the cumulative SnapshotViewModel/RefreshCoordinator path, without local-success reloads. SettingsSummary and the overlay's settings-summary query subscribe to schedule for their returned zone. Home/status consumers may still require the whole work domain.

Actual C-only UI model tests verify cross-subset negative cases, irrelevant recording/AI/appearance changes, Calendar/Leaves one stable commit -> one reload, true no-op -> zero additional loads, and a second invalidation while a calendar query is suspended -> a fresh read after that load. WorkSchedule's existing tests and UnifiedWorkSchedule tests pass. ImmediateEditor now starts its existing ChangeObserver directly so the same entity subscription produces both tokens and refresh events; no new legacy feed switch was introduced.

## Executed RED/GREEN and test limits

All logs are in build/qa/layer-boundaries/. A filename containing green is not by itself evidence of success; actual intermediate failures are identified below.

| Log | Observed result |
|---|---|
| t06b-work-red.log | Real Graph at both 1k and 10k fails workRead: fullStateLoads=1 rather than 0. |
| t06b-work-green.log | Backend 258 tests and both real Graph work access tests pass after the work migration and cancellation fix. |
| t06b-cancellation-red.log | A running coroutine cancels its own job and directly invokes invalid score/quiet/position saves. All three return instead of throwing CancellationException, producing failure indices [0,1,2]. |
| t06b-history-red.log | Real Graph calendarRead at both scales loads full state once. |
| t06b-corrections-green.log | Backend suite plus Graph local reads and necessary full-history override/leave corrections pass. |
| t06b-ui-red.log | A leaves entity invalidation reloads all three actual models: [2,2,2] instead of [1,1,2]. |
| t06b-ui-green.log, t06b-ui-edge.log | Precise entity subscriptions, existing work UI cases, commit/no-op counts and in-flight invalidation cases pass. |
| t06b-service-edge-red.log | Independent typed-port counterexample proves invalid fetched published is wrongly validated before selecting retained metadata. Another assertion in this run initially used a dirty makeup baseline that correctly conflicted; that fixture was corrected, not treated as a business defect. |
| t06b-ignored-write-red.log | Real SQLite RAISE(IGNORE) on calendar_audit returns success incorrectly; checked inserts then make it fail and roll back. |
| t06b-service-sql-edge-green.log | Five independent typed-port service tests and five real SQLite tests pass after the publication-validation/ignored-write fixes. |
| t06b-full-gate.log | Complete requested gate succeeds; results below. |

The initial newly created fixture had a missing newline, a later return statement was split across lines, and an intermediate test adaptation removed a default-zone suffix. Those were compilation/preparation failures, not architectural coupling REDs. The initial SQLite edge fixture incorrectly assumed an empty seeded audit/calendar and one JUnit expression returned a non-Unit result; those preparation mistakes were corrected. A previous intermediate history-green run also caught an old test relying on an incidental cold snapshot warm-up; that test now explicitly calls refreshDecisionSnapshot before checking failed-write snapshot preservation. Necessary history corrections were verified against their domain expected results; this file does not retroactively claim a separate behavioral RED for every mechanically transferred calculation.

The cancellation counterexample was a **T06a/T05 follow-up finding after their earlier review checkpoints**. QuietSettingsService.save, ScoreSettingsService.save and PositionService.save now ensureActive before pure early validation. Their invalid inputs still perform zero storage reads/writes, and the original valid-input cancellation/commit tests remain. The earlier checkpoint reviews did not cover this new counterexample.

## Safe SQL evidence and scope

The four files history-settings-1000.json, history-settings-10000.json, sql-history-settings-1000.json and sql-history-settings-10000.json were copied from the actual app/build/qa/layer-boundaries output generated by the complete gate. Original baselines/goldens are unchanged. These observations contain fixed labels, table names and counts only: no SQL text, bind values or caller numbers. Fixture seeding, full-state audits and explicit decision warming occur outside measured production operations. fullStateLoads delta is 0 for every measured new operation; local work operations preserve every unrelated raw/current/original fact, while necessary history correction compares each resulting current fact to WorkClassifier and separately preserves every original/unrelated field.

Counts below mean SELECT invocations / successful consumed cursor rows, **not engine scan rows**. Writes mean attempted statements / successful affected rows; rollback safety is asserted from actual database contents separately.

| Operation | 1k selects/rows | 10k selects/rows | writes (1k;10k) |
|---|---:|---:|---:|
| calendar get | 2 / 1 | 2 / 1 | 0;0 |
| absent override delete | 3 / 1 | 3 / 1 | 0;0 |
| leaves get | 1 / 0 | 1 / 0 | 0;0 |
| work get | 1 / 1 | 1 / 1 | 0;0 |
| work append; identical append; reset (each) | 1 / 1 | 1 / 1 | 1/1;1/1 |
| override save + necessary history repair | 14 / 1049 | 50 / 10049 | 1004/1003;10004/10003 |
| identical override save | 3 / 3 | 3 / 3 | 0;0 |
| override delete + necessary history repair | 14 / 1048 | 50 / 10048 | 2/2;2/2 |
| leave create + necessary history repair | 12 / 1048 | 48 / 10048 | 1005/1004;10005/10004 |
| note-only leave update | 13 / 1050 | 49 / 10050 | 1004/1004;10004/10004 |
| identical leave save | 1 / 1 | 1 / 1 | 0;0 |
| leave delete + necessary history repair | 13 / 1049 | 49 / 10049 | 1004/1004;10004/10004 |

The override delete still examines all history even when that particular deletion changes no current facts: its two writes are configuration delete and audit, with no unnecessary call UPDATEs. Calendar/leave no-ops do not scan calls. Full-history corrections consume more rows and batches at 10k, as required; no claim of constant access across those two sizes is made for them. Calls number is read only in the necessary correction projection to publish the real affected closure.

## Deleted paths and named remaining deletion debt

Deleted in this slice: all remaining historical-settings business bodies from SettingsService, the old BackendContext.replaceCalendar implementation, UiEdits work and leave premerge paths, BackendContext validateWeekly/validateWindows/validateZone/calendar/leaveEntry helpers, and WorkClassifier.reclassify(BackendState). The two tests that called that removed domain helper now explicitly classify the row(s) for their own calculation/persistence assertion; no production overload remains for tests. New services have a single production implementation; no old/new feature flag, unsupported default or compatibility fallback was added.

Still live and deliberately not deleted in this slice:

- currentSchedule: CallersService, ReportCalculator and CoreSnapshotProjection; scheduleCopy and quietCopy/windowsCopy: CoreSnapshotProjection. These full-state projection helpers disappear when those remaining T07-T09/T13 consumers migrate. requireIssues is also still used by ReportCalculator.
- ScheduleStore and SettingsStore full-state load/save: legacy incoming/caller/report/home and explicit full snapshot/restore consumers. They are not used by the migrated settings operations; deleting them before T07-T09 would break real consumers.
- BackendContext, BackendState/BackendStore, coreScopeValues and StorageResources.legacy: other unconverted callers/calling/report/home/runtime/explicit snapshot workflows, with their existing T07-T13 final deletion gates.
- SnapshotViewModel refreshFromChanges=false / RequestLoader / old mutation-success refresh: HomeViewModel, RingtoneViewModel and RuntimeViewModel. Calendar and Leaves have exited that branch. Nullable ChangeFeed and generic legacy ChangeObserver fallback still have other unconverted test/production consumers and retain the T11 deletion gate; they are not a second history-settings implementation.
- Coarse core/source command markers remain required until all command creators/restore producers use dedicated scopes. New historical business never invokes coreScopeValues.

The parent owns the main plan, execution record and legacy-removal-ledger updates. This implementation does not overwrite them or check T06 complete.

## Verification

Toolchain: JAVA_HOME=D:/Apps/zulu17.54.21-ca-jdk17.0.13-win_x64; ANDROID_HOME=D:/Apps/AndroidSDK.

Executed exactly this complete Gradle gate after freezing code/tests:

```text
:contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest
:app:assembleDebug :ui:lintDebug :app:lintDebug
:app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin --console=plain
```

t06b-full-gate.log reports BUILD SUCCESSFUL in 1m47s, 146 tasks (44 executed, 102 up-to-date). Fresh XML at completion: backend 55 suites / 263 tests; UI 70 / 295; app 52 / 243. All failures/errors/skips are 0. contracts:test is NO-SOURCE. Debug assembly, lint and BackendQa/UI AndroidTest compilation passed. No device execution or SIM/runtime acceptance is claimed.

After that gate, t06b-architecture.log records both architecture guards PASS and their 17+4 Python tests PASS. git diff --check has no errors; line-ending normalization warnings are informational. New Kotlin files and all changed Kotlin files were normalized to one final newline without staging or committing. Review and final checkpoint remain parent-owned.
