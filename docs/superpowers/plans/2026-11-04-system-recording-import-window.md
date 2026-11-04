# 系统录音导入日期范围实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 如用户选择子代理执行，再使用 superpowers:subagent-driven-development。

**Goal:** 系统录音按可配置的滚动日期窗口发现并导入安装前后的文件，默认最近 7 天，支持大目录、持久去重及安全重试，不改变通话记录同步行为。

**Architecture:** contracts 承载录音设置；backend/domain 实现文件名解析和时间窗口，backend/application 编排发现、关联、稳定性检查和正式导入。app/platform 流式枚举授权目录，app/data 持久化来源身份和扫描状态；复用现有负数录音身份、归档事务、累计失效和 RecordingWorkflow。UI 只调用正式设置接口，系统 Job 保持组件身份。

**Tech Stack:** Kotlin、Android SAF/DocumentsContract、SQLite、JobScheduler、Compose/MVVM、JUnit/Robolectric；工具链及统一验证以 PROJECT.md 为准。

---

## 输入、范围与执行边界

- 设计来源：[系统录音导入日期范围](../specs/2026-11-04-system-recording-import-window-design.md)。本文件为新增实施计划，原设计保留。
- 开始执行前重新阅读根 AGENTS.md、PROJECT.md，并核对 HEAD、工作树及本计划中的路径。项目规则仍只由 AGENTS.md 和 PROJECT.md 维护。
- 本计划已进入实施。勾选仅表示该条已接入或有已知定向验证；未勾选的设备、完整回归、统一门和交付步骤仍待执行，不由局部通过推定。
- 不增加固定日期模式，不限制通话记录同步，不删除范围外的已有归档，不改变显式手工导入的范围，不新增一套转写/分析流程。
- 设计未规定的参数按下文实际取值实施，并同步到 PROJECT.md；这些取值不冒充原设计已确认的要求。
- 提交按任务文件精确暂存；不要使用 `git add .`，不要夹带工作树原有文档或运行状态文件。执行期间按当前 checkout 条件复用或创建开发分支，不重置用户改动。

## 已核对的实现入口

以下均为仓库根目录相对路径。

| 路径 | 当前职责及本次变化 |
| --- | --- |
| `contracts/src/main/kotlin/com/greatwall/callring/contracts/recording/RecordingSettingsView.kt` | View/Patch 增加天数字段，保留已有参数兼容方式 |
| `backend/src/main/kotlin/com/greatwall/callring/application/recording/model/RecordingConfiguration.kt` | RecordingSettings 默认值与唯一业务校验 |
| `backend/src/main/kotlin/com/greatwall/callring/application/recording/AiSettingsService.kt` | 设置读写、字段冲突及提交后调度；同时覆盖 patchRecording、patchCombined |
| `backend/src/main/kotlin/com/greatwall/callring/application/recording/RecordingSettingsChanges.kt` | 精准作用域 `recordingConfig:systemImportDays` |
| `backend/src/main/kotlin/com/greatwall/callring/application/ports/recording/RecordingMediaPorts.kt` | 现有目录和身份窄端口；新增扫描能力时同步迁移装配与测试 Fake |
| `backend/src/main/kotlin/com/greatwall/callring/application/ports/recording/RecordingPersistence.kt` | 现有独立录音事件登记及统计关联事务 |
| `backend/src/main/kotlin/com/greatwall/callring/application/recording/RecordingService.kt` | 当前 recentMetadata(12 小时) 驱动发现；改为委托唯一发现服务，复用正式音频导入 |
| `backend/src/main/kotlin/com/greatwall/callring/application/recording/RecordingRegistrationService.kt` | 独立身份登记、提交后发布 |
| `app/src/main/kotlin/com/greatwall/callring/platform/recording/SystemRecordingSource.kt` | 现有 list 超过 500 项失败；增加专用流式扫描，不扩大 AI 快照上限 |
| `app/src/main/kotlin/com/greatwall/callring/data/recording/AndroidRecordingStore.kt` | 当前 schema 5；升级、来源绑定、设置 JSON 编码；新表逻辑拆到同目录专用文件 |
| `app/src/main/kotlin/com/greatwall/callring/bootstrap/RecordingAssembly.kt` | 唯一装配、owner 封口与资源释放 |
| `app/src/main/kotlin/com/greatwall/callring/entrypoint/jobs/RecordingJobController.kt` | 合并发现重试与现有处理队列的下次运行时间 |
| `app/src/main/kotlin/com/greatwall/callring/RecordingJobService.kt` | 保留现有 Job ID、组件身份；核对网络条件对本地扫描的影响 |
| `app/src/main/kotlin/com/greatwall/callring/entrypoint/runtime/RuntimeEventController.kt` | 当前 IDLE/RESTORE 已触发录音 Job，复用这些入口 |
| `ui/src/main/kotlin/com/greatwall/callring/ui/feature/ai/AiSettingsViewModel.kt` | ScopedRecordingSettingsViewModel 的设置草稿与串行保存 |
| `ui/src/main/kotlin/com/greatwall/callring/ui/feature/recording/RecordingSettingsScreen.kt` | 天数输入、说明、就地校验 |
| `ui/src/main/kotlin/com/greatwall/callring/ui/app/RecordingRoutes.kt` | 新输入回调接线 |
| `app/src/main/kotlin/com/greatwall/callring/data/backup/AndroidBackupArchivePort.kt` | 新旧备份设置、来源映射及扫描状态的导出/恢复边界 |
| `app/src/main/kotlin/com/greatwall/callring/data/backup/SqliteBackupCopy.kt` | 录音暂存库先升级再严格校验；当前仅有核心库升级入口 |

