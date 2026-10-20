# T05b implementation and measured quiet slice

Implementation base: `b3f4ab7`; worktree `C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring`, branch `codex/layer-boundaries`. Later parent commits contain preparation documents. This record describes the uncommitted T05b code for dual review; it does not mark T05 complete. No schema, persisted enum/wire values, algorithms, frozen v1 API or 81-tool golden were changed.

## Scope

- `StorageResources` replaces `LazyBackendStore` casts with an explicit `CoreStorageResource`: legacy state, full snapshot, narrow sessions, scoped queries, diagnostics and close. Construction, safe counts, scoped domains and versions do not open SQLite. One lazy factory instance supplies all IO. A closing flag rejects new entries before waiting for an admitted callback/cursor/transaction to finish; repeated close is idempotent. Initialization failure can retry; SQLite closes its helper if open fails.
- `CoreUnitOfWork` opens same-connection read/write sessions. Services receive `QuietTransactions`, not all-table sessions or a context locator. Session operations reject use after the callback, including failure. Statistics is exposed only as the minimal real second-table transaction capability for rollback evidence; its business entry remains T06.
- `QuietSettingsService` is the single production get/save/mode/enabled/draft entry, including command-bound APIs and AI routing. It selects only four quiet keys and writes only changed settings. Three-field dirty merge and conflict checks occur in the final write transaction. Remembered mode, OFF windows, empty CUSTOM resume fallback and ordered first-field validation remain. Stored and effective remembered modes are distinct so a legacy active CUSTOM without its remembered key can be disabled and reopened correctly.
- `MutationDelta` and `CommandCommitter` publish one logical core revision after successful SQL. Quiet/coarse core scopes, domain markers, incarnation, expected scopes, active/mayCommit and command completion retain existing coordinator checks. A no-op produces no data version/invalidation but can record command completion. The short write/publication uses `UseCaseRunner.write` cancellation protection. A prewarmed decision snapshot is recaptured from the latest inputs with only quiet replaced under the fence; a cold snapshot stays cold. Production incoming-call preparation explicitly invokes `refreshDecisionSnapshot` when cold.
- Immutable cumulative snapshots contain independent per-domain lastAny/wide/entity revisions. Each domain is capped at 1024 keys; overflow promotes only that domain to its current wide revision before clearing. Domain/entity token projection retains A and B across StateFlow conflation. The old observer is a per-collector cumulative domain-difference adapter.
- `ChangeObserver` reads cumulative tokens directly in production. `RefreshCoordinator` keeps one load in flight, reloads a pending latest token and rejects obsolete parameter/data results. Its loading callback has an independent run generation so a cancelled old finally cannot clear a new load. `ImmediateEditor` keeps its queue/draft semantics. Production HomeQuiet delegates committed changes to the feed and cannot replace later truth with an earlier save return.
- Architecture guards reject concrete SQLite store casts (Kotlin, Java and conversion helper), while allowing unrelated JSON/KeyStore casts. The current internal contract inventory records the cumulative protocol and nullable migration capability.

## Actual RED/GREEN evidence

All logs live under `build/qa/layer-boundaries/` (root Gradle logs). These are executed failures, not compile-only substitutes:

| Log | Observed failure / correction |
|---|---|
| `t05b-quiet-red.log` | Both real Graph fixtures fail new quiet fullStateLoads=0 assertion against the old implementation; green in `t05b-core-green.log` and later real/final runs. |
| `t05b-tracker-red.log` | Projected token map accepted mutation; freeze it; tracker four tests green. |
| `t05b-resources-red.log` | Postclose metadata failed to reject, and a version lookup blocked on the IO monitor and timed out; lease-free version and closing guard produce `t05b-resources-green.log`. |
| `t05b-guard-red.log` | Concrete store casts escaped the guard; precise patterns and positive legal casts pass architecture tests. |
| `t05b-edge-red.log` | A cancelled old load finally clears current loading; run generation correction is green in `t05b-edge-green.log`. |
| `t05b-legacy-memory-red.log` | Legacy active CUSTOM without last_quiet_mode reopens as NON_WORK after disable; compare stored remembered mode, then green in `t05b-edge-green.log`. |

The existing backend custom quiet decision test previously depended on a quiet write implicitly warming the whole decision snapshot. Its setup now explicitly prewarms the snapshot, preserving its decision/whitelist assertions while the production cold preparation remains explicit. This is an intentional removal of the full-state side effect, not deletion of a behavior assertion.

## Real Graph SQL and UI measurements

The same synthetic fixture and unrelated B facts as T05a are used for both 1,000 and 10,000 unrelated calls. Initialization, fixture seed and unrelated-fact audit are outside measurement windows. Existing `sql-baseline-*.json` documents remain frozen; the new evidence is `sql-scoped-*.json` and `quiet-access-*.json`.

`SCOPED_SETTING` counts actual SELECTs and successful cursor moves. `DIRECT_UPDATE/INSERT` count attempted statements and successfully affected rows separately; zero-row UPDATE before INSERT is disclosed. `writtenRows` is a statement affected-row metric, **not committed rows**: it is recorded before a surrounding transaction can later roll back. Successful Graph operations are associated with their observed transaction outcome; rollback correctness is asserted separately from actual database state. Rows are not SQLite internal scan counts and failed-transaction attempted writes are not claimed committed.

