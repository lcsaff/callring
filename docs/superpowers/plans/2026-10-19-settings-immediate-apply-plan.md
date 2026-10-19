# 主界面与设置即时生效实施计划（P1）

状态：可执行计划，2026-10-19。对应 spec [A](../specs/2026-10-19-compact-main-ui-design.md)（第 2 版）；事实依据 [D](../specs/2026-10-19-external-facts-verification.md) §5；AI 策略细节见 [B](../specs/2026-10-19-ai-request-policy-design.md)，更新页与签名见 [C](../specs/2026-10-19-app-update-design.md)。本计划只写步骤与验收，不含实现代码；不修改 spec、不提交、不切分支。分支：`docs/spec-split-ui-ai-release`。

范围：四个主页面公共布局、档案入口与分页、设置首页拆卡、除 AI 外设置页移除保存并逐字段即时生效、AI/录音表单收口。不涉及：思考模式枚举与请求策略（B）、更新页/下载/签名/发布（C）、记录类表单与首页免打扰弹窗的即时化（A §7.1 例外）、独立 `recording-archives` 路由去留。

依赖与串行：P1 与 P2/P3 的 Wave 1 可并行；本计划含 3 个收口步骤（S06 与 C、S13 与 P2）与 1 个跨计划文档收口（S14）。并行编排见 [并行执行计划](2026-10-19-parallel-execution-plan.md)，本文件只给 P1 内部顺序。路径缩写沿用 A §8：`ui/...`=`ui/src/main/kotlin/com/greatwall/callring/`，`ui/src/test/...`=`ui/src/test/kotlin/com/greatwall/callring/ui`，`ui/src/androidTest/...` 同理，`backend/...`=`backend/src/main/kotlin/com/greatwall/callring/`。

## 1. 前置事实与快照说明

行号均为 2026-10-19 工作区快照（本计划撰写时已逐文件复核），只用于定位；实施中符号移动后以文件+符号名为准，行号可能漂移。与 A/D 个别行相差数行不影响结论；若发现与 A 决策冲突的代码事实，先按决策实现并在实施记录“冲突”节列出，不静默改决策。

- 构建：`build.gradle:9,33` 用 compose BOM `2025.12.01`，本机解析 material3 **1.4.0**，`TopAppBar` 支持 `expandedHeight`（A §3.2、D §5.29）。
- 导航：`ui/.../app/CallringApp.kt:52` 定义四个 tab，`:70-83` `NavigationSuiteScaffold`，`:76` `testTag("tab-…")`，`:84-85` 外层 `Scaffold` 承载 `innerPadding`；宽窄布局由 `currentWindowAdaptiveInfo()` 计算（`:68-72`）。
- 标题：`ui/.../designsystem/components/SharedComponents.kt:35-73` `ScreenFrame`，TopAppBar 在 `:51-60`；四个主页面调用点 `HomeScreen.kt:25`、`CallerListScreen.kt:26`、`ConversationScreen.kt:68`、`SettingsOverviewScreen.kt:20`（经 `SettingsLayout`，`SettingsComponents.kt:32`）。`SettingsLayout` 当前无 `compactTitle` 参数。
- 档案与分页：`CallerListScreen.kt:33-34` 两个正文按钮，`:26-29` 文字按钮，`:42` `PageControls`；`FeatureFeedback.kt:32-37` `PageControls`（`pageIndex` 0 基、显示 +1）；`ReportScreen.kt` 也有调用点。`CallerListViewModel` 只注入 `api.callers::search`（`CallringApp.kt:106`）；`checkNumber` 是同步纯计算（`backend/.../application/api/CallringUiApi.kt:25`，`NumberCheck` 在 `:187-189`），`CallringActivity.kt:85` 的 `report`→Lookup 回退已存在。
- 设置保存设施：`ui/.../feature/settings/components/SettingsComponents.kt:70-75` `SaveAction`；`rememberFormBack`/`UnsavedChangesDialog` 在 `ui/.../designsystem/components/FormInteractions.kt:45-95`。实际调用保存按钮的只有 `QuietScreen.kt:50`、`ScoreSettingsScreen.kt:41`、`WorkScheduleScreen.kt:97`；对应的 `QuietViewModel`/`WorkScheduleViewModel`/`ScoreSettingsViewModel` 继承 `DraftEditor`（`SettingsViewModels.kt:15-35`），`SettingsDraftCodecs.kt` 仅被设置页使用。`AppearanceViewModel` 另有 `save()`/草稿持久化（`:74`、`:18-38`），`AppearanceScreen.kt:49` 有“重试保存”。`MyPositionScreen`、`RuntimeScreen`、`RingtoneScreen`、`CalendarScreen` 现状已无 `SaveAction`/草稿（本次以核验为主）。
- 设置首页：`SettingsOverviewScreen.kt:37-40` “录音与 AI”卡，`:44-46` “系统与支持”卡；`SettingsRoute.kt:10-100` 只分发 `SettingsPage` 子页，设置总览实际在 `CallringApp.kt:156-160` 直接调用（与 A §6/C §八“接线点在 SettingsRoute”表述有定位差异，见 §7 未决项）。
- AI/录音：`AiSettingsScreen.kt:16-55`（单列、`:28,33` 重复长说明、`:44-49` 预算、`:50` 保存、`:54` 放弃对话框）；`AiSettingsViewModel.kt:8-52` 只加载 `getAiView`（`:17`），`:54-83` 为 `ScopedRecordingSettingsViewModel`；`patchCombined`/`patchAi`/`patchRecording` 见 `backend/.../application/api/AiSettingsApi.kt:24-28`，`PatchField`/`AiSettingsPatch`/`RecordingSettingsPatch` 在 `:5,21-22`；`RecordingScreens.kt:76-77` 自动分析开关、`:85` 保存、`:89-92` 保存并返回/放弃；路由 `RecordingRoutes.kt:21-38`，`AiRoutes.kt:9-19`。`ui/src/sharedTest/.../testing/UiFakeApi.kt` 目前不实现 `aiSettings`，AI 测试以匿名子类覆盖（`AiCompatibilityUiTest.kt`、`AiCompatibilityComposeTest.kt`）。
- 既有测试基线：`ui/src/sharedTest/.../feature/settings/SettingsScreenCases.kt` 大量断言 `settings-save` 与保存/放弃语义；`AppNavigationTest.kt:56` 已断言外观页无 `settings-save`；`RecordingScreensTest.kt` 含 `callerListProvidesIndependentRecordingsEntry`、`unsavedBackRequiresExplicitDiscard…`、`saveAndReturnWaits…` 等将删除的用例。