## 实施约束与实际取值

1. 字段统一命名 `systemImportDays: Int = 7`，Patch 使用 `PatchField<Int>`；有效范围为 `1..Int.MAX_VALUE`，输入先安全解析，不截断、不溢出、不以默认值覆盖非法值。天数乘法先转 Long，不额外引入产品上限。
2. 每轮冻结 `now`、天数、设备 ZoneId 和目录/设置版本。窗口闭区间为 `[now - days.toLong() * 86_400_000L, now]`，不采用自然日；未来文件本轮跳过。
3. 文件名解析使用严格日期解析；本地时间对应零个或两个时区偏移时拒绝自动导入，不让 atZone 自动修正夏令时缺口或选择重叠时间。姓名仅为文件名组成部分，不用它猜测号码、通话方向或档案身份。
4. 来源键使用 provider authority 与 documentId 的规范组合摘要，不用显示名称、修改时间或目录授权 URI 充当唯一键。相同 provider/documentId 的改名和重新授权保持同一身份；provider 改变 ID 的移动或复制不承诺跨 ID 自动去重，候选冲突时跳过自动关联。
5. 来源键到 canonical archiveId 的绑定持久化且唯一，删除保留绑定与墓碑。已有身份优先复用；安装前文件无匹配通话时登记负数录音事件。后到的通话只关联原身份，不重建已分析归档；有多个可能归档或任一目标已删除时不自动合并。
6. 实际稳定观察间隔为 10 秒：大小为正、已知修改时间且至少两次实际观察一致，使用 observedAt 判断两次观察间隔，不能只靠 elapsed 时间；复制前后再次核验。未知元数据或正在进行的通话不自动导入，保留显式选择能力。通过稳定观察才接受冻结候选，允许已接受候选滑出新窗口后完成本次导入；未通过前不复制音频。
7. 实际每批最多 64 项；遍历逐行释放资源，不把整目录装入 List。SAF 不保证分页、排序或稳定 offset，取消后可从目录开头重枚举并用持久来源账本跳过已处理项，不能把 offset 当持久游标或承诺 provider 级随机分页。
8. 已提交项不因重试重放；扫描完成、取消、不完整、权限失效和失败是不同结果。当前可重试 IO 失败固定 60 秒后重试，尚未采用指数退避；等待稳定项按到期时间调度，权限失效等待恢复。目录未变且扫描已完成时不无间隔自循环。
9. 关闭录音、更换目录、设置版本变化、owner 终止使旧扫描失效，提交前复核。权限失效停止新导入，已经导入的持久处理仍按正式工作流规则进行。缩小窗口不撤销已提交音频或已排队的分析。
10. 本地发现不以 AI 连接已配置或联网为前提；自动分析关闭时音频仍可归档为 READY。真正模型处理仍服从原开关、配置与错误重试规则。

## Task 1：设置契约、持久化与精准刷新

**修改：** 文件表中的 RecordingSettingsView.kt、RecordingConfiguration.kt、AiSettingsService.kt、RecordingSettingsChanges.kt、AndroidRecordingStore.kt。

