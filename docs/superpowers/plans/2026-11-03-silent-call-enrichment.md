# 来电静默整理与档案补全实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use executing-plans to implement this plan task-by-task in the current session. Steps use checkbox (`- [x]`) syntax for tracking. Do not delegate unless the user requests delegation.

**Goal:** 移除旧来电弹窗，通话后静默分析，只补全空缺或纠正有异议的档案信息。

**Architecture:** contracts 声明字段和命令，backend 档案业务入口负责合并、异议和失效发布；录音工作流只提交有依据的候选。app 提供 SQLite 迁移、归档持久化及后台恢复，ui 展示字段、来源和异议操作。保留原模块边界和唯一业务入口。

**Tech Stack:** Kotlin、Android SQLite、Compose/MVVM、JUnit/Robolectric、现有 Gradle 与 Python 统一验证门。

---

## 执行约束与基线

- 本计划对应同日已确认设计；不新增单位字段。新增字段使用 `selfReportedPosition`、`region`，原 `department` 不改名，不把单位名称自动写成部门。
- 自动处理字段只有 name、department、selfReportedPosition、region。positionId、whitelisted 和评分/免打扰策略不属于候选。
- 全程复用当前适合的 checkout，执行前核对分支及工作区；保留已存在的 `.superpowers`、process-audit 和 process-review 改动。
- 不调用真实付费模型，不覆盖生产安装，不自动解锁设备。代码阶段统一门通过后按 PROJECT.md 提交、合并 main、推送并核对对应 SHA 的 CI。
- 不为本次文档或中间检查点升版本。没有用户发包要求时不创建 Release。
- 下文路径均相对仓库根目录 `D:/Users/GreatWall/projects/callring`。每任务先补失败用例，再实现，再运行定向测试，按本任务文件提交；跨模块签名迁移保持消费者一起编译。

## Task 1：字段、异议状态及数据库升级

**修改文件：**

- `contracts/src/main/kotlin/com/greatwall/callring/contracts/callers/CallersApi.kt`
- `contracts/src/main/kotlin/com/greatwall/callring/contracts/command/UiEditsApi.kt`
- `backend/src/main/kotlin/com/greatwall/callring/domain/model/DomainModels.kt`
- `backend/src/main/kotlin/com/greatwall/callring/application/ports/store/CallerStore.kt`
- `app/src/main/kotlin/com/greatwall/callring/data/database/CallringDatabase.kt`
- `app/src/main/kotlin/com/greatwall/callring/data/database/StorageMigration.kt`
- `app/src/main/kotlin/com/greatwall/callring/data/callers/SqliteCallerStore.kt`
- `app/src/main/kotlin/com/greatwall/callring/data/callers/CallerRowCodec.kt`
- 现有所有 ProfileRecord/CallerDraft/CallerProfile 映射、测试 Fake 和真实查询投影消费者。

**测试文件：** `app/src/test/kotlin/com/greatwall/callring/data/CallingMigrationTest.kt`、`CallerSqlSemanticsTest.kt`、`CallerAccessContractTest.kt`。

- [x] 先在真实 SQLite 迁移测试中构造 version=3 数据：姓名、部门、position_id、白名单均有值，升级重开后逐列验证原值不变，新字段为空，异议为空。补 version=1/2 直升测试，不重跑旧升级步骤两次。
- [x] 执行 `./gradlew.bat :app:testDebugUnitTest --tests '*CallingMigrationTest' --tests '*CallerSqlSemanticsTest'`，记录失败确实来自缺少迁移/字段。
- [x] 将数据库版本升至 4；保留 1/2→3 原迁移，再对低于 4 的版本执行新增列和字段元数据迁移。新库直接创建最终 schema。两列 SQL 为：

```sql
ALTER TABLE callers ADD COLUMN self_reported_position TEXT NOT NULL DEFAULT '';
ALTER TABLE callers ADD COLUMN region TEXT NOT NULL DEFAULT '';
```

- [x] 为四个身份字段存储逐字段 revision、disputed 与来源（archive ID、analysis generation、原文依据）；与 caller 在同一 SQLite 事务写入。未处理字段默认 revision=0/disputed=false/无来源。用独立身份字段元数据存储，删除 caller 同时清理；不要将原文依据塞入用户文本字段。domain 持有纯数据，不依赖 contracts。
- [x] 在 DTO、草稿、保存命令和映射中增加两个文本字段及字段元数据快照；命令增加按字段设置异议的正式入口。请求只允许上述四字段；未知字段返回稳定校验错误。字段版本用于冲突检测，不增加用户“锁定”状态。
- [x] 更新 SQL 列集、编解码、真实查询及测试 Fake；两个新文本字段上限与姓名/部门的现有校验一致。复跑迁移、局部访问与 `:backend:test :ui:testDebugUnitTest`，保证未提供新字段的旧调用语义明确。
- [x] 提交本任务范围，提交说明 `feat: add caller region and self-reported position with field metadata`。