## 2. 阶段步骤表

| # | 阶段 | 动作摘要 | 主要文件 | 验收要点 | 并行标记 |
|---|---|---|---|---|---|
| S01 | 公共布局 | 窄窗口换自定义底部导航，宽窗口保留侧边导航 | `CallringApp.kt`、导航测试 | 56dp/48dp/单层 inset/四 tab | 可并行 |
| S02 | 公共布局 | `ScreenFrame` 加 `compactTitle`，四主页面启用 | `SharedComponents.kt`、`SettingsComponents.kt`、三主页面 | 48dp/无背景色/长标题省略 | 可并行 |
| S03 | 档案 | 删两正文按钮，标题改图标按钮，删回调 | `CallerListScreen.kt`、`CallringApp.kt` | 无残留文字、≥48dp、无障碍名 | 可并行（S01 后同文件） |
| S04 | 档案 | 搜索框“查看该号码的报表”动作行 + VM 派生 `NumberCheck` | `CallerListScreen.kt`、`CallerListViewModel.kt`、`CallringApp.kt` | 显示条件 6 例、进入规范化报表 | 可并行（S03 后） |
| S05 | 分页 | `PageControls` 单页整体隐藏 | `FeatureFeedback.kt` | 0/单页隐藏，多页边界不变 | 可并行 |
| S06 | 设置首页 | 拆“录音/AI”两卡 + “检查更新”行与版本副标题 + 总览紧凑标题 | `SettingsOverviewScreen.kt`、`SettingsRoute.kt` | 拆卡、版本来自系统、QA 变体隐藏 | **需收口：与 C（M2）** |
| S07 | 即时生效 | 建立单字段即时提交/防抖/冲突刷新基座 | 设置页与 `state` 现有设施 | 防抖/非法不落库/冲突保留输入 | 可并行（先于 S08–S10） |
| S08 | 即时生效 | 免打扰页去保存与草稿，逐字段提交 | `QuietScreen.kt`、`SettingsViewModels.kt` | 改即提交、返回无对话框 | 可并行（S07 后） |
| S09 | 即时生效 | 评分 + 工作时间即时提交，数值防抖 | `ScoreSettingsScreen.kt`、`WorkScheduleScreen.kt`、VM | 防抖、非法不提交、重置仍确认 | 可并行（S07 后） |
| S10 | 即时生效 | 外观去 `save`/草稿持久化，保留合并写 | `AppearanceScreen.kt`、`AppearanceViewModel.kt` | 立即可见、无“保存”语义按钮 | 可并行（S07 后） |
| S11 | 即时生效 | 我的职位/运行状态/铃声/日历逐页核验并补断言 | 四页 + 对应 VM | 均无保存按钮，动作即 API | 可并行 |
| S12 | 即时生效 | 删除 `SaveAction`、设置页 `rememberFormBack` 分支与草稿设施 | `SettingsComponents.kt`、`FormInteractions.kt`、`SettingsDraftCodecs.kt` | grep 零残留、记录表单例外保留 | 可并行（S08–S11 后） |
| S13 | AI/录音 | 三组结构、折叠错误汇总、自动分析迁移、双快照逐字段提交、唯一思考开关 | `AiSettings*`、`Recording*`、`AiRoutes.kt`、`RecordingRoutes.kt`、`UiFakeApi.kt` | `patchCombined`/`patchAi` 断言、无保存 | **需收口：与 P2（M1）** |
| S14 | 测试文档 | 按 A §9/§10 更新测试与 README/契约/PROJECT | 测试目录、`README.md`、契约、`PROJECT.md` | 全量命令绿、旧文案零残留 | 可并行起草；**需收口：与 C（契约）** |