**测试：** `backend/src/test/kotlin/com/greatwall/callring/AiSettingsMergeTest.kt`；`app/src/test/kotlin/com/greatwall/callring/platform/ai/AndroidRecordingStoreTest.kt`。

- [ ] 先新增失败用例：默认 7、缺字段旧 JSON 默认 7、保存 30 后重开读回、0/负数/超 Int 输入拒绝、Keep 保留、并发改同字段冲突、重复保存无版本推进、无关 AI loader 不刷新。
- [ ] 运行定向测试，确认失败来自新字段或行为缺失，而非环境故障：

```powershell
.\gradlew.bat :backend:test --tests '*AiSettingsMergeTest' :app:testDebugUnitTest --tests '*AndroidRecordingStoreTest' --console=plain
```

- [x] 在 View 和 Settings 末尾追加字段，在 Patch 末尾追加 Keep 字段；编解码仅在 RecordingJson 的正式设置编码器实现。旧缺字段默认 7，存在但类型/范围非法时明确拒绝。

```kotlin
// RecordingSettings、RecordingSettingsView 的新增属性
val systemImportDays: Int = 7
// RecordingSettingsPatch 的新增属性
val systemImportDays: PatchField<Int> = PatchField.Keep
// RecordingSettings.validate() 与作用域映射的新增语句
require(systemImportDays > 0) { "recording.invalid_import_days" }
// recordingSettingsScopeValues(v)
put("recordingConfig:systemImportDays", v.systemImportDays)
```

- [x] patchRecording 和 patchCombined 均通过已有 field(base,current,patch) 校验后保存；recordingView 返回实际值。两条写入口不得出现不同校验或遗漏。
- [ ] 重跑同一命令，预期新增及原有用例通过；检查真实 ChangeFeed 和 loader 计数，不能只检查 setter 返回值。
- [ ] 按本任务文件提交 `feat: add system recording import window settings`。

## Task 2：纯解析与窗口边界

**新增：** `backend/src/main/kotlin/com/greatwall/callring/domain/recording/SystemRecordingNameParser.kt`、`SystemRecordingWindow.kt`，以及 `backend/src/test/kotlin/com/greatwall/callring/SystemRecordingNameParserTest.kt`、`SystemRecordingWindowTest.kt`。

- [ ] 写参数化失败用例，使用合成号码 12345：`示例(12345)_20261104120000.mp3` 和既有 `20261104_120000` / `20261104-120000` 格式；号码只取明确片段。拒绝 20260230、截断日期、多个时间片段、多个号码候选、时区缺口和重叠。
- [ ] 窗口用固定 now 测下界、上界恰好命中，前后各 1ms 拒绝，N 为 Int.MAX_VALUE 不溢出；修改文件 modifiedAt 不改变筛选结果。
- [ ] 运行失败测试：

```powershell
.\gradlew.bat :backend:test --tests '*SystemRecordingNameParserTest' --tests '*SystemRecordingWindowTest' --console=plain
```

- [x] 实现返回不可变号码/时间的纯解析器，不依赖 Android、contracts 或存储。建议公开模型与窗口实现如下；严格解析器对每个 LocalDateTime 使用 zone.rules.getValidOffsets，并只接受单一偏移。

```kotlin
data class ParsedSystemRecording(val number: String, val startedAtEpochMillis: Long)

data class SystemRecordingWindow(val now: Long, val days: Int) {
    init { require(days > 0) }
    val since: Long = Math.subtractExact(now, days.toLong() * 86_400_000L)
    fun contains(startedAt: Long): Boolean = startedAt in since..now
}
```

- [ ] 重跑并检查既有 RecordingAssociationTest/RecordingServiceTest 的旧格式回归；提交 `feat: parse system recording names with strict time windows`。

## Task 3：专用流式枚举与取消

**新增：** `backend/src/main/kotlin/com/greatwall/callring/application/ports/recording/SystemRecordingScanPort.kt`；`app/src/main/kotlin/com/greatwall/callring/platform/recording/SystemRecordingScanner.kt`；`app/src/test/kotlin/com/greatwall/callring/platform/ai/SystemRecordingScannerTest.kt`。

**修改：** SystemRecordingSource.kt、RecordingAssembly.kt；生产与测试的相关装配。

