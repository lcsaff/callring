# Quality review fixes implementation plan

**Goal:** Fix the five reviewed defects, synchronize the public contract, and verify regressions.

**Design:** Preserve the existing ports and UI. Composite deletion may continue from its own guarded commits, but must still reject intervening foreign changes and cancelled commands. Remaining widget/archive data must be removable after a partial failure. Readback checks every promised data category and retains partial failures. Conversation presentation only reads settings; paid probes remain explicit. Update metadata uses ETags only with a usable cache, and file verification runs on IO while installer UI runs on Main.

**Alternatives:** A single cross-store transaction cannot include preferences and audio files. Removing conflict checks would lose concurrent-update protection. Use guarded, recoverable steps instead; retain fail-closed readback.

**Tech stack:** Kotlin/JVM, Android/Compose, coroutines, SQLite, JUnit and Robolectric.

## Execution

- [x] Add and run failing deletion tests for guarded multi-step writes, foreign changes, cancellation, widget-only cleanup, and a failed cleanup retry.
- [x] Add and run failing conversation tests proving initialization and business changes make no testSlot calls and real responses update connection state.
- [x] Retain the existing ETag recovery fix and its tests from commit `9841e76`; add and run the failing IO verification regression and verify installer thread preservation.
- [x] Add and run failing receipt tests for residual calls, archives, bindings, incomplete/unavailable sources, and existing operation errors.
- [x] Implement scoped fixes, document command continuation semantics, and synchronize docs/contracts/CallringUiApi.kt.
- [x] Run targeted regressions, full JVM suites, boundary checks, Debug assembly and lint. Review the final diff and record results.

**Validation:** No real provider calls, paid tests, installs, or device writes. Use injected transports/inspectors and real backend state transitions. Preserve pre-existing workspace changes.

## Review follow-up

- Destructive deletion now observes the whole profile, all calls, and archive/preference collection guards. Collection guards conservatively reject unrelated archive/preference writes during the operation, including insertions into initially empty collections.
- Own-commit continuation is opt-in for deletion; ordinary commands retain strict stale-baseline rejection. Tests cover foreign profile edits and widget reassignment between deletion steps.
- Readback queries D03 (all stored calls, including hidden rows), D16 archives and D18 stored bindings, and rejects partial/unavailable sources and existing operation errors.
- Audio removal failure preserves the archive path, allowing a subsequent explicit retry.

## Final verification

- Added 19 regression tests across backend, app and UI, including real SQLite/preferences/file integration.
- `:backend:test`: 222 passed; `:ui:testDebugUnitTest`: 280 passed; `:app:testDebugUnitTest`: 218 passed. Total 720, with zero failures, errors or skipped tests.
- `:app:assembleDebug`, `:ui:lintDebug` and `:app:lintDebug` passed. Lint has no errors; existing warnings remain.
- `python tools/check_backend_boundaries.py` passed, and the four boundary-checker unit tests passed.
- `git diff --check` passed. Final build log and machine-readable totals are in ignored `build/quality-review/final-validation.log` and `final-summary.json`.