## Task 2：唯一的字段补全、异议及手动编辑规则

**修改文件：**

- `backend/src/main/kotlin/com/greatwall/callring/application/callers/CallerCommandService.kt`
- `backend/src/main/kotlin/com/greatwall/callring/application/callers/CallerProjection.kt`
- `backend/src/main/kotlin/com/greatwall/callring/application/callers/CallerQueryService.kt`
- `backend/src/main/kotlin/com/greatwall/callring/application/callers/CallersApiAssembly.kt`
- `backend/src/main/kotlin/com/greatwall/callring/application/CallringBackend.kt`
- 新增 `backend/src/main/kotlin/com/greatwall/callring/domain/calling/CallerEnrichmentPolicy.kt`
- 新增 `backend/src/main/kotlin/com/greatwall/callring/application/callers/CallerEnrichmentService.kt`
- 新增 `backend/src/test/kotlin/com/greatwall/callring/CallerEnrichmentPolicyTest.kt`
- `backend/src/test/kotlin/com/greatwall/callring/callers/CallerServiceTest.kt`

- [x] 写纯规则失败测试，覆盖如下输入/结果；新 policy 位于 domain，只接受文本、异议和版本，不接数据库或 AI DTO：

```kotlin
// CallerEnrichmentPolicy exposes apply(current, disputed, revision,
// expectedRevision, candidate, evidence): String?. null means keep current.
@Test fun fillsOnlyEligibleFields() {
    assertEquals("杭州", CallerEnrichmentPolicy.apply("", false, 0, 0, "杭州", "我在杭州工作"))
    assertNull(CallerEnrichmentPolicy.apply("上海", false, 0, 0, "杭州", "我在杭州工作"))
    assertEquals("杭州", CallerEnrichmentPolicy.apply("上海", true, 1, 1, "杭州", "我在杭州工作"))
    assertNull(CallerEnrichmentPolicy.apply("上海", true, 2, 1, "杭州", "我在杭州工作"))
    assertNull(CallerEnrichmentPolicy.apply("上海", true, 1, 1, "上海", "我在上海工作"))
    assertNull(CallerEnrichmentPolicy.apply("", false, 0, 0, "杭州", ""))
}
```

- [x] 运行 `./gradlew.bat :backend:test --tests '*CallerEnrichmentPolicyTest'` 确认失败，再实现纯判定：trim 后候选和依据均非空、版本一致、当前为空或有异议且候选不同，才返回候选。
- [x] 在 CallerEnrichmentService 通过窄事务端口重新读取当前字段，调用 policy，一次提交符合条件的字段及来源。非空无异议字段保持；成功补全/纠正置 disputed=false 并推进字段 revision。旧分析基线与现值相同但版本不同也不得写入，防止用户先修改又改回。
- [x] 手动字段保存仅对实际修改字段清除异议并推进 revision，未修改字段不动；手动异议操作单独推进对应 revision，不清空值、不调模型。清空字段按普通空缺处理。
- [x] 扩展 dirtyFields、baseline 合并、keys 与 ChangeFeed 的 CALLERS/REPORTS 实体影响；幂等无变化不发布。业务服务不能依赖完整 CallringBackend。
- [x] 补服务测试：分析等待期间用户编辑、同值但版本变化、另一个字段仍可补、重复调用不推进版本、职位/白名单不变、字段异议校验、清空后后续分析可补。
- [x] 执行 `./gradlew.bat :backend:test --tests '*CallerEnrichmentPolicyTest' --tests '*CallerServiceTest'` 并提交 `feat: implement guarded caller enrichment and dispute correction`。

## Task 3：分析结果扩展与归档兼容

**修改文件：**

- `contracts/src/main/kotlin/com/greatwall/callring/contracts/recording/RecordingApi.kt`
- `app/src/main/kotlin/com/greatwall/callring/platform/ai/CompatibleAiClient.kt`
- `app/src/main/kotlin/com/greatwall/callring/data/recording/AndroidRecordingStore.kt`
- `app/src/main/kotlin/com/greatwall/callring/data/recording/ArchiveStreamParser.kt`
- `app/src/main/kotlin/com/greatwall/callring/data/recording/ArchiveProjectionIndex.kt`
- `backend/src/main/kotlin/com/greatwall/callring/application/conversation/query/SafeProjection.kt`
- `app/src/test/kotlin/com/greatwall/callring/platform/ai/AndroidRecordingStoreTest.kt`、`ArchiveBoundedParserTest.kt`、`ArchiveProjectionMigrationTest.kt`。