- [ ] 写失败用例：501、2,001 项目录正常遍历；混合目录/音频；空 Cursor、授权撤销、单项损坏、深层目录循环；第 65 项取消时关闭全部 Cursor，无取消后回调；仅枚举不调用音频解码或复制。
- [ ] 运行 `:app:testDebugUnitTest --tests '*SystemRecordingScannerTest'`，确认新能力缺失导致失败。
- [x] 定义独立窄扫描端口，逐条或最多 64 条回调，元数据包含来源摘要、文件 URI、名称、大小及修改时间是否已知。回调及每次 moveToNext 前检查取消，取消时继续抛出 CancellationException。查询传 Android CancellationSignal，以便取消阻塞中的 resolver.query；取消异常继续传播。
- [x] 扫描栈按目录持久身份防循环，固定最大嵌套深度并将超限标记为不完整；内存中只保留遍历路径与当前批次。不要复用 DirectoryEnumerator.capture 的全量快照来实现此端口。
- [x] 保留显式 systemRecordings/list 与共享 AI 目录快照的现有安全限制；自动发现仅使用新扫描端口。应用层不得接 Cursor、Context 或 resolver。
- [ ] 重跑新增测试及 `*RecordingCandidatePaginationTest`、`*RecordingFolderViewTest`，预期原有 AI 枚举限制和授权校验仍通过；提交 `feat: stream automatic system recording discovery`。

## Task 4：来源账本、独立身份及原子去重

**新增：** `backend/src/main/kotlin/com/greatwall/callring/application/ports/recording/SystemRecordingImportStore.kt`；`app/src/main/kotlin/com/greatwall/callring/data/recording/SystemRecordingImports.kt`；`app/src/test/kotlin/com/greatwall/callring/platform/ai/SystemRecordingImportStoreTest.kt`。

**修改：** RecordingPersistence.kt、RecordingRegistrationService.kt、AndroidRecordingStore.kt、RecordingMetadataReads.kt；关联查询需要的窄端口及其唯一实现。

- [ ] 写失败用例：同来源连续/并发登记只有一个 canonical ID；无统计记录仍有负数录音身份；已有唯一通话复用；双向歧义不关联；事务失败不残留孤立事件；删除再扫描不建档；旧 schema 1–5 升级后原记录、序列及墓碑不变。
- [ ] 运行 `:app:testDebugUnitTest --tests '*SystemRecordingImportStoreTest'`，预期缺新表/入口而失败。
- [x] schema 5 升至 6，新建来源绑定表及扫描观察表。来源绑定保存 source_key 唯一键、archive_id、号码/开始时间及 completed 阶段；大小、修改时间、观察时间归独立临时 inventory，扫描状态保存冻结窗口与配置签名。DDL 集中在 SystemRecordingImports，不复制到多个升级入口。
- [x] 在同一 SQLite 事务中执行“查来源绑定 → 查唯一可复用身份 → 必要时登记事件 → 插入绑定”；已有来源不得另分配 ID。新事件 session_id 使用 `system-file:` 加来源摘要，沿用负数 ID 规则，不伪造 account、方向、接通状态或统计记录。
- [ ] 已有通话按规范化号码与开始时间 ±3 秒匹配，查询用索引和有界候选，不重新全量加载通话或全文归档。一个文件匹配多个通话、多个不同来源文件争用同一通话时均记为歧义，不能以遍历先后选胜者。
- [x] 原始 ID、统计关联与来源绑定分开保存；通话晚到后只添加能够唯一证明的关联。存在两个已有归档时不自动搬迁音频/分析，不以合并绕过删除标记。
- [x] 扫描观察写入不推进归档列表 revision；实际身份或归档改变经正式登记/命令服务提交后发布精准失效，rollback 不发布。
- [ ] 重跑新增测试和 `*AndroidRecordingStoreTest`、`*RecordingRegistrationServiceTest`、`*RecordingArchivePagingTest`；提交 `feat: persist system recording source identities`。

## Task 5：文件稳定性与唯一导入流程

**新增：** `backend/src/main/kotlin/com/greatwall/callring/application/recording/SystemRecordingDiscovery.kt`、`backend/src/test/kotlin/com/greatwall/callring/SystemRecordingDiscoveryTest.kt`。

**修改：** RecordingService.kt、RecordingAssembly.kt；需要时以同目录窄适配抽出正式音频附加能力，手工与自动导入必须仍共用唯一实现。