## 3. 每步详述

### S01 窄窗口自定义底部导航（可并行）
- 动作：窄窗口移除 `NavigationSuiteScaffold` 底部栏，改为自定义 `CallringBottomBar`（`Surface`+`Row`，内容高约 56dp；每项 `weight(1f)` + 整项可点击，语义角色为 tab，图标+文字均可触发；点击区用 `heightIn(min = 48.dp)` 与父约束保证 ≥48dp，不靠压缩内容）。`WindowInsets.navigationBars` 只在自定义组件内 `windowInsetsPadding` 一次，高度由 `Scaffold(bottomBar = …)` 承接，`NavHost` 继续用 `Scaffold` 的 `innerPadding`；宽窗口（可用宽度 ≥600dp）保留 `NavigationSuiteScaffold` 侧边导航。四 tab 顺序、`testTag("tab-<route>")` 不变；新增 `testTag("bottom-bar")` 供高度断言。
- 文件：`ui/.../app/CallringApp.kt`；`ui/src/androidTest/.../app/AppNavigationTest.kt`、`ui/src/test/.../app/ConversationNavigationTest.kt`。
- 验收：`bottom-bar` 实测高度 56dp±2dp；每个 tab 语义节点高度 ≥48dp；手势/三键导航下底部只有一层 inset；点击图标或文字都切换且选中态一致；`w900dp` 下无 `bottom-bar` 且四 tab 存在；`tab-*` 全在。失败判据同 A §3.1。
- 完成：窄/宽两形态导航均正常，导航测试改后全绿，无双重底部空白。
- 并行：可并行（Wave 1）；与 P2/P3 无文件交集。
### S02 `ScreenFrame` 紧凑标题（可并行）
- 动作：`ScreenFrame` 增加 `compactTitle: Boolean = false`；为 true 时 `TopAppBar` 传 `expandedHeight = 48.dp`，为 false 用 `TopAppBarDefaults.TopAppBarExpandedHeight`；不改 `windowInsets`（状态栏 inset 不计入 48dp）；给 TopAppBar 加 `testTag("screen-frame-topbar")` 作为测试钩子。`SettingsLayout` 透传 `compactTitle`；首页、档案、沟通传 `true`；设置总览的 `true` 在 S06（M2）处理。子页面不传，保持默认高度与返回按钮。
- 文件：`ui/.../designsystem/components/SharedComponents.kt`、`ui/.../feature/settings/components/SettingsComponents.kt`、`ui/.../feature/home/HomeScreen.kt`、`ui/.../feature/callers/CallerListScreen.kt`、`ui/.../feature/conversation/ConversationScreen.kt`；`ui/src/androidTest/.../designsystem/SharedComponentsTest.kt`。
- 验收：SharedComponentsTest 断言 `compactTitle=true` 的 topbar 高度 48dp、`false` 保持默认；长标题单行省略；无独立背景色、滚动前后高度与背景一致；子页面不启用。失败判据同 A §3.2。
- 完成：三主页面启用（设置总览待 S06），编译与新增断言绿。
- 并行：可并行；本步不动 `SettingsOverviewScreen.kt`（Wave 1 冻结）。

