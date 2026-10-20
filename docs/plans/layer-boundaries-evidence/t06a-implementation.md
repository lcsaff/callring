# T06a simple settings, positions and consistent summary

Code base: `b99c5ae` (later parent commits add documentation only). Worktree: `C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring`, branch `codex/layer-boundaries`. This is the T06a slice, not completion of T06. No schema, persisted values, Android component identities, classification/scoring algorithms, frozen v1 contracts or 81-tool schemas change. No commit, device execution, release or delivery is performed by this implementer.

## Implemented boundary

`ScoreSettingsService`, `OverlaySettingsService`, `StatisticsSettingsService`, `PositionService`, `AppearanceService` and `SettingsSummaryService` are the single business entries for their direct, command-bound and UI/AI draft callers. Each constructor receives its typed capability; no service receives `BackendContext` or all-table `CoreUnitOfWork`. Composition requires one explicit `CoreUnitOfWork` and projects its capabilities. The preference services receive `AppearanceStore` or read-only `AppearanceReader`, without widget access. The overlay service receives only an overlay access observation.

`app/data/settings/SqliteSimpleSettings` is a per-session adapter over the existing `SqlAccess` and helper connection. Every operation checks the parent session lifetime/read-only guard. There is no second database, repository lookup, generic context or full-state fallback. Real SQLite tests cover rollback across score, overlay, position, own-position and statistics, post-callback rejection, FK RESTRICT and non-reused AUTOINCREMENT position IDs.

Score selects four keys and writes only changed fields. Validation retains first-field order and runs before storage on direct saves. Draft merge uses transaction-current values and preserves CONFLICT/`draft.field_changed`; reset to defaults can be a no-op. Score and positions never rebuild decision inputs or change call facts. Positions read target/own rows, use name/reference EXISTS and read reference numbers only for invalidation. Complete positions reads are limited to the API's explicit option-list requirement. Kotlin level/name/id sorting preserves prior Unicode behavior. Inactive own positions can be retained; another inactive position cannot be selected.

Overlay selects one key. Enable observes capability outside the fence, then command authorization is checked at commit; disable needs no capability observation. Statistics reads its one open row and MAX(closed end), enables at max(clock, closed end), disables at max(clock, start), and inserts/updates one row. Neither operation reclassifies or recounts calls. Under the commit fence, warm decision inputs are recaptured from the latest inputs with only the actual overlay/interval patch. Interval history is retained; cold inputs stay cold. Real Graph assertions explicitly prewarm outside measurement and verify history and unrelated decision object identity. A scheduler exception cannot turn committed enable into failure; scheduling occurs after leaving the fence. Cancellation injected inside the statistics writer still yields one commit/publication and one scheduler call in the executed test.

Appearance persists only SharedPreferences and publishes once through its business service. Direct null seed retains the current seed; explicit draft null can clear it. Android disk-failure rollback remains intact. It advances only preferences version, changed appearance field scopes and APPEARANCE invalidation. A no-op records a command without a revision. Appearance draft conflict retains the old draft entry's CONFLICT code; input validation remains VALIDATION.

Summary selects its settings keys, EXISTS(open statistics), latest config and optional own-position target in one SQL read session. Appearance is read across the separate preference resource with relevant scope markers checked before/after; score is explicitly included. A relevant race retries or returns retryable CONFLICT. An unrelated recording commit does not retry, and returned metadata retains the marker actually observed before reading. It never relabels old data with a later marker. There are no calls, calendar/audit collections or full profiles reads.

All migrated SQL writes describe actual changes via `MutationDelta` and the existing `CommandCommitter`: one core version/revision, fine scopes, coarse core command compatibility, domain marker and command completion. Failure has no publication; no-op has no revision or event. Appearance uses the corresponding single preference-resource publication. Impacts are SCORE_SETTINGS, OVERLAY_SETTINGS, STATISTICS, APPEARANCE or POSITIONS plus actual caller/report relationship dependencies. The existing per-domain 1024-entity tracker bound remains in force.

## Executed RED/GREEN

Logs are under `build/qa/layer-boundaries/`.