- [ ] 用注入时钟、Fake 扫描端口及调用计数写失败测试：首次观察不复制，间隔不足不复制，大小改变重置观察，稳定后仅复制一次，复制期间变化丢弃暂存，未知元数据不自动导入，等待稳定跨窗口仍完成冻结候选。
- [ ] 再写失败测试：安装前文件无既有档案仍能导入；COMPLETE 不再复制或调用模型；自动分析关闭为 READY；录音关闭不登记；重复扫描无归档 revision 增量；删除或 owner 失效发生在复制期间时不能提交或发布。
- [ ] 运行 `:backend:test --tests '*SystemRecordingDiscoveryTest'` 验证失败。
- [x] 发现服务顺序为“冻结轮次 → 流式解析/窗口过滤 → 持久观察 → 完整枚举及来源冲突排除 → 稳定性确认与唯一目标关联 → 来源身份绑定 → prepareImport → 再 inspect → 复核配置/来源/墓碑/generation → 正式 attach”。解析和复制不得跨数据库事务。
- [x] 删除旧 discoverSystemRecordings 的 12 小时 metadata 全量候选和私有 matches；入口委托新发现服务。只有通过完整唯一性检查的候选才进入导入，不能发现一个就立即付费分析而尚未检查后续冲突文件。
- [x] 复用 PreparedRecordingAudio 的 retain/close 所有权与 RecordingWorkflow.attach；来源绑定的完成标记和归档提交保持可恢复一致性。若提交后、来源标记前崩溃，重开先核对已附加归档，不能重复附加或重置 COMPLETE。
- [ ] 重跑新增测试及 `*RecordingServiceTest`、`*RecordingWorkflowTest`、`*RecordingArchiveCommandsTest`，预期通过；提交 `feat: import stable discovered recordings through shared workflow`。

## Task 6：触发、续跑及生命周期

**新增测试：** `backend/src/test/kotlin/com/greatwall/callring/SystemRecordingSchedulingTest.kt`；`app/src/test/kotlin/com/greatwall/callring/bootstrap/SystemRecordingOwnerTest.kt`。

**修改：** AiSettingsService.kt、RecordingAssembly.kt、RecordingService.kt、RecordingJobController.kt、RecordingJobService.kt、RuntimeEventController.kt。

- [ ] 先测：授权目录、开启录音、改变天数都请求下一轮；重复保存不额外调度；无 AI 待处理项但有稳定性观察/可重试扫描失败时仍返回 nextRunAt；取消不报告扫描完成；新 owner 不接续旧 owner 的内存回调。
- [ ] 运行 `:backend:test --tests '*SystemRecordingSchedulingTest' :app:testDebugUnitTest --tests '*SystemRecordingOwnerTest'`，确认失败。
- [x] 设置 afterSave 改为携带实际变更信息的窄通知，在持久化成功后请求扫描；setSystemFolder 同样触发，调度失败不得把已经成功保存的设置报告成未提交。启动/恢复/通话 IDLE 复用现有入口。
- [x] runScheduled 返回发现待办与 workflow.nextRunAt 的最早时间；停止吞掉发现失败，不因目录失败阻断已有音频处理。DENIED 等待权限恢复或用户重新授权；可重试 IO 使用持久退避；稳定性观察到期独立于是否有 QUEUED 归档。
- [x] 明确本地扫描的无网络运行方式：保留同一 Job ID，取消全局 NETWORK_TYPE_ANY 限制，把联网条件放到真正的模型处理阶段；离线模型待办使用受控退避，避免立即自循环，不新增第二业务队列。
- [ ] 补齐离线后恢复联网的专项验证。
- [x] 关闭/替换 owner 时先封口扫描与取消系统查询，再排空已接受写入，最后关闭 store；配置变化使旧轮次失效后开启新冻结轮次。重启从持久来源账本恢复，重枚举不重放已提交导入。
- [ ] 重跑新增测试及 `*RecordingAssemblyDrainTest`、`*RestoreJobOwnerTest`；提交 `feat: schedule resumable recording discovery safely`。

## Task 7：备份兼容、恢复暂停与来源边界

**修改：** AndroidBackupArchivePort.kt、SqliteBackupCopy.kt；SystemRecordingImports.kt 的可移植数据与临时状态划分。

**新增测试：** `app/src/test/kotlin/com/greatwall/callring/data/backup/SystemRecordingBackupMigrationTest.kt`。

