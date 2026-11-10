# 统一来电记录与分析确认实施计划

> 执行时使用 executing-plans 技能逐阶段验证。复选框用于跟踪实施进度。

**目标**：在「档案」内提供不限号码和日期的来电列表、单次通话详情，以及手动分析确认写档和后台自动补全两条独立路径。

**架构**：列表以应用保存的来电为主，使用既有通话和归档关联，按页合并状态。详情复用通话、播放、离线转写和分析服务。后端持久化分析意图和确认状态，独立裁决手动写档与自动补全。

**技术栈**：Android、Kotlin、Compose Material 3、SQLite、现有 JVM 与隔离设备测试。

## 范围与交互约定

- 「档案」内提供明确的「通话记录」入口。列表显示应用内已保存的来电，不限号码和日期，包括无录音、未接、拒接和未计分记录。已合并的筛选事实与系统通话记录展示为一行。录音库中没有关联通话的独立归档继续由录音入口访问，删除号码后的保留录音也可从该入口找回。
- 按来电时间降序展示，同一时间用通话 ID 作稳定次序。列表标题是来电日期和时间，可用号码作副标题以区分不同来电人。沿用通话的工作分类和时区。绿点表示工作时间内，红点表示工作时间外，并提供对应无障碍文字。
- 录音、转写各有状态图标。有可播放音频时，录音图标直接播放或停止。有转写时，转写图标打开全文弹窗。缺少内容时显示不可操作状态。点图标不触发整行导航，点行的其他位置打开详情。
- 详情显示号码、完整来电日期和时间、时长、工作分类、录音及转写状态。时长未知时显示「未知」。有音频可播放及停止。缺少转写且有音频时提供「转为文本」，复用随包离线转写，并沿用现有时长限制及错误提示。已有转写可展开、收起，并可点击「AI 分析」，包括只有文本的恢复归档。
- 手动分析完成后保存结果和建议，用户逐项核对并确认写入所选字段。暂不确认时保留结果。后台自动分析沿用现有启用条件、原文证据规则、空字段补全和异议纠正，保留已有且无异议的值。

## 阶段 1 · 通话列表查询与归档状态投影

**代码落点**

- `contracts/src/main/kotlin/com/greatwall/callring/contracts/callers/CallersApi.kt` 与 `contracts/src/main/kotlin/com/greatwall/callring/contracts/CallringApi.kt`，定义通话列表查询、行状态和稳定分页游标。
- `app/src/main/kotlin/com/greatwall/callring/data/report/SqliteReportCalls.kt` 及现有通话读取端口，复用可见通话和已合并事实的读取语义。
- `app/src/main/kotlin/com/greatwall/callring/data/recording/RecordingArchivePages.kt` 及录音读取适配器，提供按当前页通话 ID 批量获取录音和转写状态的路径。

- [x] 先覆盖多号码、跨日期、相同时间戳、已合并通话、无录音、只有转写和空列表。断言分页无重漏，隐藏的合并中间记录不额外出现。
- [x] 在 SQLite 层按时间和 ID 有界分页。保留通话 ID 作为详情入口，复用现有通话到归档映射解析可播放归档 ID。按页批量读取归档元数据，区分有音频、缺音频、有转写、归档不可读和没有归档。列表查询不读取音频或转写全文。
- [x] 通过正式变更订阅刷新列表。新通话、归档关联、音频和转写状态变化更新相关行。并发分页遇到旧游标时重载，迟到结果不得覆盖新页。验证无关设置变化不重读列表。

## 阶段 2 · 档案入口、列表操作与单次详情

**代码落点**

- `ui/src/main/kotlin/com/greatwall/callring/ui/app/CallringApp.kt`、`ui/src/main/kotlin/com/greatwall/callring/ui/app/RecordingRoutes.kt`，接通「档案 → 通话记录 → 单次通话」及既有报表到详情的入口。
- 新建 `ui/src/main/kotlin/com/greatwall/callring/ui/feature/callers/CallHistoryScreen.kt` 和同目录 `CallHistoryViewModel.kt`，负责列表、图标操作、弹窗与分页状态。
- `ui/src/main/kotlin/com/greatwall/callring/ui/feature/recording/ArchiveScreen.kt` 和 `ui/src/main/kotlin/com/greatwall/callring/ui/feature/recording/ArchiveViewModel.kt`，复用录音详情，加入通话事实和分离后的操作状态。