### S03 档案页删两入口、标题改图标（可并行）
- 动作：删除 `CallerListScreen.kt:33-34` “按号码查看报表”“通话录音”及 `onLookup`/`onRecordings` 参数；标题右侧保留职位管理与新增，改 `IconButton`，点击区 ≥48dp、`contentDescription` 为“职位管理”“新增档案”；`CallringApp.kt:110-112` 删除对应回调，保留 `onAdd`/`onCaller`/`onPositions`。`UiDestination.Lookup`、`composable("lookup")`、外部 target 与 `report`→Lookup 回退一律不动。
- 文件：`ui/.../feature/callers/CallerListScreen.kt`、`ui/.../app/CallringApp.kt`；`ui/src/test/.../app/UiDestinationTest.kt`、`CallringActivityTest.kt`、`ui/src/test/.../feature/callers/NumberLookupViewModelTest.kt`、`ui/src/androidTest/.../feature/callers/CallerScreensTest.kt`、`NumberLookupScreenTest.kt`。
- 验收：档案页正文无两个文字按钮；两图标有非空无障碍名称且点击分别进入职位管理/新增档案；`report` 无效号码仍回退 Lookup、`lookup` target 直达；grep `onLookup|onRecordings` 无残留。
- 完成：编译、深链回归与界面断言绿。
- 并行：可并行（与 S01 同改 `CallringApp.kt`，按序执行）。
### S04 搜索框有效号码报表动作行（可并行）
- 动作：`CallerListViewModel` 构造注入 `checkNumber: (String) -> NumberCheck`，暴露同步派生 `NumberCheck`（query 文本非空时计算；SavedState 恢复后同样同步派生）；`CallerListScreen` 搜索框下增加动作行“查看该号码的报表”，显示条件：查询非空、`validProfileNumber`、已加载且非 loading、`page.items.none { it.number == check.normalized }`；不改子串 contains 语义，`matchesRule` 只作副文案。点击回调 `onReport(check.normalized)`；`CallringApp` 注入 `api::checkNumber` 并接线 `open(UiDestination.Report(it))`。
- 文件：`ui/.../feature/callers/CallerListViewModel.kt`、`CallerListScreen.kt`、`ui/.../app/CallringApp.kt`；`ui/src/test/.../feature/callers/CallerListViewModelTest.kt`、`ui/src/androidTest/.../feature/callers/CallerScreensTest.kt`。
- 验收：六种显示条件各一断言（无档案有效号码显示、姓名/空查询不显示、精确档案抑制、子串命中不抑制、`matchesRule=false` 仍显示、非法/loading 不显示）；androidTest 输入 `31234` 点击后报表标题与号码为规范化号码；失败判据同 A §4.3。
- 完成：条件表测试全绿，点击进入正确报表。
- 并行：可并行（S03 后，同文件顺序）。
### S05 单页隐藏分页（可并行）
- 动作：`PageControls` 首行判断 `totalItems <= pageSize`（含 0）直接不渲染；多页布局、文案、页码 +1、首末页禁用、loading 禁用全部不变；`CallerListScreen.kt:42` 与 `ReportScreen.kt:68` 调用点不改。
- 文件：`ui/.../feature/shared/FeatureFeedback.kt`；`ui/src/test/.../feature/callers/CallerListViewModelTest.kt`、`.../feature/report/ReportViewModelTest.kt`、`ui/src/androidTest/.../feature/callers/CallerScreensTest.kt`、`.../feature/report/ReportScreenTest.kt`。
- 验收：0 条与 `pageSize` 条时页面无“上一页/下一页/第 1 页/共 N 条”任何文字；`pageSize+1` 条时出现且边界正确；两页分别验证。失败判据同 A §5。
- 完成：两页行为一致，回归绿。
- 并行：可并行；与 P2/P3 无交集。

### S06 设置首页拆卡与检查更新入口（需收口：与 C）
- 动作：`SettingsOverviewScreen.kt:37-40` 的“录音与 AI”拆为两张卡：“录音”卡一行“录音设置”→“`SettingsPage.RECORDING`”；“AI”卡一行“AI 设置”→“`SettingsPage.AI`”。“系统与支持”在运行状态行后加“检查更新”行，副标题=当前安装 `versionName`（来源由 C 定：从系统应用信息/`PackageManager` 读取，禁止后端 `SettingsSummary` 或硬编码）；`backendQa` 变体不显示该行（C 提供判定）；点击进入更新页；设置总览传 `compactTitle = true`。接线：`SettingsPage` 枚举新增 `UPDATE`（枚举在 `SettingsOverviewScreen.kt:12`），`SettingsPage.entries` 自动生成 `settings/update` 路由，`SettingsRoute.kt` 增 `SettingsPage.UPDATE` 分支组装更新页（页面实现由 C）；不新增 `UiDestination`。
- 文件：`ui/.../feature/settings/SettingsOverviewScreen.kt`、`ui/.../app/SettingsRoute.kt`（均 Wave 1 冻结，M2 单 owner 修改）；`ui/src/test/.../feature/settings/SettingsScreensJvmTest.kt`（经 `SettingsScreenCases` 影响 `ui/src/androidTest/.../feature/settings/SettingsScreensTest.kt`）。
- 验收：设置首页依次出现“录音”“AI”两卡且无合并卡；系统与支持出现“检查更新”，副标题等于系统应用信息中的版本名（设备断言，如 `0.2`）；`backendQa` 无该行；点击进入更新页；总览标题 48dp。失败判据同 A §6。
- 完成：C 的版本来源与更新页路由可用后，M2 单次提交完成本步并全绿；C 未合入时停在本步，不得硬编码版本或伪造入口。
- 并行：**需收口：与 C（M2，Wave 2）**；Wave 1 不改这两个文件。