- [ ] 失败测试覆盖旧录音 schema 1–5、缺天数字段、schema 6 来源绑定与墓碑、可选音频、损坏来源引用、非法天数；恢复预览不修改原备份/当前库。
- [ ] 运行 `:app:testDebugUnitTest --tests '*SystemRecordingBackupMigrationTest'` 验证失败。
- [x] 在私有暂存中执行录音库升级，再调用现有严格 schema/reference 校验；不能删除校验或仅比较版本号。保留原始归档 ID、payload、唯一约束和 sqlite_sequence。
- [x] 新备份保留天数、来源摘要到归档的绑定和删除事实；清除原始 URI、目录授权、运行租约、扫描轮次和临时观察。不同设备不承诺 documentId 相同；恢复后不把来源摘要解释成访问权限。
- [ ] 恢复仍关闭自动分析、暂停待处理任务；没有音频的已恢复来源明确保留“缺音频”状态，不由自动扫描悄悄重新分析。缺音频时，重新授权目录或点击分析重试不会自动补齐；需在原归档重新选择文件导入，经正式入口沿用原始身份。
- [ ] 重跑新增测试及 `*AndroidBackupArchivePortTest`、`*SqliteBackupCopyTest`、`*RestoreJournalTest`，预期新旧备份均通过；提交 `feat: migrate recording import state in backups`。

## Task 8：录音设置页面与契约消费者

**修改：** AiSettingsViewModel.kt、RecordingSettingsScreen.kt、RecordingRoutes.kt；`backend/src/main/kotlin/com/greatwall/callring/application/conversation/query/SafeProjection.kt` 及实际受影响的设置投影。

**测试：** `ui/src/test/kotlin/com/greatwall/callring/ui/feature/recording/RecordingScreensTest.kt`、`RecordingViewModelsTest.kt`；`backend/src/test/kotlin/com/greatwall/callring/AiSettingsMergeTest.kt`。

- [ ] 写失败测试：首次显示 7，修改 30 后正式读回；空值、0、负数、非数字及超 Int 输入保留草稿并提示；保存期间继续编辑不被迟到响应覆盖；错误和冲突保留草稿；后台恢复正确显示持久值。
- [ ] 运行 `:ui:testDebugUnitTest --tests '*RecordingScreensTest' --tests '*RecordingViewModelsTest'` 验证失败。
- [x] 增加数字输入“系统录音自动导入天数”，说明“默认最近 7 天；仅影响系统录音自动导入，已有录音和通话记录不受影响”。输入停顿 500ms 后提交合法值，串行保存最新草稿，首次加载前不能保存；成功静默且已有数据刷新不显示进度。
- [x] UI 仅消费 RecordingSettingsView/Patch，不自行计算截止时间或查询目录。
- [ ] 对新字段补齐窄屏、键盘、系统字体缩放和旋转恢复的专项验证。
- [ ] 盘点所有 View/Patch 构造、复制和 Fake，确保非本字段修改使用 Keep。新字段本次由本地 UI 编辑，不自动扩充外部 AI 写入 Schema/操作；如安全投影新增可见字段，明确审查 D13 完整性和兼容测试，不能意外以返回默认值冒充真实设置。
- [ ] 审查并精准更新 `docs/contracts/current-api-baseline.md` 及架构守卫要求的声明摘要；外部 AI 操作基线不为通过守卫而整体重生成。
- [ ] 重跑定向 UI 测试和实际受影响的 AI 设置/投影测试，检查无关 loader 增量为零；提交 `feat: configure system recording import days in settings`。

## Task 9：集成、设备验收与交付

**文档：** PROJECT.md、README.md；本计划追加实际验证记录。

**新增设备测试：** `app/src/backendQa/kotlin/com/greatwall/callring/SystemRecordingImportDeviceTest.kt`，沿用隔离 backendQa 测试约定。

- [ ] 在合成 Provider 中建 2,001 项目录，包含两种名称、非法日期、未来文件、窗口内外边界、稳定后变化文件；记录扫描次数、最大在途批次、复制/解码次数和模型次数。断言窗口外文件零复制/解码，重复扫描 COMPLETE 零模型调用。
- [ ] 集成覆盖重启、扫描中取消、导入前删除、复制中关闭开关、换目录、权限撤销、失败重试、通话后到和备份恢复；通话记录同步查询结果及持久行不因录音窗口变化而改变。
- [ ] 在隔离测试包中使用合成姓名/号码/音频验证真实 SAF、音频解码及重开读回；模拟模型不触发真实费用。设备可用时按项目工具的显式专项选择方式运行并记录真实序号，不安装或修改生产包。
- [ ] 真机确认实际文件名格式、目录数量、provider 元数据/取消行为；不收集或提交私人名称、号码、录音。没有可用设备时明确记为未测，不拿 Robolectric 代替厂商目录验收。
- [x] 同步 PROJECT.md 的正式职责、来源身份/删除协议、扫描及备份语义，README.md 的默认 7 天、天数设置和自动导入条件。真实在线转写/分析仅在获得明确付费授权后补验。
- [ ] 运行最终代码统一门，检查本轮 summary 的真实执行状态及失败信息：

