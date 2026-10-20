# 生产文件归属与迁移清单

日期：2026-10-20。源码基线：`fa886a431e44424fe9b483380ea97f6d792c003b`。

关联：[设计方案](2026-10-20-layer-boundaries-design.md)、[实施计划](../plans/2026-10-20-layer-boundaries-plan.md)。本清单是目标归属，不表示已移动文件；本轮不修改代码。

## 使用规则

- 范围是 app/ui/backend 的 src/main 下全部 Kotlin/Java 文件，不包含生成代码、资源、测试、debug/backendQa。新增 contracts 及其他目标文件见实施计划第 2 节。
- B/A/U/C 根路径与实施计划第 2 节一致；“原 FQCN”表示完整组件类名维持，系统登记组件可在原包保留薄适配壳。
- 同路径也需审查契约 import、可见性和真实依赖，不为了移动而移动。普通目标包与目录一致。
- 多目标项先按内容拆分，再删除原混合实现；表中 `*` 表示明确的职责组，业务服务目标文件名按实施计划新建清单执行。
- 后续执行逐行勾选，拆分项回填实际文件与验证证据；测试、runner、manifest/调度/资源引用同步审查。
- 纯移动与行为改变分开提交，不以长期转发包装保留两套业务实现。

基线覆盖：共 **227** 个生产文件：app 70、ui 87、backend 70。所有实施状态均未完成。

## app

