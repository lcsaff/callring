# Home and calendar implementation plan

**Goal:** Implement the approved simplified three-tab workflow and automatically refreshed yearly calendar.

**Architecture:** Retain existing persistence and historical schedule snapshots. Extend work status with calendar day information; use the existing calendar import transaction for network data via a dedicated platform source. Separate day classification from current work time.

**Tech stack:** Kotlin, Compose Material 3, coroutine MVVM, Android HTTPS, JVM/Robolectric tests.

- [x] Add bounded annual holiday source, API sync, automatic background refresh, durable cache/failure behavior and parser/orchestration tests.
- [x] Replace per-weekday/makeup editing with one shared morning/afternoon schedule; test normalized saves, validation and reset.
- [x] Add explicit day-type/holiday information to work status; test holiday and leave/lunch/after-hours semantics without rewriting historical call decisions.
- [x] Simplify Home and navigate directly to leave records; move position management to Callers and retain personal-position selection in Settings. Reclassify settings and replace calendar editing with annual sync status.
- [x] Update navigation/Compose regression tests and documentation, review code, run backend/platform/UI checks, lint and app/test builds.

## Results

- RED tests reproduced old Home/calendar controls, missing shared schedule behavior, hidden holiday identity during leave, missing midnight refresh and unavailable annual synchronization.
- `python tools/verify_backend.py`: passed boundary check, 73 backend tests, 73 platform tests, 165 UI tests, Debug/Release/BackendQA and both instrumentation APK builds, app/UI lint. No device run.
- Final UI follow-up (`:ui:testDebugUnitTest :ui:lintDebug :ui:assembleDebugAndroidTest :app:assembleDebug`): passed, 166 UI tests after adding readable failed-sync feedback coverage. Final combined test count 312; zero failures/errors/skips.
- Production/shared API declaration mirror and business boundary check pass. Navigation coverage verifies Home→Leaves→Home and Callers→Positions→Callers. Saving an unedited legacy draft normalizes subsequent workday/makeup windows while preserving historical snapshots.
- Annual source fixture (2026) verifies 33 offdays and 6 makeup dates. Source parser rejects empty unpublished-year placeholders. Network work occurs before the database lock; unchanged content preserves metadata/audits; failed sync preserves cached calendar and presents a readable retry message.
- Spec/code review found no blocking issues. Existing unused compatibility callbacks remain; legacy manual-date data/API are retained for historical compatibility, with no editing/import controls in the UI.
- ADB device is offline. Actual device/emulator keyboard, background scheduling and real call behavior were not executed. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