- [x] 先写界面回归用例。点击音频图标只调用播放或停止，点击转写图标只打开对应全文弹窗，点击行打开正确通话，返回时保留滚动位置。
- [x] 以通话事实作为详情页基线。录音归档未生成时仍展示时间、时长和工作分类。复用现有播放观察和归档读取，播放失败留在当前页提示，全文弹窗支持关闭和失败重试。
- [x] 接好「转为文本」、全文展开与「AI 分析」的独立动作。以转写状态决定分析入口，以音频状态决定播放和转写入口。验证无音频文本也能分析，转写成功不会立即调用分析。

## 阶段 3 · 手动分析与确认写档

**代码落点**

- `contracts/src/main/kotlin/com/greatwall/callring/contracts/recording/RecordingApi.kt`，提供独立的手动文本分析、候选读取及确认写入契约。
- `backend/src/main/kotlin/com/greatwall/callring/application/recording/RecordingService.kt`、`backend/src/main/kotlin/com/greatwall/callring/application/recording/RecordingWorkflow.kt`，在正式业务入口记录运行意图并隔离两条写档路径。
- `backend/src/main/kotlin/com/greatwall/callring/application/callers/CallerEnrichmentService.kt`，复用字段证据、档案版本及号码删除保护，在正式写入事务中校验选择和版本。

- [x] 增加工作流回归，断言手动分析成功后结果可读，档案保持原值。原先没有档案时，分析期间也不创建空档案。后台扫描、页面退出或重启均不会代替用户确认。
- [x] 持久化手动分析意图、结果版本和确认状态。手动分析只读已有转写，模型结果保存为待确认。候选仅包含通过现有原文证据校验的姓名、部门、自述职位和所属地区。界面显示当前值、建议值及原文，用户可勾选字段后确认。
- [x] 确认命令绑定目标号码、分析结果版本及用户看到的档案状态。提交时复核字段版本、号码删除时间和档案身份，发现冲突则返回最新值供重新确认。只更新选中字段及其来源，重复确认同一结果不得重复写入。新分析替换旧建议后，旧确认失效。
- [x] 将自动补全继续接到自动分析结果。覆盖补空、纠正异议、保留非空无异议字段、没有有效证据、删除后重建和补全失败后本地重试。检查既有 AI 分析与重试消费者，确保所有手动入口受同一写档确认边界约束，并按项目约定核对 AI 协议基线。

## 阶段 4 · 迁移、恢复与最终验证

**代码落点**

- `app/src/main/kotlin/com/greatwall/callring/data/recording/RecordingSchema.kt` 与 `app/src/main/kotlin/com/greatwall/callring/data/backup/ArchiveBackupFields.kt`，覆盖归档编码、迁移及备份恢复。
- `PROJECT.md`、`README.md`，同步职责、行为、入口及验收记录。合并文档时保留工作区已有修改。

- [x] 迁移旧归档。保留既有录音、转写、分析及来源。可识别的旧手动排队或处理中任务，后续完成时进入待确认。旧手动任务已经保存分析但尚待本地补全时，保留分析并转为待确认。已经完成的历史结果保持可读。来源不明确的旧待处理数据须经过明确裁决才可写档案。
- [x] 验证恢复后待确认结果可读，恢复前的确认凭证失效。重新确认时以恢复后的档案为准，继续遵守现有恢复暂停及缺音频规则。若档案提交完成但归档确认状态尚未写回，重启后应能识别已提交结果，防止重复写入或重复调用模型。
- [x] 运行 `python tools/verify_backend.py`，检查统一门摘要中的任务执行状态、测试计数和 lint。用隔离模拟器执行 `python tools/verify_ui.py --device <隔离设备序号>`，并运行后端专项验证手动确认、自动补全、迁移和备份恢复。使用固定音频及模拟 AI，确保调度测试满足网络门控条件。设备用例覆盖列表播放、弹窗、详情、确认写档和重启。
- [x] 复核上一轮设备验收暴露的两项问题。档案编辑保存回调专项需要在目标屏幕尺寸上确认可达及可点击。自动补全专项需要区分设备无默认网络导致的排队与联网条件下的实际工作流结果。

## 交付判定

全部已保存来电可通过统一列表找到。录音和转写图标能在列表内完成各自操作。详情可独立转写和分析，只有文本也能分析。手动分析在用户确认之前对档案零写入。后台自动分析仍按现行规则补空和纠正异议。旧数据、备份恢复、号码删除保护与确认幂等均有回归证据。