### S07 设置即时提交基座（可并行）
- 动作：统一即时提交模式：每字段改动只构造一个字段变化的 `value`，以“最后确认快照”为 `base` 调 `api.edits.quiet/work/score/appearance(base, value)`（后端按字段 diff，保留他人写入）；复用 `MutationRunner`（`ui/.../state`）串行化；数值文本字段停止输入 500ms 或失焦且解析合法才提交，非法中间值只就地显示错误、不调 API；冲突时用服务端最新值刷新 `base` 并提示“已更新，请重新修改”，保留用户输入；页面返回即离开，无草稿回滚、无放弃对话框。实现结构可参考 `AppearanceViewModel` 的 enqueue/startWriter 与 `SnapshotViewModel.write`；若需新增共享文件，实施记录登记路径与符号。
- 文件：`ui/.../feature/settings/`（共享工具落点实施时定）、`ui/.../state/SnapshotViewModel.kt`、`ui/.../feature/settings/AppearanceViewModel.kt`（参考）；`ui/src/test/.../feature/settings/`。
- 验收：防抖单测（500ms 内多次输入只一次提交）；非法值不提交；冲突后 base 刷新、输入保留、提示出现；提交正文只含本字段 Set、其余 Keep。失败判据同 A §7.1。
- 完成：基座可被 S08–S10 直接使用，测试工具可复用。
- 并行：可并行（Wave 1，先于 S08–S10；不改冻结文件）。
### S08 免打扰即时生效（可并行）
- 动作：`QuietScreen` 删除 `SaveAction`、`rememberFormBack` 与 `canSave` 状态；模式 chip、应用于所有号码开关改即提交；自定义时段在合法变更时提交，非法/相等端点不提交并就地报错；删除“放弃未保存修改”路径。`QuietViewModel` 从 `DraftEditor` 草稿态改为快照+单字段提交（保留 `api.changes` 刷新）；`SettingsRoute.kt` 更新调用签名（S07 基座）。
- 文件：`ui/.../feature/settings/QuietScreen.kt`、`SettingsViewModels.kt`、`ui/.../app/SettingsRoute.kt`；`ui/src/test/.../feature/settings/SettingsScreensJvmTest.kt`、`ui/src/androidTest/.../feature/settings/SettingsScreensTest.kt`。
- 验收：改模式/开关立即调用一次 `edits.quiet` 且 base 正确；非法时段无提交；返回无对话框；重进/重启值保持（设备或 Fake reload 断言）；页面无保存按钮。
- 完成：无 `settings-save`，单测与仪器断言绿。
- 并行：可并行（S07 后）。
### S09 评分与工作时间即时生效（可并行）
- 动作：`ScoreSettingsScreen` 删 `SaveAction`/`rememberFormBack`；四个数值字段 500ms 防抖或失焦且合法即 `edits.score`，非法不落库并就地错误；“恢复默认评分设置”`ConfirmAction` 保留（一次性危险操作）。`WorkScheduleScreen` 同时区选择/每日时段/调休时段逐字段提交，非法时区或时段不提交；`ConfirmAction` 重置保留；两 ViewModel 去草稿态；`SettingsRoute.kt` 调用更新。
- 文件：`ui/.../feature/settings/ScoreSettingsScreen.kt`、`WorkScheduleScreen.kt`、`SettingsViewModels.kt`、`ui/.../app/SettingsRoute.kt`；`ui/src/test/.../feature/settings/WorkScheduleViewModelTest.kt`、`SettingsScreensJvmTest.kt`、`ui/src/androidTest/.../feature/settings/SettingsScreensTest.kt`。
- 验收：改一个数值防抖后只提交一次且其他字段 Keep；非法值无提交且显示错误；改工作时间窗口立即生效；重置仍弹确认；无保存按钮。
- 完成：两页即时生效，重置与边界回归绿。
- 并行：可并行（S07 后）。
### S10 外观即时生效清理（可并行）
- 动作：`AppearanceScreen` 删除 `onSave` 参数与错误旁“重试保存”，改为非保存语义的“重试”（界面不得出现“保存”字样）；`AppearanceViewModel` 删除 `save()`、`baseline`/草稿持久化与恢复（`PENDING_KEY`、`appearance-base`），保留滑杆 250ms 合并写与冲突刷新；`SettingsRoute.APPEARANCE` 调用更新。
- 文件：`ui/.../feature/settings/AppearanceScreen.kt`、`AppearanceViewModel.kt`、`ui/.../app/SettingsRoute.kt`；`ui/src/test/.../feature/settings/AppearanceViewModelTest.kt`、`ui/src/sharedTest/.../SettingsScreenCases.kt`（外观段）、`ui/src/androidTest/.../app/AppNavigationTest.kt`。
- 验收：点预设/改色立即生效；非法 hex 不提交；快速滑杆合并为一次写；失败提示并保留选择、“重试”可再提交；杀进程重启值保持（设备）；`settings-save` 不存在。
- 完成：外观页无保存语义按钮，VM 无草稿持久化。
- 并行：可并行（S07 后）。
### S11 我的职位/运行状态/铃声/日历核验（可并行）
- 动作：逐页核验并记录：`MyPositionScreen` 单选项已即时提交；`RuntimeScreen` 无保存（权限/同步/诊断均一次性操作）；`RingtoneScreen` 开关/导入/恢复/试听/停止均一次性操作，导入音频与控制音例外保留；`CalendarScreen` 同步为一次性操作。清理残留 save 参数/未用 `save()` 方法；若发现某页仍有 dirty/保存，按 S07 模式改造。补“无保存按钮”断言。
- 文件：`ui/.../feature/settings/MyPositionScreen.kt`、`RuntimeScreen.kt`、`RingtoneScreen.kt`、`CalendarScreen.kt`、`RuntimeViewModel.kt`、`RingtoneViewModel.kt`、`ui/.../app/SettingsRoute.kt`；`ui/src/androidTest/.../feature/settings/SettingsScreensTest.kt`。
- 验收：四页顶部/底部无保存类按钮，操作即调对应 API；恢复内置铃声确认、删除/同步确认仍在；`settings-save` 不存在。
- 完成：核验结论（含“无需改造”依据）写入实施记录，断言绿。
- 并行：可并行；仅核验与清理，无行为变更。
### S12 草稿与保存设施收口（可并行）
- 动作：删除 `SaveAction`（`SettingsComponents.kt:70`）及全部设置页调用；设置页不再用 `rememberFormBack`，函数保留给记录表单（`CallerEditorScreen`、`FormDialog`、`WidgetConfigurationActivity`、`HomeQuietCard`）；删除仅设置页使用的 `SettingsDraftCodecs.kt` 与 `SettingsDraftCodecsTest.kt`；`DraftEditor` 若仅剩设置页使用则删除、否则按记录表单需要保留并更新注释；先 grep 确认无生产引用再删。
- 文件：`ui/.../feature/settings/components/SettingsComponents.kt`、`ui/.../designsystem/components/FormInteractions.kt`（仅注释/范围）、`ui/.../feature/settings/SettingsDraftCodecs.kt`、`ui/src/test/.../feature/settings/SettingsDraftCodecsTest.kt`、`ui/src/test/.../state/DraftEditorTest.kt`。
- 验收：grep `SaveAction(`、`settings-save`、设置页 `rememberFormBack` 均为 0；记录类表单的确认/放弃对话框仍可用；全量测试绿。
- 完成：设置页不再有草稿/保存设施，例外清单行为不变。
- 并行：可并行（S08–S11 后）。