- [x] 添加旧 JSON（原八字段）读回测试及新版字段往返测试。历史 organization 原样保留，缺少 department/region/逐字段依据时默认空，不据此填入档案。
- [x] 运行 `./gradlew.bat :app:testDebugUnitTest --tests '*AndroidRecordingStoreTest' --tests '*ArchiveBoundedParserTest' --tests '*ArchiveProjectionMigrationTest'`，确认新增用例失败。
- [x] CallAnalysis 保留历史 organization/position，新增 department、region 和四字段的独立原文依据。position 映射到档案 selfReportedPosition；不增加档案 organization。兼容逻辑只在现有归档编码器实现，不同时维护第二套模型声明。
- [x] 修改模型分析提示和严格 JSON 解析：输出只允许明确属于来电人的自述；地区仅限所属/工作地区；不自动补省市层级；第三人、出差地点和推测只展示。分段合并出现同字段互斥值时不产出该字段可写候选，保留冲突说明及原文。
- [x] 用固定模拟响应验证：同名第三人、仅单位无部门、出差地区、归属不明、跨片段冲突、缺少依据、未知键/错类型/超限。检查字段依据来自本段原文，不允许仅靠模型填一个“有依据”布尔值通过。
- [x] 重跑上述定向测试及 AI 客户端现有测试，提交 `feat: extract evidenced caller fields with archive compatibility`。

## Task 4：后台分析完成后补全与可恢复的本地提交

**修改文件：**

- `backend/src/main/kotlin/com/greatwall/callring/application/recording/RecordingWorkflow.kt`
- `backend/src/main/kotlin/com/greatwall/callring/application/recording/RecordingArchiveCommands.kt`
- `backend/src/main/kotlin/com/greatwall/callring/application/ports/recording/RecordingPersistence.kt`
- `app/src/main/kotlin/com/greatwall/callring/bootstrap/RecordingAssembly.kt`
- `app/src/main/kotlin/com/greatwall/callring/data/recording/AndroidRecordingStore.kt`
- `app/src/main/kotlin/com/greatwall/callring/entrypoint/jobs/RecordingJobController.kt`
- `backend/src/test/kotlin/com/greatwall/callring/RecordingWorkflowTest.kt`、`RecordingArchiveCommandsTest.kt`。

- [x] 写工作流失败测试：分析完成后补全；分析成功但本地提交失败；重启后只重试本地提交；删除/取消/重新关联录音拒绝迟到结果；重复执行不重复写。Fake AiClient 计数必须证明本地重试不增加 analyze/transcribe 次数。
- [x] 运行 `./gradlew.bat :backend:test --tests '*RecordingWorkflowTest' --tests '*RecordingArchiveCommandsTest'` 确认失败。
- [x] 在开始 analyze 前取得四字段基线版本和档案存在版本；分析成功先持久化结果和待补全状态，再经窄端口调用 Task 2。已有 COMPLETE 的历史归档不批量回填，避免未请求的历史推断。
- [x] 为本次新分析持久化待补全/已处理/失败状态及原基线；后台选取同时覆盖网络待执行和本地待提交。完成分析与本地补全分开错误分支，避免本地失败落入现有网络重试逻辑。
- [x] 在归档 generation、删除标记和 owner 租期保护下验证后提交；档案删除后不得由旧分析重建。档案原本不存在可首次建档，但必须复核原不存在版本，避免“删除后变不存在”误判。归档文件与 SQLite 不是同一事务，遵循先保存可恢复意图、幂等提交、再标完成的协议，不宣称跨存储原子提交。
- [x] nextRunAt/processPending 处理本地任务，关闭/取消不得发布迟到结果，真实存储重开后验证恢复。记录补全失败的稳定错误，不显示原始服务错误或私有路径。
- [x] 执行定向测试以及 `./gradlew.bat :app:testDebugUnitTest --tests '*RecordingPublicationTest' --tests '*IndependentRecordingRuntimeTest'`，提交 `feat: enrich callers after durable analysis with local retry`。

## Task 5：档案界面、异议操作、搜索及 AI 正式接口

**修改文件：**

