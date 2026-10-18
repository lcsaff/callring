# Home quiet controls implementation plan

**Goal:** Move quiet controls to Home with an adjacent custom-time dialog, restore the last enabled mode, and put personal settings first.

**Architecture:** Reuse QuietSettings and the existing validation/time editor. Backend owns remembered mode under the existing last_quiet_mode key. UI exposes a dedicated Home quiet ViewModel and dialog; main settings removes its quiet row and shows Personal first. Preserve unrelated dirty work in this checkout.

## Tasks

- [x] Test quick-enable defaults, CUSTOM disable/restore with unchanged weekly windows and number scope, invalid empty historical CUSTOM fallback, and changes through setQuietMode/saveQuietHours updating last mode. Add SettingsApi.setQuietEnabled and BackendState remembered mode; mirror monolithic contract.
- [x] Test SQLite close/reopen persistence and old last_quiet_mode compatibility. Load and save that existing setting explicitly in SettingsStore, including cases where only remembered mode changes. Keep existing quiet settings format.
- [x] Test and implement Home card, immediate switch writes with real saved-state display, custom dialog with modes, allNumbers and week time windows, scrollable layout, save/cancel/unsaved-exit handling. Saving errors preserve draft; successful save updates Home and closes dialog.
- [x] Move Personal to first in SettingsOverviewScreen, remove quiet row, and preserve legacy deep-link compatibility. Centralize widget enable behavior through the same production API while preserving test-fixture compatibility and rollback behavior.
- [x] Run backend/app/UI test suites, APK builds, app/UI lint and boundary checks. Independently review spec compliance and quality; record actual evidence and remaining offline-device checks. No installation or mixed source commit.

## Verification evidence (2026-10-18)

- Full Gradle verification succeeded: `:backend:test :app:testDebugUnitTest :ui:testDebugUnitTest :app:assembleDebug :app:lintDebug :ui:lintDebug :ui:assembleDebugAndroidTest`.
- 397 tests passed: backend 93, app 113, UI 191; zero failures, errors or skips. Lint: zero errors/fatal issues; existing warnings app 9, UI 28.
- SQLite tests cover restart, windows and scope preservation, remembered-mode-only updates, historical keys, unknown values, and disabling an active legacy CUSTOM configuration with no remembered key. The legacy missing-key regression failed before the fix and passed afterward.
- Home UI tests cover control positioning, fixed dialog actions and Sunday scrolling at 640 x 360 dp, invalid custom windows, save failure retention and dirty cancellation. Shared API tests cover widget behavior with stale widget preferences.
- Backend boundary/contract check and `git diff --check` passed. Independent static review completed, including the legacy persistence correction.
- APK: `app/build/outputs/apk/debug/app-debug.apk`. ADB `192.168.10.149:5555` remains offline; no installation, real-device dialog or real-call silencing verification claimed. No mixed source commit made.
- Detailed Gradle log: `app/build/home-quiet-final-verification.log` (ignored build output).