### S13 AI/录音表单收口（需收口：与 P2）
- 动作：(a) 三组固定为“内容分析与沟通”“录音转写”“高级设置”；删 `AiSettingsScreen.kt:28,33` 重复长说明，同类信息只留一处；高级设置默认折叠，标题右侧显示红色非法字段汇总与数量，展开后字段级错误就位，非法值不落库，恢复合法后汇总消失并提交；删除保存按钮、`dirty/baseline` 草稿态和放弃对话框，保留“本地编辑密钥”与连接测试确认。(b) 自动分析开关从 `RecordingScreens.kt:76-77` 移入“录音转写”组；录音设置页删除该开关与说明、保存/保存并返回/放弃对话框，保留录音开关、采集模式、目录授权；归档页导入/回听/重试/删除不动。(c) AI 页加载双快照（`getAiView` + `getRecordingView`）；自动分析提交一次 `patchCombined`（AI 全 `Keep`、录音仅 `automaticAnalysis=Set`）；其他 AI 字段走 `patchAi` 且其他字段 `Keep`；录音页提交对 `automaticAnalysis` 发 `Keep`。(d) 第一组只放一个“思考模式”开关（默认关），状态说明/错误码由 B 提供，A 不定义枚举；旧“高级兼容”块随 B 迁移移除。(e) 更新 `UiFakeApi`/测试替身支持双快照与 patch 记录；删除仅测试使用的 `RecordingSettingsViewModel`（先确认无生产引用）。
- 文件：`ui/.../feature/ai/AiSettingsScreen.kt`、`AiSettingsViewModel.kt`、`ui/.../feature/recording/RecordingScreens.kt`、`RecordingViewModels.kt`、`ui/.../app/AiRoutes.kt`、`RecordingRoutes.kt`、`ui/src/sharedTest/.../testing/UiFakeApi.kt`；`ui/src/test/.../app/AiCompatibilityUiTest.kt`、`AiCompatibilityComposeTest.kt`、新增 `ui/src/test/.../feature/ai/`（AI VM 测试）、`.../feature/recording/RecordingScreensTest.kt`、`RecordingViewModelsTest.kt`、`RecordingArchiveListTest.kt`、`ui/src/androidTest/.../feature/settings/SettingsScreensTest.kt`。
- 验收：三组顺序与字段齐全；自动分析只在“录音转写”组、思考模式只在第一组；无保存按钮；折叠非法预算 → 无 patch 且标题红色汇总，展开可定位；自动分析 → 一次 `patchCombined`（AI 全 Keep、录音仅 Set）；地址/模型/预算 → `patchAi` 其余 Keep；录音页提交自动分析为 Keep；两页值一致；无供应商/合同/参数名文案。失败判据同 A §7.2–§7.6。
- 完成：B 的枚举/错误码合入后，M1 单次提交完成本步并全绿。
- 并行：**需收口：与 P2（M1，Wave 2）**；`AiSettings*`、`Recording*`、`UiFakeApi` 在 Wave 1 冻结。