- `ui/src/main/kotlin/com/greatwall/callring/ui/feature/callers/CallerEditorForm.kt`、`CallerEditorViewModel.kt`、`CallerListScreen.kt`
- `ui/src/main/kotlin/com/greatwall/callring/ui/feature/shared/CallerComponents.kt`
- `ui/src/main/kotlin/com/greatwall/callring/ui/feature/recording/ArchiveScreen.kt`、`ArchiveViewModel.kt`
- `ui/src/main/kotlin/com/greatwall/callring/ui/app/RecordingRoutes.kt`
- `backend/src/main/kotlin/com/greatwall/callring/application/conversation/operation/OperationCatalog.kt`、`OperationId.kt`
- `backend/src/main/kotlin/com/greatwall/callring/application/conversation/execution/ToolExecutor.kt`、`ReceiptVerifier.kt`、`ConfirmationTargets.kt`、`ToolBatchEngine.kt`、`StepLedger.kt`
- `contracts/src/main/kotlin/com/greatwall/callring/contracts/CallringApi.kt`、`ai/ConversationApi.kt`
- 删除 `backend/src/main/kotlin/com/greatwall/callring/application/recording/IdentityAdoptionService.kt` 及仅被它使用的采纳指纹/确认类型。

- [x] 在现有 CallerEditorViewModelTest、RecordingScreensTest、CallerListScreenJvmTest 中补失败用例：两个新字段编辑、来源查看、单字段标异议、不弹采纳确认、补全失败与分析完成区分。运行 `./gradlew.bat :ui:testDebugUnitTest` 确认新增断言失败。
- [x] 编辑器新增自述职位、所属地区文本框；四个身份字段各提供“有异议/无异议”操作和来源查看。沿用草稿、未保存退出及字段冲突处理，新增内容不要挤入原职位下拉框。
- [x] 扩展真实搜索投影和 visitCallerSearch，匹配姓名、部门、自述职位和地区；UI 与 AI 分页走同一查询。来源可在详情查看，列表展示控制长度并适配字体放大。
- [x] 录音详情移除旧采纳按钮和覆盖确认，显示补全状态及档案编辑入口。取消所有 adoptIdentity 调用、工具目录项、确认目标、执行分派及采纳专用核验代码，不留转发别名。
- [x] caller 查询/保存工具 Schema、作用域版本、读回核验和安全投影纳入新字段与异议状态。字段异议写入遵守现有前台工具授权；后台本地补全走固定窄业务入口，不授予聊天模型后台通用写权限。
- [x] 运行 `./gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest`，修复真实 loader 刷新及投影消费者，提交 `feat: expose caller enrichment and field disputes in profiles`。

## Task 6：完整移除旧来电弹窗及工具引用

**删除范围：** `ui/src/main/kotlin/com/greatwall/callring/ui/overlay/`，仅属于旧方案的 overlay 测试、RuntimeOverlayTestScreen、自检宿主和 `OverlaySettingsService.kt`。

**修改文件与消费者：**

- `app/src/main/kotlin/com/greatwall/callring/platform/calling/IncomingCallCoordinator.kt`、`IncomingCallController.kt`、`ScreeningController.kt`
- `app/src/main/kotlin/com/greatwall/callring/bootstrap/UiBridgeAssembly.kt`、`BackendGraph.kt`
- `ui/src/main/AndroidManifest.xml`、`app/src/main/AndroidManifest.xml`、相关测试 Manifest
- `ui/src/main/kotlin/com/greatwall/callring/ui/app/CallringActivity.kt`、`CallringApp.kt`、`ForegroundActionHost.kt`、`SettingsRoute.kt`、`bridge/UiNotifications.kt`
- `contracts/src/main/kotlin/com/greatwall/callring/contracts/callers/CallersApi.kt`、`settings/SettingsApi.kt`、`ai/ConversationApi.kt` 及 runtime capability 声明
- backend 的 DecisionInputs/DecisionSnapshot/DecisionMirror、HomeService、SettingsSummaryService、OverlayReader/Writer/Transactions 以及所有装配、真实存储适配和查询投影消费者。
- OperationCatalog/OperationId、OperationScopePlanner、ReceiptVerifier、ToolExecutor 及提示、工具发现与完整快照内的旧弹窗引用。