| Log | Actual observed result |
|---|---|
| `t06a-simple-red.log` | Real Graph score read fails at both 1k/10k: full-state loads 1 instead of 0. `t06a-score-green.log` passes. |
| `t06a-appearance-red.log` | Appearance commit advances 4 revisions instead of 1. It passes after single-publisher migration; the next overlay failure is retained in `t06a-appearance-green-overlay-red.log`. |
| `t06a-settings-red.log`, `t06a-appearance-green-overlay-red.log` | Old overlay no-op full-state load 1 / revision 1. The next stage passes overlay and exposes statistics revision 2 in `t06a-overlay-green-statistics-red.log`. |
| `t06a-statistics-green-position-red.log` | Statistics passes; positions read still loads full state 1. `t06a-position-green-summary-red.log` subsequently passes positions and exposes summary full-state load 1. |
| `t06a-simple-green.log`, `t06a-graph-final-green.log` | Complete real Graph operations pass, including actual overlay enable/disable and constant-size access. |
| `t06a-ui-red.log` | Real SettingsSummaryViewModel no-op write adds an unnecessary load, 3 instead of 2 total. `t06a-ui-green.log` passes. |
| `t06a-overlay-summary-red.log` | Actual OverlayViewModel full-summary loader misses SCORE_SETTINGS invalidation. `t06a-overlay-summary-green.log` passes all returned-field subscriptions. |
| `t06a-service-edge-red.log` | Typed-port tests expose appearance conflict code, invalid-score storage access and a missing score consistency key. `t06a-service-edge-green.log` passes. |
| `t06a-regression-second.log` | Existing DomainRegressionTest catches direct invalid position level reaching storage. Direct-only pure validation restores the original behavior. |

`t06a-opens-red.log` is a missing-counter instrumentation failure, not an existing behavior RED. `t06a-statistics-cancellation-red.log` was named before execution, but the proposed cancellation counterexample actually passed; it is GREEN evidence and required no scheduler implementation change.

Typed fake ports instantiate every new service independently. Tests exercise dirty merge/conflicts, validation order, rollback/no-op/actual-once, stale/coarse scopes, active/mayCommit, own-command continuation, cancellation, capability checks and scheduling outside the fence. Real Graph tests remain separate from these fakes.

## Safe SQL and loader measurements

