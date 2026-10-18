# UI settings implementation plan

**Goal:** Apply the approved timezone, picker, loading feedback and appearance interactions.

**Architecture:** Keep the existing API and saved settings compatible. Use the shared Compose controls to fix all affected screens, and retain data during refreshes. Presets write a custom RGB value through the existing appearance writer.

**Tech stack:** Kotlin, Jetpack Compose Material 3, Robolectric and isolated Android instrumentation.

- [x] Update `SettingsScreenCases.kt` to verify preset-to-custom synchronization, read-only timezone selection, reset behavior and stable content position during refresh/save. Run the changed tests before implementation.
- [x] Replace the work schedule zone text field with a dropdown of available zone IDs; remove the summary, leave and report timezone labels. Retain timezone conversion and DST validation.
- [x] Remove the shared refresh/save progress bars and the appearance/widget bars. Keep initial empty-state loading, errors, retry and disabled actions.
- [x] Make presets set `selection = CUSTOM` and `customSeedRgb = preset.seedRgb`; derive the preview, HEX and selected preset from the effective color. Preserve color mode and immediate saving.
- [x] Align picker content and actions, constrain dialogs to the available window, and allow scrolling and keyboard input. Verify existing date conversion, cancellation and restored input tests.
- [x] Run `python tools/verify_ui.py`, build the app, review the diff and run isolated device tests when a device is available. Record results and limitations.

## Verification

- Before implementation, four regression tests failed as expected: home and appearance content each moved by 12 pixels, presets retained the old custom seed, and leave pages still displayed timezone labels.
- `python tools/verify_ui.py`: passed (151 tests, zero failures/errors), UI instrumentation APK assembled and lint passed.
- After adapting the short-window clock and extending coverage, `gradlew.bat :ui:testDebugUnitTest :ui:assembleDebugAndroidTest :ui:lintDebug :app:assembleDebug --console=plain`: passed (153 tests, zero failures/errors/skips).
- `PickerLayoutJvmTest.kt` renders native screenshots for calendar/input modes, short time dialogs and regular/short date ranges in `ui/build/screenshots/pickers/`. Images were visually inspected; the short time dialog now uses the horizontal clock with its actions below it.
- Read-only code review completed. Added slider-value coverage, short date/range coverage and memoization of effective theme seed; screenshot filenames include dimensions to retain both range layouts.
- ADB lists `192.168.10.149:5555` as offline. No app was installed; actual device keyboard/inset behavior still requires an online, unlocked device.
- Production debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