- [x] 在 IncomingCallCoordinatorTest 和 ScreeningControllerTest 中保留记录、铃声、免打扰、统计开关独立性和 onIncoming 录音登记断言；将“展示 UI”断言替换为核心工作完全不需要 UI sink 的构造与执行测试。删除仅证明旧窗口可见的测试，新增运行页不再显示悬浮权限/自检入口的 UI 断言。
- [x] 删除 show 操作及 IncomingCallUiSink/IncomingCallUiRequest、其注入参数和所有调用，保持 Telecom 时限及异步持久化逻辑不变，不以 no-op sink 替代。
- [x] 删除整个旧窗口实现及专用 Activity/Service 声明、悬浮开关、专用通知渠道创建、权限申请入口、预览、自检、TEST_OVERLAY/PREVIEW_CALL 前台动作及相关 AI 工具。保留六个根系统组件身份、录音前台通知和其他真实消费者需要的通用能力。
- [x] 移除业务决策与设置中的 overlay 能力和全部接口消费者；旧 settings 存储键允许留存但不再读取/写入。不重新解释其值，不清空用户设置。
- [x] 运行以下扫描，逐个解释残留，产品路径不得保留旧入口；历史设计、迁移夹具中的引用允许保留：

```powershell
rg -n 'IncomingCallUiSink|IncomingCallUiRequest|setOverlayEnabled|TEST_OVERLAY|PREVIEW_CALL|CallringOverlayService|LockScreenOverlayActivity|adoptIdentity' contracts/src/main backend/src/main app/src/main ui/src/main
rg -n 'SYSTEM_ALERT_WINDOW|canDrawOverlays|ACTION_MANAGE_OVERLAY_PERMISSION|callring.overlay' app/src/main ui/src/main
```

- [x] 执行 `./gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest`，修复全部消费者和装配测试，提交 `refactor: remove obsolete incoming call overlay workflow`。

## Task 7：协议审查、统一门、文档与交付

**修改文件：** `PROJECT.md`、`README.md`、`docs/contracts/ai-operation-compatibility.json`、守卫实际读取的内部签名/声明摘要，以及本计划的完成记录。

- [x] 列出协议差异：删除弹窗开关、自检、预览、旧采纳工具；新增两字段、异议状态与正式命令；保留历史录音 JSON 的 organization。审查后精准更新基线和目录数量，不直接全量重生成来通过守卫，不更换既有操作身份或复用已删除编号。
- [x] README 移除悬浮窗能力/权限说明，说明静默流程、录音条件、自动补空缺、异议纠正与来源、两字段；PROJECT 将“待实现”改为实际行为并记录当轮验证边界。历史验收摘要不改成当前已通过。
- [x] 运行 `python tools/verify_backend.py`；读取本轮 summary，核对失败、跳过、缓存及任务状态，不复用旧测试 XML。当次源码改变后重新跑必要验证及最终统一门。
- [x] 有在线且已手动解锁的 ADB 设备时，使用隔离包覆盖：新旧 schema 升级、亮屏/锁屏/息屏不启动窗口、模拟录音到分析到 SQLite 重开读回、异议纠正与删除迟到结果。删除旧 LockScreenOverlayDeviceTest 后新增 `SilentCallEnrichmentDeviceTest`，验证静默流程而非窗口出现。无设备则如实列未测项，不改主包角色或锁屏超时。
- [ ] 运行 `git diff --check`，审查只提交本次文件；核对 gh 登录与远端 main，依项目流程合并、推送并等待精确 SHA 的 CI。合并改变源码 SHA 则重新验证，不能用其他提交的绿灯替代。
- [ ] 交付注明代码提交、推送/CI 实际状态、无真实模型付费验证与真实 SIM/后台调度的实测范围。未要求 APK 时不发 Release。


## 自检

覆盖映射：静默流程→Task 4/6；字段及兼容→Task 1/3；补空缺和异议→Task 2；查看和纠正→Task 5；删除旧入口→Task 5/6；来源、并发、删除与恢复→Task 2/4；协议与验收→Task 7。

Task 1–6 已实施。上游同步为 16996ad，包含录音分页、备份恢复和 owner 封口修复，实施时扩展了 schema 3 备份私有暂存升级和新分析字段的备份形状验证。签名消费者统一迁移后共同提交，保留早期检查点 4626d0b。

已补回归：补全保存和失败状态同时保存失败时不重复模型调用；新分析使用新基线及来源 generation；字段编辑同时清除异议/来源时纳入元数据作用域；矛盾的 AI 改值并标异议在提交前拒绝；待补全归档排除存储清理；字面依据验证、档案删除重建、草稿恢复及真实 SQLite 重开。生产接口读回专项和模拟器静默补全专项已通过。最终统一门、提交后 CI 与交付结果按 Task 7 继续记录，不将上述专项代替完整验收。

最终源码 f0e71ba 的统一门通过：JVM 443/352/438、常规设备 31 项及静默补全专项 1 项；生产包未改动。后续文档提交不改变受测源码，推送与远端 CI 的实际结果以本次交付回执为准。