`sql-settings-1000.json` / `sql-settings-10000.json` and `settings-access-1000.json` / `settings-access-10000.json` are copied from the actual **app/build/qa/layer-boundaries/** output. The old SQL baselines and quiet evidence are unchanged. Fixture seeding, unrelated B fact audits and explicit decision warm-up occur outside measurement windows. Every measured operation preserves B's profile and every raw/current/original/legacy call fact. All ordinary operations have fullStateLoads=0 and completeCaptures delta=0; appearance additionally has coreOpens=0.

The full operation SQL arrays are identical between 1k and 10k unrelated calls. SELECT/consumed-row counts below are observed cursor/scalar metrics, not engine scan counts.

| Operation | SELECTs / consumed rows | Write attempts / affected rows |
|---|---:|---:|
| appearance save | 0 / 0 | 0 / 0 |
| score read | 1 / 0 | 0 / 0 |
| first score save; draft edit | 1 / 0; 1 / 1 | 2 / 1 each |
| identical score | 1 / 1 | 0 / 0 |
| overlay no-op; enable; disable | 1 / 0; 1 / 0; 1 / 1 | 0 / 0; 2 / 1; 1 / 1 |
| statistics disable; enable; no-op | 2 / 2; 2 / 1; 2 / 2 | 1 / 1; 1 / 1; 0 / 0 |
| positions read | 2 / 1 | 0 / 0 |
| position create; no-op; edit | 3 / 1; 3 / 2; 4 / 2 | 1 / 1; 0 / 0; 1 / 1 |
| own-position set; no-op; clear | 4 / 4; 4 / 5; 3 / 4 | 2 / 1; 0 / 0; 1 / 1 |
| position delete | 3 / 3 | 1 / 1 |
| summary with no own-position | 3 / 6 | 0 / 0 |

An absent setting first attempts a zero-row UPDATE and then INSERT. `writtenRows` counts successful affected statements, not committed rows; transaction rollback is asserted from actual SQLite state separately. Reference checks consume scalar EXISTS results or required number projections, not full caller records. The summary measurement above has no own-position target; its optional target lookup is not claimed as a separately measured 3-query case.

Actual SettingsSummary, Positions, ScoreSettings, Appearance and Overlay ViewModels are exercised with a cumulative contract feed and loader counters. Unrelated recording/AI changes cause zero setting-loader increments; own domains reload the corresponding loader. Summary's stable commit reloads once and its no-op adds zero loads. Existing actual Quiet/HomeQuiet Graph loader checks remain and now assert appearance does not reload quiet. The earlier A/B isolation tests remain in the full suite. App update retains its independent status protocol.

## Deleted paths and remaining migration debt

Deleted in this slice: migrated legacy `SettingsService` bodies; `BackendContext.saveAppearanceExact`; old score/appearance/position premerge branches and unused `appearanceDirty`; unused `BackendContext.validateQuiet`; AndroidPreferences appearance commit/publish duplication; new/old default unsupported core/quiet implementations and dual constructor parameters; full-list statistics/append methods that existed only for the earlier capability proof; local-success reload fallback for migrated summary/position consumers. No parallel simple-setting implementation or fallback remains.

The remaining old code has live, named consumers and is not final compatibility infrastructure:

- **T06b:** work/calendar/override/leave in `SettingsService`, plus work/leave edits, still use the legacy transaction path until their historical batch closure is migrated.
- **T07–T10/T13:** `BackendContext`, legacy `BackendStore`/`BackendState`, full `SettingsStore` loading/saving and `coreScopeValues` still serve callers, incoming calls, reports/home, explicit full snapshots/restore and composite operations. Their simple-setting projection helpers cannot be blindly removed: `stats` is still used by home/report/full snapshots and `quietCopy` by `CoreSnapshotProjection`. T09 replaces those consumers; T13 deletes the obsolete full-state protocol after all live users are gone.
- **T11:** `SnapshotViewModel.refreshFromChanges`, its old RequestLoader/observeChanges branch and old mutation-success refresh remain **only as migration infrastructure** for Home, Calendar, Leaves, Ringtone and Runtime consumers. Remove the switch and old branch after those consumers migrate. Nullable ChangeFeed and generic legacy observer fallback likewise have a final T11 deletion obligation; migrated summary/position writes no longer depend on a null-feed success fallback. Widget binding publication and other unconverted producers remain outside T06a and must be narrowed/removed in T10/T11.
- **T06/T11 debt retained explicitly:** quiet.get still includes coarse core consistency, because this slice does not prove every restore/production quiet writer advances its dedicated marker. No speculative removal is made. Coarse core **command** compatibility remains required independently of that read debt.

The parent-owned `legacy-removal-ledger.md` and main execution plan track final deletion. T06 overall is not checked complete.

## Verification

The full gate before the final forwarding-helper cleanup, `build/qa/layer-boundaries/t06a-freeze-final.log`, returned **BUILD SUCCESSFUL**, 46s, 146 tasks (19 executed / 127 up-to-date), after removing the unsupported defaults, redundant legacy helpers and old full-statistics proof methods. Fresh JUnit XML totals: **backend 257, UI 293, app 236**, with zero failures/errors/skips. Both architecture scripts PASS; their 17+4 Python tests PASS; `git diff --check` is clean. Earlier complete gates (`t06a-final.log`, `t06a-clean-final.log`) also passed and the freeze log records the complete gate before the small cleanup below.

The executed complete command is:

```text
:contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest
:app:assembleDebug :ui:lintDebug :app:lintDebug
:app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin --console=plain
```

Toolchain: JAVA_HOME `D:/Apps/zulu17.54.21-ca-jdk17.0.13-win_x64`, ANDROID_HOME `D:/Apps/AndroidSDK`. BackendQa/AndroidTest compilation is not device testing; contracts test is NO-SOURCE.


## Quality review cleanup follow-up

Verified the remaining appearance private helper had exactly one caller after publication was moved into the business service. `AndroidPreferences.saveAppearance` now carries `@Synchronized` directly and contains the unchanged `updatePreferences` call; `saveAppearanceInternal` and its redundant blank line are deleted. No other production or test source changes accompany this follow-up, and synchronization, persistence and disk-failure rollback semantics are unchanged.

After this cleanup, `build/qa/layer-boundaries/t06a-cleanup-followup.log` records the requested single Gradle invocation of `:app:testDebugUnitTest --tests '*PublicationAdaptersTest*' --tests '*SimpleSettingsAccessContractTest*' --console=plain`: **BUILD SUCCESSFUL**, 13s, 52 tasks (5 executed / 47 up-to-date). Fresh XML contains 1 publication-adapter test and 2 real Graph access tests, all with zero failures/errors/skips. `git diff --check` is clean. This is targeted post-cleanup verification; the earlier full gate was not rerun after this mechanical helper removal and is not represented as such.