### S14 测试与文档同步（可并行起草）
- 动作：按 A §9 更新/新增测试：删除保存/草稿/放弃断言，新增即时生效、防抖、冲突刷新、拆卡与检查更新、导航高度、`compactTitle`、单页分页、号码报表入口；按 A §10 更新 `README.md` 第 15、69 行（改为搜索框入口与“报表→逐次明细→录音与分析”）、`docs/plans/UI业务API契约_v1.md` 第 43–44、214、282–284、706–716 行、`PROJECT.md` 第 26、28 行（版本行按内容定位，文件混合换行，勿按行号硬改）。
- 文件：`ui/src/test/...`、`ui/src/androidTest/...`（A §9 清单）；`README.md`、`docs/plans/UI业务API契约_v1.md`、`PROJECT.md`。
- 验收：统一命令全绿且测试数不少于基线（记录三处 `test-results` 的 tests/failures/errors/skipped，空测试集不算通过）；grep 旧文案（正文“按号码查看报表”“档案 → 通话录音”“保存设置”）无残留；文档与新行为一致。
- 完成：测试与文档随各步同步提交，证据留档。
- 并行：可并行起草；契约文件与 P3 的更新页条目**需收口：与 C**后再提交。

## 4. 共享文件收口清单

| 共享文件 | Wave 1 | 收口 owner/步骤 | 约束 |
|---|---|---|---|
| `ui/.../feature/ai/AiSettingsScreen.kt`、`AiSettingsViewModel.kt` | 冻结 | M1（S13） | P1 提供三组结构、双快照、逐字段提交；P2 提供思考模式开关/错误码；Wave 1 不得提交 |
| `ui/.../feature/recording/RecordingScreens.kt`、`RecordingViewModels.kt` | 冻结 | M1（S13） | 自动分析迁移与保存/草稿删除由 M1 一次完成 |
| `ui/src/sharedTest/.../testing/UiFakeApi.kt` | 冻结 | M4（S13 输入） | 三方字段合并由收口 owner 一次提交；Wave 1 各 track 用临时 fake |
| `ui/.../feature/settings/SettingsOverviewScreen.kt`、`ui/.../app/SettingsRoute.kt` | 冻结 | M2（S06） | P1 提供拆卡/入口；C 提供版本来源与更新路由；Wave 1 不得提交 |
| `ui/.../feature/settings/components/SettingsComponents.kt`、`FeatureFeedback.kt`、`SharedComponents.kt`、`CallringApp.kt`、`CallerList*` | P1 | 无 | P2/P3 不改；P1 内部按 S01→S05 顺序 |
| `backend/.../application/api/CallringUiApi.kt`、`UiEditsApi.kt`、`AiSettingsApi.kt` | P2/P3 | P2/P3 | P1 只使用现有签名，不新增字段 |