| 状态 | 现有源码 | 目标归属/拆分要求 | 对应任务 |
|---|---|---|---|
| [ ] | `app/src/main/java/com/greatwall/callring/CallStateSnapshot.java` | A/platform/calling/CallStateSnapshot.java | T08/T13 |
| [ ] | `app/src/main/java/com/greatwall/callring/PreparationGate.java` | A/platform/audio/PreparationGate.java | T08/T13 |
| [ ] | `app/src/main/java/com/greatwall/callring/RingPlaybackService.java` | 原 FQCN 组件壳；逻辑归 A/entrypoint/calling/PlaybackServiceController.kt | T08/T13 |
| [ ] | `app/src/main/java/com/greatwall/callring/SessionGate.java` | A/platform/calling/SessionGate.java | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/bootstrap/BackendConfiguration.kt` | A/bootstrap/BackendConfiguration.kt | T05/T10 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/bootstrap/BackendGraph.kt` | A/bootstrap/BackendGraph.kt；拆出 CoreAssembly/RecordingAssembly/ConversationAssembly/UiBridgeAssembly | T05/T10 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/bootstrap/BackendProvider.kt` | A/bootstrap/BackendProvider.kt | T05/T10 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/bootstrap/CompleteSnapshotAssembly.kt` | 接线归 A/bootstrap/ConversationAssembly.kt；业务快照投影归 B/application/conversation/query | T09/T10 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/bootstrap/LazyBackendStore.kt` | A/bootstrap/StorageResources.kt；删除原包装和具体类型强转 | T05 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/bootstrap/ScopedQueryAssembly.kt` | 接线归 A/bootstrap/ConversationAssembly.kt；源投影归 A/data/query 与 B/application/conversation/query | T09/T10 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/bootstrap/ValueScopedReadSource.kt` | A/data/query/ValueScopedReadSource.kt | T09 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/CalendarSyncJobService.kt` | 原 FQCN 组件壳；逻辑归 A/entrypoint/jobs/CalendarSyncJobController.kt | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/CallCaptureService.kt` | 原 FQCN 组件壳；逻辑归 A/entrypoint/recording/CaptureServiceController.kt | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/CallringApplication.kt` | A/CallringApplication.kt（保持系统组件身份） | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/data/CallringDatabase.kt` | A/data/database/CallringDatabase.kt | T05–T09 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/data/CallStore.kt` | A/data/calling/SqliteCallStore.kt（现有 CallStore 按端口职责改造） | T05–T09 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/data/LegacyValueCodec.kt` | A/data/codec/LegacyValueCodec.kt | T05–T09 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/data/ProfileStore.kt` | A/data/callers/SqliteCallerStore.kt（现有 ProfileStore 按端口职责改造） | T05–T09 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/data/ScheduleStore.kt` | A/data/settings/ScheduleStore.kt | T05–T09 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/data/SettingsStore.kt` | A/data/settings/SettingsStore.kt | T05–T09 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/data/SnapshotPreflight.kt` | A/data/database/SnapshotPreflight.kt | T05–T09 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/data/SqliteBackendStore.kt` | 拆为 A/data/database/{SqliteCoreUnitOfWork,SqliteReadSession,SqliteWriteSession}.kt；删除 StorageSession 全量协议 | T05–T10/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/data/SqliteScopedReadAdapter.kt` | A/data/query/SqliteScopedReadAdapter.kt | T05–T09 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/data/SqlSupport.kt` | A/data/database/SqlSupport.kt | T05–T09 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/data/StorageMigration.kt` | A/data/database/StorageMigration.kt | T05–T09 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/data/UnicodeSearchPattern.kt` | A/data/query/UnicodeSearchPattern.kt | T05–T09 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/data/WorkConfigCodec.kt` | A/data/codec/WorkConfigCodec.kt | T05–T09 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/ai/AiSettingsService.kt` | B/application/recording/AiSettingsService.kt；权限/目录观察留 A/platform/recording，配置存储归 A/data/recording | T10 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/ai/AndroidAudioTranscriber.kt` | A/platform/recording/AndroidAudioTranscriber.kt | T09/T10/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/ai/AndroidRecordingRuntime.kt` | 业务 facade 归 B/application/recording/RecordingService.kt；设备资源归 A/platform/recording/AndroidRecordingRuntime.kt；接线归 A/bootstrap/RecordingAssembly.kt | T10 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/ai/AndroidRecordingStore.kt` | A/data/recording/AndroidRecordingStore.kt | T09/T10/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/ai/ArchiveProjectionIndex.kt` | A/data/recording/ArchiveProjectionIndex.kt | T09/T10/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/ai/ArchiveStreamParser.kt` | A/platform/ai/ArchiveStreamParser.kt | T09/T10/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/ai/CompatibleAiClient.kt` | A/platform/ai/CompatibleAiClient.kt | T09/T10/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/ai/CompatibleConversationClient.kt` | A/platform/ai/CompatibleConversationClient.kt | T09/T10/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/ai/ConversationJson.kt` | 协议归 A/platform/ai/ConversationJson.kt；持久化解析提取为 A/data/codec/PersistedJsonCodec.kt | T09/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/ai/DirectoryScopedReadSource.kt` | A/platform/recording/DirectoryScopedReadSource.kt | T09/T10/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/ai/PcmWaveChunks.kt` | A/platform/recording/PcmWaveChunks.kt | T09/T10/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/ai/RecordingScopedReadAdapter.kt` | A/data/query/RecordingScopedReadAdapter.kt | T09/T10/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/ai/SystemRecordingSource.kt` | A/platform/recording/SystemRecordingSource.kt | T09/T10/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/AndroidAudioController.kt` | A/platform/audio/AndroidAudioController.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/AndroidCallLogSource.kt` | A/platform/calling/AndroidCallLogSource.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/AndroidDiagnosticsStore.kt` | A/platform/diagnostics/AndroidDiagnosticsStore.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/AndroidDocumentStore.kt` | A/platform/document/AndroidDocumentStore.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/AndroidPlaybackCommands.kt` | A/platform/audio/AndroidPlaybackCommands.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/AndroidPreferences.kt` | A/data/preferences/AndroidPreferences.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/AndroidRuntimeProbe.kt` | A/platform/runtime/AndroidRuntimeProbe.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/AndroidSyncScheduler.kt` | A/platform/scheduler/AndroidSyncScheduler.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/CalendarRefreshPolicy.kt` | A/platform/calendar/CalendarRefreshPolicy.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/callers/NumberDeletionService.kt` | B/application/callers/NumberDeletionService.kt；文件能力归 A/platform/recording | T10 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/calling/AndroidCallEnvironment.kt` | A/platform/calling/AndroidCallEnvironment.kt | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/calling/AndroidCallWorkLease.kt` | A/platform/calling/AndroidCallWorkLease.kt | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/calling/IncomingCallCoordinator.kt` | A/platform/calling/IncomingCallCoordinator.kt | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/calling/ScreeningController.kt` | A/platform/calling/ScreeningController.kt | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/HolidayCalendarSource.kt` | A/platform/calendar/HolidayCalendarSource.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/JobRuns.kt` | A/platform/scheduler/JobRuns.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/PlatformSafety.kt` | A/platform/runtime/PlatformSafety.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/PlaybackCommandDispatch.kt` | A/platform/audio/PlaybackCommandDispatch.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/PlaybackStateTracker.kt` | A/platform/audio/PlaybackStateTracker.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/StrictJson.kt` | A/data/codec/StrictJson.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/UiNotificationEntryPoints.kt` | A/platform/runtime/UiNotificationEntryPoints.kt | T06/T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/update/AndroidAppUpdate.kt` | A/platform/update/AndroidAppUpdate.kt | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/update/AndroidUpdateAdapters.kt` | A/platform/update/AndroidUpdateAdapters.kt | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/update/GitHubReleaseClient.kt` | A/platform/update/GitHubReleaseClient.kt | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/update/UpdateContract.kt` | A/platform/update/UpdateContract.kt | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/platform/update/UpdateStateStore.kt` | A/platform/update/UpdateStateStore.kt | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/RecordingJobService.kt` | 原 FQCN 组件壳；逻辑归 A/entrypoint/jobs/RecordingJobController.kt | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/RuleScreeningService.kt` | 原 FQCN 组件壳；逻辑归 A/entrypoint/calling/ScreeningServiceController.kt | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/RuntimeReceiver.kt` | 原 FQCN 组件壳；逻辑归 A/entrypoint/runtime/RuntimeEventController.kt | T08/T13 |
| [ ] | `app/src/main/kotlin/com/greatwall/callring/SyncJobService.kt` | 原 FQCN 组件壳；逻辑归 A/entrypoint/jobs/CallLogSyncJobController.kt | T08/T13 |

## ui

| 状态 | 现有源码 | 目标归属/拆分要求 | 对应任务 |
|---|---|---|---|
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/app/AiRoutes.kt` | U/app/AiRoutes.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/app/CallringActivity.kt` | U/app/CallringActivity.kt（保持系统组件 FQCN） | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/app/CallringApp.kt` | U/app/CallringApp.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/app/DocumentPickers.kt` | U/app/DocumentPickers.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/app/ForegroundActionHost.kt` | U/app/ForegroundActionHost.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/app/RecordingRoutes.kt` | U/app/RecordingRoutes.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/app/RouteSupport.kt` | U/app/RouteSupport.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/app/SettingsRoute.kt` | U/app/SettingsRoute.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/app/ThemeWindowSupport.kt` | U/app/ThemeWindowSupport.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/app/UiApiOwner.kt` | U/bridge/UiApiOwner.kt | T03/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/app/UiDestination.kt` | U/app/UiDestination.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/app/UiNavigationState.kt` | U/app/UiNavigationState.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/app/UiNotifications.kt` | U/bridge/UiNotifications.kt | T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/designsystem/components/BrandButton.kt` | U/designsystem/components/BrandButton.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/designsystem/components/DateTimeInputs.kt` | U/designsystem/components/DateTimeInputs.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/designsystem/components/FormInteractions.kt` | 通用交互留 U/designsystem/components/FormInteractions.kt；业务状态适配归 U/feature/shared/FormFeedback.kt | T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/designsystem/components/InteractionFeedback.kt` | U/designsystem/components/InteractionFeedback.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/designsystem/components/SharedComponents.kt` | 纯视觉留 U/designsystem/components/SharedComponents.kt；业务实体组件归 U/feature/shared/CallerComponents.kt | T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/designsystem/ScorePalette.kt` | U/designsystem/ScorePalette.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/designsystem/Theme.kt` | U/designsystem/Theme.kt 的视觉 ThemeSpec/颜色逻辑；AppearanceSettings 映射归 U/bridge/AppearanceThemeMapper.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/ai/AiSettingsScreen.kt` | U/feature/ai/AiSettingsScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/ai/AiSettingsViewModel.kt` | U/feature/ai/AiSettingsViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/ai/CredentialDialog.kt` | U/feature/ai/CredentialDialog.kt（复制预览改读 copyToAnalysisProfile，删除厂商解析） | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/callers/CallerEditorForm.kt` | U/feature/callers/CallerEditorForm.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/callers/CallerEditorScreen.kt` | U/feature/callers/CallerEditorScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/callers/CallerEditorViewModel.kt` | U/feature/callers/CallerEditorViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/callers/CallerListScreen.kt` | U/feature/callers/CallerListScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/callers/CallerListViewModel.kt` | U/feature/callers/CallerListViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/callers/NumberDeletionDialogs.kt` | U/feature/callers/NumberDeletionDialogs.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/callers/NumberLookupScreen.kt` | U/feature/callers/NumberLookupScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/callers/NumberLookupViewModel.kt` | U/feature/callers/NumberLookupViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/conversation/ConversationScreen.kt` | U/feature/conversation/ConversationScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/conversation/ConversationViewModel.kt` | U/feature/conversation/ConversationViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/home/HomeQuietCard.kt` | U/feature/home/HomeQuietCard.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/home/HomeQuietViewModel.kt` | U/feature/home/HomeQuietViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/home/HomeScreen.kt` | U/feature/home/HomeScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/home/HomeViewModel.kt` | U/feature/home/HomeViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/recording/RecordingScreens.kt` | 拆至 U/feature/recording/ 下各录音页面独立文件 | T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/recording/RecordingViewModels.kt` | 拆至 U/feature/recording/ 下各录音 ViewModel 独立文件 | T10/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/report/ReportScreen.kt` | U/feature/report/ReportScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/report/ReportViewModel.kt` | U/feature/report/ReportViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/AppearanceScreen.kt` | U/feature/settings/AppearanceScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/AppearanceViewModel.kt` | U/feature/settings/AppearanceViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/CalendarScreen.kt` | U/feature/settings/CalendarScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/components/SettingsComponents.kt` | U/feature/settings/components/SettingsComponents.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/LeavesScreen.kt` | U/feature/settings/LeavesScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/MyPositionScreen.kt` | U/feature/settings/MyPositionScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/PositionsScreen.kt` | U/feature/settings/PositionsScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/QuietScreen.kt` | U/feature/settings/QuietScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/RingtoneScreen.kt` | U/feature/settings/RingtoneScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/RingtoneViewModel.kt` | U/feature/settings/RingtoneViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/RuntimeScreen.kt` | U/feature/settings/RuntimeScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/RuntimeViewModel.kt` | U/feature/settings/RuntimeViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/ScoreSettingsScreen.kt` | U/feature/settings/ScoreSettingsScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/SettingsDraftCodecs.kt` | U/feature/settings/SettingsDraftCodecs.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/SettingsOverviewScreen.kt` | U/feature/settings/SettingsOverviewScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/SettingsViewModels.kt` | 拆至 U/feature/settings/ 下各设置 ViewModel 独立文件 | T06/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/UnifiedWorkSchedule.kt` | U/feature/settings/UnifiedWorkSchedule.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/WorkScheduleScreen.kt` | U/feature/settings/WorkScheduleScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/shared/FeatureFeedback.kt` | U/feature/shared/FeatureFeedback.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/update/AppUpdateScreen.kt` | U/feature/update/AppUpdateScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/feature/update/AppUpdateViewModel.kt` | U/feature/update/AppUpdateViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/format/UiFormat.kt` | U/format/UiFormat.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/format/UiLabels.kt` | U/format/UiLabels.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/overlay/AndroidIncomingCallUiSink.kt` | U/overlay/AndroidIncomingCallUiSink.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/overlay/CallringOverlayService.kt` | U/overlay/CallringOverlayService.kt（保持系统组件 FQCN） | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/overlay/OverlayController.kt` | U/overlay/OverlayController.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/overlay/OverlayDialogHost.kt` | U/overlay/OverlayDialogHost.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/overlay/OverlayRoute.kt` | U/overlay/OverlayRoute.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/overlay/OverlayScreen.kt` | U/overlay/OverlayScreen.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/overlay/OverlaySessions.kt` | U/overlay/OverlaySessions.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/overlay/OverlayViewModel.kt` | U/overlay/OverlayViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/overlay/OverlayVisibility.kt` | U/overlay/OverlayVisibility.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/overlay/OverlayWindow.kt` | U/overlay/OverlayWindow.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/overlay/UiOverlayOwner.kt` | U/overlay/UiOverlayOwner.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/overlay/WindowPlacement.kt` | U/overlay/WindowPlacement.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/state/AsyncState.kt` | U/state/AsyncState.kt；affects 观察职责归 U/state/ChangeObserver.kt | T11 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/state/ImmediateEditor.kt` | U/state/ImmediateEditor.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/state/SnapshotViewModel.kt` | U/state/SnapshotViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/state/UiViewModel.kt` | U/state/UiViewModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/widget/CallringWidget.kt` | U/widget/CallringWidget.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/widget/CallringWidgetReceiver.kt` | U/widget/CallringWidgetReceiver.kt（保持系统组件 FQCN） | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/widget/UiWidgetRefresh.kt` | U/widget/UiWidgetRefresh.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/widget/WidgetConfigurationActivity.kt` | U/widget/WidgetConfigurationActivity.kt（保持系统组件 FQCN） | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/widget/WidgetConfigurationModel.kt` | U/widget/WidgetConfigurationModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/widget/WidgetModel.kt` | U/widget/WidgetModel.kt | T03/T11/T13 |
| [ ] | `ui/src/main/kotlin/com/greatwall/callring/ui/widget/WidgetUpdates.kt` | U/widget/WidgetUpdates.kt；内含 WidgetTimeReceiver 保持既有 FQCN | T11/T13 |

## backend

| 状态 | 现有源码 | 目标归属/拆分要求 | 对应任务 |
|---|---|---|---|
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/api/AiJson.kt` | C/ai/AiJson.kt；Unavailable 实现归 backend 对应能力 | T03 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/api/AiQueryApi.kt` | C/ai/AiQueryApi.kt；Unavailable 实现归 backend 对应能力 | T03 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/api/AiSettingsApi.kt` | C/ai/AiSettingsApi.kt；Unavailable 实现归 backend 对应能力 | T03 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/api/AiSnapshotApi.kt` | C/ai/AiSnapshotApi.kt；Unavailable 实现归 backend 对应能力 | T03 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/api/AppUpdateApi.kt` | C/runtime/AppUpdateApi.kt；Unavailable 实现归 backend 对应能力 | T03 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/api/CallringUiApi.kt` | C/CallringApi.kt 与 C/{common,callers,settings,report,runtime,widget,change,command} 下分域声明；Unavailable 实现归 backend 对应能力 | T03 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/api/ConversationApi.kt` | C/ai/ConversationApi.kt；Unavailable 实现归 backend 对应能力 | T03 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/api/ConversationCompatibility.kt` | 公开兼容/失败/usage 类型归 C/ai；profile 解析归 B/domain/ai/ConversationPolicy.kt | T03/T04 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/api/NumberDeletionApi.kt` | C/callers/NumberDeletionApi.kt；Unavailable 实现归 backend 对应能力 | T03 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/api/RecordingApi.kt` | 公开定义归 C/recording/RecordingApi.kt；内部含密钥配置/校验归 B/domain/ai；Unavailable 归 B/application/recording | T03/T04/T10 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/api/ThinkingPolicy.kt` | 公开模式/支持类型归 C/ai；解析/厂商策略归 B/domain/ai/ThinkingPolicy.kt | T03/T04 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/api/UiEditsApi.kt` | C/settings/UiEditsApi.kt；Unavailable 实现归 backend 对应能力 | T03 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/BackendContext.kt` | 拆解至 B/application/calling/{IncomingCallService,CallLogImportService,DecisionQueryService}.kt 与 coordination/{UseCaseRunner,CommandCommitter,DecisionStateHolder}.kt；删除原类 | T04–T10 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/BusinessRevisionCoordinator.kt` | B/application/coordination/BusinessRevisionCoordinator.kt | T06–T10/T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/BusinessScopes.kt` | B/application/coordination/{ConflictScope,ChangeImpact}.kt；删除 coreScopeValues 全量前后比较 | T05–T10 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/CallerProjection.kt` | B/application/callers/CallerProjection.kt | T06–T10/T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/CallersService.kt` | B/application/callers/{CallerQueryService,CallerCommandService}.kt | T07 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/CallringBackend.kt` | B/application/CallringBackend.kt（只组合业务能力） | T05–T10 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/AiSnapshotService.kt` | B/application/conversation/query/AiSnapshotService.kt | T09 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/CapabilityCatalog.kt` | B/application/conversation/operation/CapabilityCatalog.kt | T12 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/ConfirmationTargets.kt` | B/application/conversation/execution/ConfirmationTargets.kt | T12 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/ContextAssembler.kt` | B/application/conversation/context/ContextAssembler.kt | T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/ContextBudget.kt` | B/application/conversation/context/ContextBudget.kt | T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/ConversationService.kt` | B/application/conversation/execution/ConversationService.kt | T12 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/DirectoryEnumerator.kt` | B/application/conversation/query/DirectoryEnumerator.kt | T09 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/ForegroundActionService.kt` | B/application/conversation/foreground/ForegroundActionService.kt | T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/ForegroundResourceRegistry.kt` | B/application/conversation/foreground/ForegroundResourceRegistry.kt | T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/OperationContract.kt` | B/application/conversation/operation/OperationContract.kt | T12 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/OperationScopePlanner.kt` | B/application/conversation/execution/OperationScopePlanner.kt | T12 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/QueryService.kt` | B/application/conversation/query/QueryService.kt | T09 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/ReceiptVerifier.kt` | B/application/conversation/execution/ReceiptVerifier.kt | T12 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/ScopedTextFingerprint.kt` | B/application/conversation/query/ScopedTextFingerprint.kt | T09 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/ScopedToolReader.kt` | B/application/conversation/query/ScopedToolReader.kt | T09 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/SecretRedactor.kt` | B/application/conversation/context/SecretRedactor.kt | T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/StepLedger.kt` | B/application/conversation/execution/StepLedger.kt | T12 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/TaskPlanner.kt` | B/application/conversation/execution/TaskPlanner.kt | T12 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/ToolBatchEngine.kt` | B/application/conversation/execution/ToolBatchEngine.kt | T12 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/ToolExecutor.kt` | B/application/conversation/execution/ToolExecutor.kt | T12 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/ToolRegistry.kt` | B/application/conversation/operation/ToolRegistry.kt | T12 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/conversation/TurnBudget.kt` | B/application/conversation/context/TurnBudget.kt | T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/CoreNumberDeletionApi.kt` | 合并至 B/application/callers/NumberDeletionService.kt 核心步骤，不保留重复 facade | T10 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/CoreSnapshotProjection.kt` | B/application/conversation/query/CoreSnapshotProjection.kt | T09 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/HomeService.kt` | B/application/home/HomeService.kt | T06–T10/T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/ports/BackendPorts.kt` | 按能力拆至 B/application/ports/store/* 与 platform/*；BackendFailure 独立归错误边界 | T05/T09 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/ports/ConversationPorts.kt` | B/application/ports/platform/ConversationPorts.kt | T05/T09 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/ports/ScopedReadPort.kt` | B/application/ports/query/ScopedReadPort.kt | T05/T09 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/QuerySupport.kt` | B/application/mapping/PageMappings.kt、application/report/CsvEncoding.kt、domain/model/TimeMath.kt | T04/T09 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/QuietControls.kt` | 规则归 B/domain/calling/QuietControls.kt；提交归 B/application/settings/QuietSettingsService.kt | T04/T06 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/ReadbackDocumentStore.kt` | B/application/runtime/ReadbackDocumentStore.kt | T06–T10/T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/RecordingAssociations.kt` | B/domain/calling/RecordingAssociations.kt | T04/T10 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/RecordingPorts.kt` | 拆至 B/application/ports/recording/{RecordingStore,AiClient}.kt | T05/T10 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/RecordingWorkflow.kt` | B/application/recording/RecordingWorkflow.kt | T06–T10/T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/ReportCalculator.kt` | 纯计算归 B/domain/report/ReportCalculator.kt；API 投影归 B/application/report/ReportProjection.kt | T04/T09 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/ReportsService.kt` | B/application/report/ReportsService.kt | T06–T10/T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/RingtoneService.kt` | B/application/runtime/RingtoneService.kt | T06–T10/T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/RuntimeService.kt` | B/application/runtime/RuntimeService.kt | T06–T10/T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/SafeProjection.kt` | B/application/conversation/query/SafeProjection.kt | T09 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/SettingsService.kt` | B/application/settings/{QuietSettingsService,ScoreSettingsService,StatisticsSettingsService,WorkScheduleService,CalendarService,LeaveService,PositionService,AppearanceService}.kt | T06 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/SettingsValidation.kt` | 纯校验归 B/domain/model/SettingsValidation.kt；配置投影归 B/application/settings/SettingsMappings.kt | T04/T06 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/SharedBusinessCommands.kt` | B/application/recording/IdentityAdoptionService.kt；其他分支并入对应业务服务 | T10 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/SnapshotCollections.kt` | B/application/mapping/SnapshotCollections.kt | T06–T10/T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/StreamingReportAccumulator.kt` | B/domain/report/StreamingReportAccumulator.kt（唯一业务公式） | T04/T09 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/UiEditsService.kt` | 按编辑目标并入 B/application/callers/CallerCommandService.kt 与 settings/*Service.kt；删除宽上下文编辑包装 | T06/T07 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/application/WidgetBindingsService.kt` | B/application/widget/WidgetBindingsService.kt | T06–T10/T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/domain/BusinessRecords.kt` | B/domain/model/BusinessRecords.kt（消除 API 依赖） | T04/T08 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/domain/BusinessState.kt` | 拆为 B/domain/model/DecisionInputs.kt 及完整快照专用事实模型；普通业务全状态容器退场 | T04–T10/T13 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/domain/CallCorrelation.kt` | B/domain/calling/CallCorrelation.kt（消除 API 依赖） | T04/T08 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/domain/DecisionSnapshot.kt` | B/domain/calling/DecisionSnapshot.kt（消除 API 依赖） | T04/T08 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/domain/SystemCallRecord.kt` | B/domain/model/SystemCallRecord.kt（消除 API 依赖） | T04/T08 |
| [ ] | `backend/src/main/kotlin/com/greatwall/callring/domain/WorkClassifier.kt` | B/domain/calling/WorkClassifier.kt（消除 API 依赖） | T04/T08 |

## 配置、资源、测试和工具同步范围

| 范围 | 动作 | 任务 |
|---|---|---|
| settings.gradle / 四模块 build.gradle | 新增 contracts、固定依赖；保持版本与工具链 | T03 |
| app/ui manifests 与 res/xml | 保留组件 FQCN、provider authority；同步必要资源引用 | T13 |
| app debug/backendQa/androidTest | 新装配和端口，保持隔离身份与 runner | T05–T15 |
| ui sharedTest/test/androidTest | 跟随状态、组件、ViewModel，保持共享测试方式 | T03/T11/T13 |
| 全部现有测试 | 跟随生产职责更新包/夹具/端口，保留有效业务断言 | 全阶段 |
| tools/check_backend_boundaries.py 及测试 | 当前契约兼容替换单文件相等，扩展分层负例 | T03/T14 |
| 新 tools/check_architecture.py 及测试 | 模块/包、全量协议、具体强转、数字语义检查 | T03/T14 |
| tools/verify_backend.py、verify_ui.py、verify_ai_device.py | 新模块、测试类路径与实际验证口径同步 | T14/T15 |
| .github/workflows/build.yml、release.yml | 接入 contracts/架构检查，保留签名发布语义 | T14 |
| README.md、PROJECT.md、docs/contracts 当前说明 | 更新最终结构和命令，保留历史审查证据 | T13/T15 |