| Path | SELECTs | Consumed rows | Write attempts / affected rows | Full state loads | Logical revision |
|---|---:|---:|---|---:|---:|
| quiet read | 1 | 0 | 0 / 0 | 0 | 0 |
| first quiet save | 1 | 0 | UPDATE 0 + INSERT 1 / 1 | 0 | 1 |
| identical save | 1 | 1 | 0 / 0 | 0 | 0 |
| three-field edit | 1 | 1 | UPDATE 0 + INSERT 1 / 1 | 0 | 1 |
| command-bound disable | 1 | 2 | UPDATE 1 / 1 | 0 | 1 |

Every measured quiet access touches `settings` only, with identical ranges at both fixture sizes. Old quiet reads were 27 queries and 2,101 / 20,101 consumed rows, including 2,002 / 20,002 calls rows and a full-state load. Real changes publish only QUIET_HOURS according to the dependency matrix. B profile and all call facts remain exactly equal after every measured path.

Both actual QuietViewModel (ImmediateEditor→edits.quiet) and actual HomeQuietViewModel consume the production cumulative StateFlow in the Graph test. Together they initialize with two narrow reads. Each editor/home commit performs one transaction-old read and one reload per VM (three narrow reads total), with no duplicate reload. A real B report SnapshotViewModel remains at its one initial load across both quiet writes. The remaining legacy appearance write is deliberately verified to conservatively notify these consumers.

## Verification and remaining stage boundaries

Final gate (`t05b-final.log`) returned **BUILD SUCCESSFUL**, 1m33s, 146 tasks (34 executed / 112 up-to-date):

```text
:contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest
:app:assembleDebug :ui:lintDebug :app:lintDebug
:app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin
```

Fresh XML totals: backend 245, UI 288, app 233; failures/errors/skips all zero. Architecture and backend-boundary scripts PASS; architecture unit suite 17 and backend-boundary unit suite 4 PASS. Logs are `t05b-architecture.log`, `t05b-boundaries.log`, `t05b-architecture-tests.log`, `t05b-boundaries-tests.log`. `git diff --check` reports no whitespace errors. Toolchain: JAVA_HOME `D:/Apps/zulu17.54.21-ca-jdk17.0.13-win_x64`, ANDROID_HOME `D:/Apps/AndroidSDK`.

The real Graph operation and SQL JSON files are emitted under **app/build/qa/layer-boundaries/** because Gradle executes that test with the app working directory; the safe scoped measurements are copied into this evidence directory. Root build/qa contains command logs. Existing SQL baselines remain unchanged. A repeated sparse-weekday save assertion also exposed canonical storage comparison during development; canonical JSON comparison fixes the no-op, while the retained real quiet log is the later green run. Contracts tests are NO-SOURCE. BackendQa and UI AndroidTest source compilation are build evidence, not device execution.

The narrow transaction suite changes settings and statistics_intervals before throwing, confirms both rollback, then confirms both successful writes; all reads stay in one transaction and stateLoads remains zero. Escape cases and the legacy remembered-mode case use real SQLite. The resource suite uses an independent non-SQLite resource, concurrent first open/failed open/close, version during an active lease, and rejection of calls queued after closing starts. The quiet service suite covers no-op completion, hidden-only changes, dirty merge, rollback, stale scopes and cancellation after write/publication.

This slice does not claim to migrate profiles, reports, incoming calls or remaining settings: the original three measured paths still load full state once and old writes advance twice and broadcast broadly. All legacy successful writes enter the cumulative tracker conservatively, preventing new consumers from missing them. T06-T10 remove those old write/read paths; T11 removes remaining broad producers/consumers, nullable ChangeFeed.snapshots fallback and the legacy observer adapter. `UnavailableQuietTransactions` is explicit absence for calculation-only compositions, with no production full-state fallback; test-only memory fixtures adapt their own state. Recording retains its separate database and lifecycle.

No device run or production database access was performed. No APK delivery, release, commit or final T05 checkbox is performed by this implementer.

## Quality review P2: nullable feed during first editor load

The review found that an editor using the nullable transition feed had no token change to mark a pending refresh while its first load was suspended. A new real UI test captures the old value, suspends the first load, emits a relevant legacy event, then releases the load. `t05b-null-feed-red.log` shows the editor failing (one load instead of two); the other six refresh tests, including HomeQuiet's nullable feed cancellation case, pass. `ImmediateEditor.refresh` now requests force=true only when its observation token is absent. Production cumulative tokens retain their distinct/single-flight behavior. `t05b-null-feed-green.log` passes all seven cases. HomeQuiet already routes nullable feeds through RequestLoader; its new noncooperative old-load test confirms that cancellation/ticket checks reject the old result, so its production code needs no change for this P2.

`quiet.get` currently validates both quiet and conservative coarse core keys around its narrow read. This can retry an otherwise unrelated core write; it is a documented read-dependency debt for T06/T11. Narrowing that read token is separate from retaining coarse core command conflict compatibility and is not included in this corrective patch.

Corrective final gate: `t05b-review-final.log` **BUILD SUCCESSFUL**, 1m41s, 146 tasks (27 executed / 119 up-to-date), using the same complete command including BackendQa and UI AndroidTest compilation. Fresh XML totals are backend **245**, UI **290**, app **233**, with zero failures/errors/skips. Architecture/backend-boundary checks and 17+4 Python unit tests pass after this patch; `git diff --check` has no whitespace errors. Code and safe evidence are re-frozen for re-review.
