# Android Interactions Implementation Plan

> **For agentic workers:** Use executing-plans to implement the tasks in this session. Steps use checkbox syntax for tracking.

**Goal:** 按已确认设计统一官方交互与反馈，覆盖日期时间、顶部栏、表单、返回和自适应布局，不增加无障碍专项工作。

**Architecture:** 在现有 UI design system 增加公共选择器、反馈宿主和表单退出保护；各 Screen 继续接收状态和回调，业务 API 与数据语义不变。通过现有 MutationState 的 completed 标识消费成功反馈，保留业务层并发保护。

**Tech Stack:** Kotlin 2.3.21, Compose BOM 2025.12.01, Material 3, Navigation Compose 2.9.5, Robolectric / Compose UI tests.

---

### Task 1: 页面、导航与统一反馈

Files: `ui/build.gradle`, `app/src/main/AndroidManifest.xml`, `ui/src/main/AndroidManifest.xml`, `ui/src/main/kotlin/com/greatwall/callring/ui/app/CallringApp.kt`, `ui/src/main/kotlin/com/greatwall/callring/ui/designsystem/components/SharedComponents.kt`, new `InteractionFeedback.kt`, `feature/shared/FeatureFeedback.kt`.

- [x] Add behavior tests for single-consumption Snackbar, whole-row switch and standard back action; run `:ui:testDebugUnitTest --tests '*Interaction*'` to observe failure.
- [x] Add BOM-managed navigation-suite and material icon dependencies. Use `NavigationSuiteScaffold(navigationSuiteItems = { item(...) })`, keep NavHost stable while changing size, use `NavigationSuiteType.None` on details.
- [x] Use `TopAppBar`, `IconButton`, `Icons.AutoMirrored.Filled.ArrowBack`, `nestedScroll`, `imePadding`, a centered width-limited content column, and a common SnackbarHost.
- [x] Observe mutations via `LaunchedEffect(state.completed)` and save the consumed completion per feedback instance; keep errors and progress inline, replace permanent success text with contextual Snackbar. Add an independent host for the overlay.
- [x] Enable `android:enableOnBackInvokedCallback="true"` and `android:windowSoftInputMode="adjustResize"` for Compose activities. Preserve root system back behavior.

### Task 2: 官方日期和时间选择器

Files: new `designsystem/components/DateTimeInputs.kt`, `feature/settings/components/SettingsComponents.kt`, `feature/settings/{WorkScheduleScreen,QuietScreen,CalendarScreen,LeavesScreen}.kt`, `feature/report/ReportScreen.kt`, shared settings tests.

- [x] Write tests that selecting/cancelling time ranges invokes exactly the expected callback, UTC date round-trips in differing timezones, and note-only leave edits preserve exact epochs. Observe the new interaction tests fail before implementation.
- [x] Implement shared DatePickerDialog/DateRangePicker and a BasicAlertDialog containing TimePicker/TimeInput with standard confirm/dismiss actions. Initial date conversion: `date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()`; convert back with UTC.
- [x] Replace time syntax entry with editable start/end rows and add/remove actions; equal endpoints invalid, crossing midnight explicit. DateOverride and leave forms reuse the same pickers and keep save/cancel available while content scrolls.
- [x] Preserve original leave instant when the chosen local date/time equals the original display; reject DST gaps using `zone.rules.getValidOffsets(local).firstOrNull()`.
- [x] Update restoration tests to exercise selection UI, not retired raw syntax. Run shared cases on JVM and compile device tests.

### Task 3: 表单与破坏性操作

Files: new `designsystem/components/FormInteractions.kt`, `feature/callers/{CallerEditorForm,CallerEditorScreen,NumberLookupScreen}.kt`, `feature/settings/{PositionsScreen,WorkScheduleScreen,ScoreSettingsScreen,RingtoneScreen}.kt`, `feature/settings/components/SettingsComponents.kt`, widget configuration screen.

- [x] Add tests proving cancel does not delete/reset, confirm executes once, dirty form back preserves draft unless discard is confirmed, and phone fields expose the expected IME action.
- [x] Add reusable `ConfirmAction` for destructive actions and `FormExitGuard` using `PredictiveBackHandler` only while dirty; after a completed gesture show AlertDialog with keep/discard (and save where valid). Clean root screens never intercept back.
- [x] Use standard exposed dropdown for position selection, keyboard types and Next/Done, clear focus before save, and stable button/progress state. Whole-row toggle delegates to a Switch with no second onCheckedChange handler.
- [x] Wire confirmations for position/leave/date deletion, work/score reset and restoring built-in ringtone. Do not offer unsupported undo.

### Task 4: 集成、代码审查与验证

Files: `README.md`, `docs/plans/UI重构_验收.md`, all modified tests.

- [x] Run `python tools/verify_ui.py` and `./gradlew.bat :app:assembleDebug --console=plain`. Expected zero test failures, no lint errors, a built debug APK.
- [x] Run `adb devices`; read power/keyguard without changing lock settings. If unlocked run `python tools/verify_ui.py --device 192.168.10.149:5555` against the isolated test package only.
- [x] Request independent spec review, fix findings, then request code quality review and fix material findings.
- [x] Update documentation with actual counts, covered behaviors and any remaining device-only verification, then check `git diff --check` and review the final diff. Preserve pre-existing `.superpowers` state changes.


## Execution evidence

Final UI suite: 128 tests, zero failures/errors/skips; UI lint, AndroidTest APK and app Debug APK passed. Full isolated Android 16 run: 55 tests passed. The same real overlay-window test was then extended and rerun separately (1 passed) after fixing its service-context confirmation dialog. App-ops restored in both runs. Spec and quality reviews approved. Full detail and evidence paths are recorded in docs/plans/UI重构_验收.md.

The initial time-picker RED run had an encoding-corrupted selector and is not treated as reliable RED evidence. Feedback, draft-exit, reset-confirmation, Arabic time-format and real overlay-token regressions each produced observed failures before their respective fixes.
