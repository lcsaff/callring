# Recording/statistics decoupling implementation plan

**Goal:** Capture and archive incoming calls with statistics disabled, preserving statistics pause semantics.

**Architecture:** Independent recording event registry and archive identifiers, async observation executor, optional statistical link, independent archive list. Existing source changes remain in this working checkout.

## Tasks

- [x] Write failing coordinator tests for observation while statistics disabled, blocked observer isolation, and observer failure containment. Implement independent observation queue in `app/.../platform/calling/IncomingCallCoordinator.kt`; bind callbacks in `bootstrap/BackendGraph.kt`.
- [x] Write failing store tests for independent/idempotent registration, exact-session statistical links, and v1 migration. Extend `AndroidRecordingStore.kt` schema to v2 and preserve legacy archive payloads/tombstones. Each event and initial archive must commit atomically.
- [x] Write failing runtime tests with recording enabled and a statistics lookup that throws if called. Add event-owned lookup/recent discovery, own session registration and listing; allow nonzero archive IDs in `RecordingWorkflow.kt`, preserving positive legacy IDs.
- [x] Add default `RecordingApi.listArchives()` and independent list view model/screen plus navigation from the callers page. Tests cover loading/opening independent negative identities and list failure; no caller report or statistics lookup is required to open a recording.
- [x] Preserve optional report-to-recording association via exact sessionId/statistical key. Update docs and remove UI/README instructions requiring statistics enabled.
- [x] Run `.\gradlew.bat :backend:test :app:testDebugUnitTest :ui:testDebugUnitTest :app:assembleDebug :app:lintDebug :ui:lintDebug :ui:assembleDebugAndroidTest --console=plain`, `python tools/check_backend_boundaries.py`, `git diff --check`. Review changes independently and record unresolved real-device checks.

## Verification - 2026-10-18

- Full command above: BUILD SUCCESSFUL in 3m 2s; log `app/build/decoupling-final-verification.log`.
- Backend 89, app 109, UI 181 tests: **379 total**, zero failures/errors/skips. App/UI lint and both APK builds passed; boundary and whitespace checks passed.
- Regression coverage includes statistics-disabled observation without statistical writes, observer blocking/failure isolation, independent negative identifiers, idempotent session registration, migration, deleted first-identity reimport, independent old-archive reads, exact-session optional links across switch changes, late observation after answer, independent list/detail navigation, and initial navigation graph readiness.
- Directory provider fixture queries actual documents and copies bytes into a private recording file with statistics access forbidden. Deleting and rescanning suppresses reimport while preserving the source. Audio metadata inspection is injected for Robolectric; real device decoding/recording is not claimed by this fixture.
- Independent review findings fixed and re-reviewed; no remaining confirmed important issue in reviewed scope.
- Device 192.168.10.149:5555 remains offline. Real SIM capture, manufacturer folder access and actual providers remain unverified; this change removes the statistical business dependency, not shared-process/platform constraints.

Source changes remain in the working tree alongside existing user changes. No reset, merge, push, application installation or mixed source commit was performed.