## 5. 验收命令

```powershell
.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug
```

- 设备仪器测试在设备可用时执行：`:ui:connectedDebugAndroidTest`，覆盖 A §9 的 androidTest 清单（导航高度/48dp、图标语义、动作行、单页分页、Lookup、拆卡、紧凑标题）。
- `backend` 是 `java-library`，测试任务是 `:backend:test`；不得写成 `testDebugUnitTest`。
- Debug 构建与单测通过**不代表** Release/更新链路通过：签名、真实下载、覆盖升级由 C 单独验收。
- 任一步失败保留输出与截图/日志，不把“编译通过”记作步骤完成。

## 6. 风险与回滚

- **双重底部 inset / 导航高度不达标**：先在 `CallringApp` 保留 `NavigationSuiteScaffold` 分支作为回退路径，仅替换窄窗口 `bottomBar`；若仪器断言失败，回退该组合函数，不动 NavHost 路由。
- **即时提交写放大**：文本/滑杆高频提交用 500ms（滑杆 250ms）合并与 `MutationRunner` 串行；若仍抖动，回退为失焦提交，不恢复保存按钮。
- **冲突刷新覆盖输入**：只刷新 `base` 与服务端最新值，输入与错误保留；若实现困难，先保证“提示 + 不静默回滚”，再补自动 rebase。
- **AI/录音收口冲突**：Wave 1 冻结五个共享文件；M1 单 owner 一次提交，失败 `git revert` 收口提交，不 force-push；不把临时 fake 当最终实现。
- **设置入口依赖 C**：C 未提供版本来源/更新路由时 S06 保持未完成，不得硬编码 `0.2` 或伪造页面。
- **深链回归**：S03 只删 UI 入口，不改 `UiDestination`/`CallringActivity`；若深链测试失败，只回退档案页 UI 提交，不删路由。
- 行号漂移与 spec 定位差异：按符号名执行；实施记录“冲突”节列出（当前已知见 §7）。

## 7. 未决项（需 Lead/评审裁决）

1. **定位差异（已解决）**：A §6/C §八 已采用“新增 `SettingsPage.UPDATE` 子页”方案：枚举加在 `SettingsOverviewScreen.kt:12`，`SettingsPage.entries` 自动生成 `settings/update` 路由，`SettingsRoute.kt` 加 UPDATE 分支，设置总览复用既有 `onNavigate`；不需要新增 `UiDestination` 或改 `CallringApp` 路由。S06 按此实施。
2. **版本来源**：入口副标题必须来自系统应用信息（`PackageManager`），具体 API 与 `backendQa` 判定由 C 定；S06 在 C 合入前阻塞。
3. **“已即时”页面**：`MyPosition/Runtime/Ringtone/Calendar` 现状已无 `SaveAction`/草稿，S11 按“核验+清理”处理；若评审要求统一抽象或改动，请裁决。
4. **外观“重试保存”**：与“无保存语义按钮”冲突，S10 计划改文案为“重试”；如要求保留原文案需评审确认。
5. **思考模式与 AI 测试目录**：枚举/矩阵/错误码由 B 定义，A 只定位置，S13 需 B 的 M1 依赖步骤先合入；A §9 写作 `ui/src/test/.../feature/ai/`，当前无该目录，S13 新增时按该路径登记，若 P2 已另建目录以 P2 为准。

## 8. 完成定义（DoD）

- S01–S14 全部完成；S06、S13、S14 的收口依赖已合入并在实施记录注明收口提交。
- 设置页（除 AI 外）无 `SaveAction`/`settings-save`/草稿回滚，逐字段改动即时生效；例外清单（职位/请假对话框、密钥确认、连接测试、删除确认、导入音频、恢复铃声）保留确认。
- 统一验收命令全绿且测试数不少于基线；设备可用时 `:ui:connectedDebugAndroidTest` 通过并留证据。
- README、`docs/plans/UI业务API契约_v1.md`、`PROJECT.md` 同步完成且旧文案 grep 为零；共享文件收口清单按 owner 执行，无跨 track 未合并改动；spec 未修改，冲突与未决项已列入实施记录。