```powershell
python tools/verify_backend.py
git diff --check
```

- [ ] 统一门预期全部要求通过；记录最终源码 SHA、JVM 数量、架构守卫、双 lint、APK 编译，以及设备/真实服务未测项。若失败先修复再继续，不能仅报告定向测试通过。
- [ ] 按 PROJECT.md 及 callring-delivery 技能提交、合并 main、推送并确认对应最终 SHA 的 CI；只有用户要求发包时再递增发行版本和发布 Release。计划/文档本身不升版本。

## 覆盖与完成标准

| 设计要求 | 对应任务 | 完成证据 |
| --- | --- | --- |
| 默认 7 天、设置读回、旧安装/备份 | 1、7、8 | 旧 JSON/旧库夹具、正式 API 与重开读回 |
| 闭区间窗口、未来时间、严格日期/时区 | 2 | 固定时钟和时区边界测试 |
| 两种文件名、号码明确、不猜身份 | 2、4 | 正反例与歧义关联测试 |
| 安装前录音、独立身份 | 4、5 | 无通话记录情况下导入及重开查询 |
| 超过 500 项、取消恢复 | 3、6、9 | 大目录流式枚举、取消及资源释放 |
| 文件稳定、重试、删除不复活 | 4、5、6 | 复制前后变化、崩溃恢复、墓碑竞态 |
| 不重复分析、服从现有开关 | 5、7、9 | 模型计数、READY/COMPLETE 与恢复暂停 |
| 通话同步不变、手工导入不受窗口限制 | 5、9 | 正式消费者回归与持久行对照 |
| 隐私、模拟与真实服务证据分开 | 9 | 合成夹具、隔离包证据、未测项说明 |

所有任务完成且最终统一门通过，才可宣称实现完成。编写本计划、定向测试通过、APK 编译或模拟设备通过均不等同于真实 SIM、厂商待机和真实付费服务全链路已验收。

## 实施记录（2026-11-04，最终验证待补）

- **已接入**：设置契约及 JSON、字段冲突与无变化处理、500ms 数字草稿串行保存、严格时间解析、专用流式 SAF 枚举、schema 6 来源/观察账本、唯一导入编排、录音 Job 本地无网络调度、备份私有升级及临时信息清理。当前 API 声明清单由主负责人单独核对。
- **已知定向结果**：设置/投影及 UI 草稿用例、解析/扫描/来源存储/发现与运行入口已有 JVM 通过记录；备份定向 12 项通过，覆盖 schema 1–5 候选升级、原始字节/归档/墓碑/序列保留、schema 5 加密备份预览及旧天数缺省、来源与完成阶段恢复、URI 清理及非法引用拒绝。这些结果不代表后续源码或最终 SHA 已完成统一验证。
- **测试组织差异**：窗口用例目前与 `SystemRecordingNameParserTest` 同类；发现集成使用 app Robolectric 的 `SystemRecordingDiscoveryTest`。实际生产消费者及测试路径以仓库文件为准，不另保留计划中的空壳入口。
- **设备专项状态**：新增 backendQa `SystemRecordingImportDeviceTest` 与合成 `SystemRecordingQaProvider`，已通过 Kotlin 编译；当前夹具含 2,001 项元数据、一个固定 WAV、2,000 个不可解析名称，并检查稳定观察、真实解码/模拟 AI、重开去重、删除不复活与原文件保留。尚未执行设备测试；不能据此勾选 Task 9 的全部边界、取消、真实授权或厂商目录验收。首次 APK 封包受 Windows 工作树 C:/D: 路径解析冲突影响，由主负责人统一处理构建路径。
- **仍待收尾**：恢复暂停守卫及当前源码回归、完整统一门、隔离设备执行、真实目录观察、最终 SHA/日志/测试数量和未测项记录，以及提交、合并、推送与该 SHA 的 CI。未使用真实转写/分析服务，不存在新增付费服务验收结果。
