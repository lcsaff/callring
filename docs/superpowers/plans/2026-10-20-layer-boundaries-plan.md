# 分层边界与业务链路解耦 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本文是用户要求的分阶段实施计划；用户后续执行授权已生效，任务在当前会话持续推进，不派生用户会话。

**Goal:** 完成 app/ui/backend 的单向依赖收口、层内目录整理，并解决全量状态读写、无关刷新、AI 编号依赖、宽上下文和存储强转五项耦合。

**Architecture:** 保留三大职责层，新增纯 JVM contracts；以业务窄端口、同库事务会话和实际提交变更取代 BackendState 全量协议；累计版本快照支撑精准且不漏失的订阅。AI 操作改为具名目录，app 只保留装配、系统入口和平台/存储适配。

**Tech Stack:** 现有 Kotlin/Java、Gradle、Android/Compose、SQLite、coroutines、JUnit4/Robolectric 与设备测试；不切换技术栈。

---

日期：2026-10-20。基线：`fa886a431e44424fe9b483380ea97f6d792c003b`。

状态：用户随后已要求本会话作为主负责人推进，实施已启动；任务实际进展及验证见 [执行记录](../../plans/layer-boundaries-evidence/execution-status.md)。依据：[设计方案](../specs/2026-10-20-layer-boundaries-design.md)、[文件归属清单](../specs/2026-10-20-layer-boundaries-file-map.md)。本计划落盘时仅授权文档，后续执行授权来自用户的新指令，不将文档落盘本身视为实施授权。

## 1. 执行约定与里程碑

- 设计与本计划已进入用户要求的实施阶段；在独立分支逐切片实施，不访问用户生产数据库，不自动发布 APK。上一轮“只交付文档”的边界已由新执行指令更新。
- 执行前检查当前工作区和其他任务的修改；使用合适的当前工作区或隔离工作树，不覆盖无关改动。
- 每个任务先建立能暴露目标耦合/语义退化的测试，再迁移实现、跑定向验证、检查差异。纯搬文件只跑相关编译和既有测试，不写机械镜像测试。
- 机械移动提交与行为修改提交分开；每个业务切片可编译且只有一个有效写入口；提交精确暂存任务文件，禁止一揽子暂存用户改动。
- 现有 tests 可保持类名便于回归筛选；跟随包移动时同步命令与设备 runner。新增测试使用下列明确目标路径。
- 各阶段通过验证再进入下一依赖阶段；阶段失败修复当前切片，不恢复永久全量兜底。

| 阶段 | 任务 | 阶段产物/退出门 | 工期 |
|---|---|---|---|
| S0 基线 | T01–T02 | 公开契约、操作兼容、读写与刷新基线；所有路径归属明确 | 1～2 人日 |
| S1 硬边界 | T03–T05 | contracts 编译隔离、纯领域、窄存储会话与显式资源 | 2～3 人日 |
| S2 业务切片 | T06–T10 | 所有普通业务脱离全量状态，BackendContext 退场 | 4～7 人日 |
| S3 刷新收口 | T11 | 累计失效、全部订阅和平台通知收口，无漏刷/无关刷新 | 2～3 人日 |
| S4 AI 操作 | T12 | 全部工具具名化、兼容清单及回执/作用域覆盖 | 1～2 人日 |
| S5 整理验收 | T13–T15 | 全文件整理、旧协议删除、CI 与完整验收证据 | 2～3 人日 |

合计 12～20 人日，按 16 人日安排。各任务工时包含其定向验证，不在此基础上重复加测试工时。S2 必须同步产出正确 ChangeImpact；S3 是全部消费者/遗留通知收口，并非首次开始考虑刷新。阶段顺序为 S0→S1→S2→S3→S4→S5；业务片内按设置→档案→通话→报表→录音/组合操作推进。

## 2. 路径约定与新文件责任

以下缩写仅用于压缩文件清单：

```text
B = backend/src/main/kotlin/com/greatwall/callring
A = app/src/main/kotlin/com/greatwall/callring
U = ui/src/main/kotlin/com/greatwall/callring/ui
C = contracts/src/main/kotlin/com/greatwall/callring/contracts
BT = backend/src/test/kotlin/com/greatwall/callring
AT = app/src/test/kotlin/com/greatwall/callring
UT = ui/src/test/kotlin/com/greatwall/callring/ui
```

设计中命名的目标类型在对应任务创建，未创建前不移除调用方需要的旧入口。文件迁移清单逐项描述现有 227 个生产文件的目标归属；新建核心文件如下，除组件身份例外均使用与目录一致的包名。

| 新建文件（相对上述根） | 单一职责 | 首次创建 |
|---|---|---|
| C/CallringApi.kt；C/common/ApiResult.kt、Page.kt、ApiError.kt | 对外聚合及公共协议 | T03 |
| C/change/ChangeFeed.kt、InvalidationSnapshot.kt、ChangeSubscription.kt | 观察协议与订阅令牌 | T05 |
| C/command/CommandContext.kt、RevisionVector.kt | 跨层命令前置条件 | T03 |
| B/application/ports/store/CoreUnitOfWork.kt | 同库读写会话；组合窄能力，不包含平台服务 | T05 |
| B/application/ports/store/CallerStore.kt、SettingsStore.kt、CallStore.kt、ReportReader.kt、DecisionInputsReader.kt、FullSnapshotReader.kt | 有类型、有限范围的数据能力 | T05 声明，T06–T09 接入 |
| B/application/coordination/MutationDelta.kt、CommandCommitter.kt、InvalidationTracker.kt | 实际变化、提交协调、累计失效 | T05 |
| B/application/coordination/UseCaseRunner.kt、DecisionStateHolder.kt | 错误/调度边界、决策输入持有 | T04/T05 |
| B/domain/model/DecisionInputs.kt；B/application/mapping/ContractMappings.kt | 来电最小事实、领域/契约转换 | T04 |
| A/bootstrap/StorageResources.kt | 懒资源、明确查询/计数/关闭能力 | T05 |
| A/data/database/SqliteCoreUnitOfWork.kt、SqliteReadSession.kt、SqliteWriteSession.kt | 单连接同库会话、事务边界 | T05 |
| A/bootstrap/CoreAssembly.kt、RecordingAssembly.kt、ConversationAssembly.kt、UiBridgeAssembly.kt | 按能力装配，无业务规则 | T10/T13 |
| U/state/ChangeObserver.kt、RefreshCoordinator.kt | 令牌观察、单航班重载与参数代次 | T05/T11 |
| B/application/conversation/operation/OperationId.kt、OperationDefinition.kt、OperationCatalog.kt | 具名操作、兼容元数据、目录校验 | T12 |
| tools/check_architecture.py、tools/test_architecture.py | 路径/依赖/禁止模式与负例 | T03 起逐步扩展 |
| docs/contracts/current-api.md、ai-operation-compatibility.json | 当前公开契约说明、AI 兼容清单 | T02/T03 |

不把全部新接口和实现预先脚手架化；按任务创建实际需要的部分。业务端口签名按设计规定的数据闭包与现有用例定义，不能为了共用一个接口退回 BackendState 或 Map<String, Any>。

## 3. S0：固定基线

### T01 工作区、行为与数据范围基线

**输入文件：** `PROJECT.md`、三模块 build.gradle、`B/application/BackendContext.kt`、`B/application/BusinessScopes.kt`、`A/data/SqliteBackendStore.kt`、现有测试与工具。

**证据目标：** `docs/plans/layer-boundaries-evidence/baseline.md`，只在实际执行时创建。

- [x] 记录 HEAD、工作区差异、工具链、测试和设备可用性；检查新提交是否改变本文基线。
- [x] 运行第 9 节当前基线命令，记录实际测试项、失败/跳过与耗时；不能沿用旧报告。
- [x] 建立固定的 1千/1万无关通话合成数据夹具，包含双号码、岗位引用、历史配置、录音身份及旧编码；仅用于隔离测试。
- [x] 在 `AT/data/LocalAccessContractTest.kt` 先记录真实 Graph 的全状态装载、修订推进和通知领域，固定读免打扰、改 A 名称、查 A 报表、登记来电的基线。现有耦合用 characterization 记录，不把全量读取写成永久断言。SQL 表/返回行和窄端口探针在 T05 新适配器接入前建立，作为任何存储行为修改的前置门；不将全状态装载计数冒充 SQL 行数。
- [x] 补齐错误/回滚/无操作/取消基线，保存原始分类、ID 与序列、导入去重、CSV/报表样本；执行现有 DomainRegressionTest、CallCorrelationTest、StoragePolicyCharacterization 等回归。

**门：** 行为样本可重复、范围探针可信；此阶段不宣称 P1 已解决。覆盖 A1/A2/A7 的基线。

### T02 公开契约、工具与订阅清单

**输入文件：** `B/application/api/*.kt`、`B/application/conversation/*.kt`、`U/state/*.kt`、所有 ViewModel、widget/overlay，以及设计 §6.4。

- [x] 在 `docs/contracts/ai-operation-compatibility.json` 逐项记录当前全部操作的 toolName、O/X code、legacyIndex、schema、默认值、读写/确认/回执分类、发现顺序；包含 extensions，不只前 72 个。
- [x] 记录公开 API 方法/参数/默认值、D 域、错误码和历史持久化值；拆分“公共视图”与“含密钥内部配置”。区分必须保持的对外工具协议与允许同步调整的内部 Kotlin API，登记旧录音配置管理方法移到内部端口等差异。
- [x] 在 `docs/plans/layer-boundaries-evidence/subscription-matrix.md` 记录每个实际消费者读取的字段和依赖领域/实体；矩阵至少覆盖首页、搜索、A/B 编辑/报表、widget i/j、overlay、归档详情、AI 配置。
- [x] 在 `UT/state/RefreshIsolationTest.kt` 建立真实 loader 调用计数夹具，记录当前过度刷新的入口；在后续切片将目标正反例转为必须通过的断言。
- [x] 复核迁移清单覆盖全部生产文件，新增的生产文件也必须归属设计中的职责目录。

**门：** 所有操作和实际订阅者都有明确记录，兼容差异可自动比较。覆盖 A0/A3/A4。

## 4. S1：建立硬边界与公共机制

### T03 contracts 抽离及编译隔离

**修改：** `settings.gradle`、`contracts/build.gradle`、`ui/build.gradle`、`backend/build.gradle`、`app/build.gradle`，旧 `B/application/api`，所有契约消费者与 `tools/check_backend_boundaries.py`。

**创建：** C 下设计规定的分域接口/DTO；`tools/check_architecture.py`、`tools/test_architecture.py`、`docs/contracts/current-api.md`。

- [x] 先建立架构负例：ui→backend、domain→application、contracts→Android、data→UI 均被拒绝；正例允许装配根引用具体实现。
- [x] 将协议声明移到 C，按业务拆分主契约；厂商策略、验证、Unavailable、内部密钥配置留给 T04/T10 的实现目标，不作为契约实现泄露。
- [x] 为保持本任务可编译，随抽离同步完成旧录音管理方法到内部端口的签名迁移、策略入口的初步搬迁；后续 T04/T10 再完成职责拆细。CredentialDialog 改读新增安全 copyToAnalysisProfile 投影，backend 计算复制后 AUTO 解析结果；UI 不构造 AiConnection 或调用厂商解析器。
- [x] 将 UI 依赖改为 contracts，backend/app 明确声明 contracts；一次更新全部消费者包引用，不添加长期旧包别名。
- [x] 旧 v1 文档保留为历史；边界脚本改查当前公开契约与兼容清单，不对冻结旧单文件强制逐字相等。
- [x] 检查 `:ui:dependencies --configuration debugCompileClasspath` 中无 backend；完成三模块定向编译及全部契约兼容测试。

**门：** UI 无法编译访问 backend 实现；完整工程可构建。覆盖 A0/A4。

### T04 领域模型及公共执行边界

**修改/拆分：** 六个 domain 文件、`BackendContext.kt` 的纯 helper、`CallerProjection.kt`、`ReportCalculator.kt`、`SettingsValidation.kt`、`api/ThinkingPolicy.kt`、`api/ConversationCompatibility.kt`。

**创建：** `B/domain/model/DecisionInputs.kt`、领域原生设置/分类/配置类型、`B/application/mapping/ContractMappings.kt`、`B/application/coordination/UseCaseRunner.kt`。

- [x] 在 `BT/domain/DomainIsolationTest.kt` 和契约映射回归中固定号码、分类、评分、时区、旧枚举含义与深不可变行为。
- [x] 将领域类型与对外 DTO 分开，领域不再 import contracts/application；映射放 application，SQL 映射不夹带显示文案。
- [x] 将厂商/思考策略、连接校验归纯业务模型；HTTP endpoint/协议编码归平台，安全视图与密钥输入语义保留。
- [x] 抽出号码、分页、CSV、范围和校验 helper 的合适归属；UseCaseRunner 只负责调度、取消和错误映射。
- [x] 引入最小 DecisionInputs，不复制 calls/archives；保留按实际时间分类和原来电准备/提交协议。

**门：** 领域边界检查通过，现有算法样本结果一致；不以复制全部 API 类型替代模型划分。覆盖 A0/A2/A5/A7。

### T05 窄会话、提交变更与显式懒资源

**修改：** `BackendPorts.kt`、`BusinessRevisionCoordinator.kt`、`LazyBackendStore.kt`、`SqliteBackendStore.kt`、Graph；旧入口此时只服务未迁移切片。

**创建：** 第 2 节 CoreUnitOfWork/窄端口、MutationDelta/CommandCommitter/InvalidationTracker、SQLite 会话、StorageResources、contracts/change 和 UI ChangeObserver/RefreshCoordinator。

- [ ] 在 `BT/coordination/InvalidationTrackerTest.kt` 验证累计域/实体令牌、StateFlow 跳帧、每域 1024 项超限安全降级、epoch 切换、无操作零失效。
- [ ] 在 `AT/data/ScopedTransactionTest.kt` 验证同连接读快照、跨表回滚、变更仅提交后出现、失败不提升修订、同库单提交单次逻辑修订。
- [ ] 在 `AT/bootstrap/StorageResourcesTest.kt` 使用非 SQLite 假资源验证 scoped query、计数不打开库、首次 IO 唯一实例、并发关闭/开启、重复 close 和关闭后各端口一致拒绝。
- [ ] 实现 fence→SQL 固定顺序、后台准备与可取消阶段、短小不可取消提交/发布；禁止 SQL 回调反向取得 fence。
- [ ] 定义读令牌前后复核和单航班 pending 行为；旧 DataChange 消费者通过明确过渡适配接入，已迁移路径不得走全域通知兜底。
- [ ] 用 T06 所需最小接口验证完整链路；此阶段不提前编写未使用的通用 repository 框架。
- [ ] 在更改任何存储行为前补齐从 T01 分拆的 SQL 表/返回行与窄端口探针，先跑旧实现记录范围，再以相同夹具验证新适配器；未完成前不得推进 T06 的存储改造。该顺序调整只允许 contracts/模型边界先行，不放宽 A1 的最终证据要求。

**门：** P5 的替换能力成立；后续片可在不取得 BackendState 的条件下完成读写。覆盖 A1/A2/A3/A6/A7。

## 5. S2：按业务切片迁移

### T06 设置切片

**修改/拆分：** `SettingsService.kt`、`SettingsValidation.kt`、`QuietControls.kt`、`UiEditsService.kt`、`A/data/SettingsStore.kt`、`ScheduleStore.kt`、`AndroidPreferences.kt`、相关 UI 设置 ViewModel。

**目标：** `B/application/settings/{QuietSettingsService,ScoreSettingsService,StatisticsSettingsService,WorkScheduleService,CalendarService,LeaveService,PositionService,AppearanceService}.kt`；SQLite 实现归 `A/data/settings`，偏好实现归 `A/data/preferences`。

- [ ] 在 LocalAccessContractTest 新增硬断言：读/写免打扰、评分、悬浮开关不读 calls/profiles；外观不访问核心库；1千/1万无关通话下访问范围一致。
- [ ] 实现单配置定向读取、原值比较、定向写入和实际 ChangeImpact；岗位唯一性/引用使用 EXISTS/目标查询。
- [ ] 将日历/请假/工作安排的受影响区间及历史更正作为专门批处理；受影响数据分批读取，同一必要事务内提交；保留审计、配置版本、DST、原始分类。
- [ ] 只在真实决策输入变化时更新 DecisionStateHolder；评分或外观不重建镜像。
- [ ] 接入本切片精准通知和 UI 订阅；运行现有设置/工作安排/日历回归及 A/B 无关刷新负例。

**门：** 普通配置不依赖全量状态；历史更正结果不变。覆盖 P1/P2/P4，A1/A2/A3/A5。

### T07 档案、岗位关系与编辑切片

**修改/拆分：** `CallersService.kt`、`CallerProjection.kt`、`UiEditsService.kt` 的 profile 分支、`A/data/ProfileStore.kt`、callers UI 与 widget/overlay 相关投影。

**目标：** `B/application/callers/{CallerQueryService,CallerCommandService,CallerProjection}.kt`、`A/data/callers/SqliteCallerStore.kt`。

- [ ] 在 `AT/data/CallerAccessContractTest.kt` 验证 A 编辑只查 A/岗位/必要版本，列表 SQL 分页且无 N+1 全量历史读取。
- [ ] 使用同库写会话实现档案更新、白名单、职位引用校验；保留基线/dirtyFields 合并与无关字段并发不冲突。
- [ ] 以实际字段变化发布 A 范围；白名单影响决策，姓名/部门只影响展示；本人岗位和职位修改传播真实引用关系。
- [ ] 调整调用方只拿需要的 CallersApi/Reader；A/B 报表和编辑负例、搜索列表和最新来电正例必须同时通过。

**门：** 不加载其他号码/全量通话；UI/AI 两条入口使用相同业务命令。覆盖 P1/P2/P4，A1/A2/A3/A5/A7。

### T08 来电、系统日志与关联切片

**修改/拆分：** `BackendContext.kt` 的 recordIncoming/updateDecision/importSystemCalls/statisticsStart/hasRecentPending、`CallCorrelation.kt`、`DecisionSnapshot.kt`、`A/data/CallStore.kt`、`platform/calling` 及系统入口委托。

**目标：** `B/application/calling/{IncomingCallService,CallLogImportService,DecisionQueryService}.kt`、`A/data/calling/SqliteCallStore.kt`。

- [ ] 在 `AT/data/CallAccessContractTest.kt` 验证目标 ID/会话/号码/时间闭包；其他号码数据量变化不扩大登记读取范围。
- [ ] 为旧 logId、旧新号码/时间/账户变化加载完整候选闭包，保持全局唯一性、重复导入幂等、跨账户不误配；读取该号码全部候选只能在算法确有需要时发生并记录。
- [ ] 定向插入/更正/合并，保持自增序列、隐藏记录、原始事实和统计启用区间；待补全检查用 EXISTS，最新记录用稳定排序 LIMIT。
- [ ] 来电决策读取独立镜像，通话结果更新不重建决策镜像；调度/通知在锁外，后台持久化失败沿用现有降级。
- [ ] 运行 CallCorrelationTest、IncomingCallCoordinatorTest、SessionGate/PreparationGate 与相关系统适配回归。

**门：** 来电链路不等待全量业务状态，历史关联语义和期限保护保持。覆盖 P1/P2/P4，A1/A2/A3/A7/A9。

### T09 报表、首页、源查询与完整快照

**修改/拆分：** `HomeService.kt`、`ReportsService.kt`、`ReportCalculator.kt`、累加器、`CoreSnapshotProjection.kt`、`A/data/SqliteScopedReadAdapter.kt`、`ScopedQueryAssembly.kt`、`CompleteSnapshotAssembly.kt`。

**目标：** `B/application/report`、`home`、`conversation/query`、`A/data/report`、`data/query`。

- [ ] 在 `AT/data/ReportReadContractTest.kt` 固定范围、过滤、分页总数、重复评分边界前置记录、未知时长、本人岗位、CSV 转义/汇总和 DST 样本。
- [ ] 按号码/时间范围流式取事实；领域累加器唯一实现公式；首页组合最小投影，不载入全部业务状态。
- [ ] AI SQL 源不直接调用业务服务或 platform.ai JSON 解析器；把持久化 JSON codec 归 data，计算移纯领域/应用查询层，保持 D01–D21、游标、scope/version、完整性语义。
- [ ] 源版本按 D 域真实依赖推进：同域变化使游标/缓存失效，无关域变化不误失效；QueryStamp.scopeRevision 保持既有披露/冲突语义，不能替换为 UI 观察令牌。
- [ ] 显式 FullSnapshotReader 取代 BackendStore.captureFull；保持大小限制、审计和秘密脱敏，普通查询/闲聊/发现零完整快照。
- [ ] 补 `AT/data/QuerySnapshotConsistencyTest.kt`：读令牌与快照数据一致；并发提交时重读或拒绝过期，不能给旧结果贴新版本。

**门：** 首页、报表、普通源查询脱离全状态；完整快照仍单独可用。覆盖 P1/P2/P4，A1/A2/A3/A7。

### T10 录音、AI 设置、组合删除与总装配

**修改/拆分：** `A/platform/ai/{AiSettingsService,AndroidRecordingRuntime,AndroidRecordingStore}.kt`、`platform/callers/NumberDeletionService.kt`、`B/application/Recording*.kt`、`SharedBusinessCommands.kt`、BackendGraph 和相关装配。

**目标：** `B/application/recording/{RecordingService,AiSettingsService,IdentityAdoptionService}.kt`、`B/application/callers/NumberDeletionService.kt`、recording 端口、app 的 data/recording 与 platform/recording/ai。

- [ ] 在 `BT/recording/AiSettingsMergeTest.kt`、`BT/callers/NumberDeletionWorkflowTest.kt` 用窄假端口覆盖密钥修改、复制、权限、字段冲突、busy、数量变化、部分失败与重试。
- [ ] 单号码归档/绑定查询与音频大小端口替代全归档扫描和 backend File 调用；保留录音→核心→绑定顺序、实际部分成功通知和 continueOwnCommits 语义。
- [ ] AI 配置和录音业务 facade 移 backend，Android Context、MediaPlayer、目录、录音/网络 IO 留平台；生产能力由装配显式补齐。
- [ ] 拆出 Core/Recording/Conversation/UiBridgeAssembly；UI 安装状态/通知/来电显示通过桥注入，Graph 无具体 widget 查询和业务流程。
- [ ] 所有服务改窄依赖，移除生产 BackendContext；每组服务用仅声明依赖的假端口完成测试，不创建 Context2/万能 Dependencies。
- [ ] 运行原录音独立登记、命令上下文、AI 工具业务集成、号码删除及快照装配回归。

**门：** P4 退场完成，平台业务迁移完成，组合操作语义不降级。覆盖 P1/P2/P4/P5，A1/A2/A5/A6/A7。

## 6. S3：刷新链路整体收口

### T11 消费者、平台发布与加载协调

**修改：** `BusinessRevisionCoordinator` 新职责文件、所有平台发布点；`U/state/{UiViewModel,SnapshotViewModel,ImmediateEditor,AsyncState}.kt`；所有 feature ViewModel、widget、overlay、主题/导航观察点。

- [ ] 全部生产通知改为累计失效快照，移除旧 DataChange 单事件和全域兼容适配；runtime、diagnostics、录音进度、偏好等发布明确范围。
- [ ] 按 T02 实际依赖清单逐个接入 ChangeObserver；number/widgetId/archiveId 的匹配归各域，不复用一个跨域实体列表。
- [ ] 刷新协调统一处理初次加载、参数变化、加载中提交、订阅恢复和用户显式刷新；防止旧数据覆盖、取消后 pending 丢失。
- [ ] 移除本地写回调无条件 refresh 与 feed 重复触发；无操作结果直接更新状态或保持现有状态。
- [ ] 在 `UT/state/ChangeObserverTest.kt` 和 RefreshIsolationTest 执行完整矩阵：A/B、i/j、慢订阅连续 A/B 变化、1025 实体变化、域级传播、恢复、加载并发、无操作、重复发布。
- [ ] 用真实 loader 查询计数验证：无关写入查询增量为 0；单次稳定提交在已初始化订阅上没有双重刷新；多提交合并后最终数据正确。

**门：** P2 全部完成，无旧全域通知路径；A3 及相关 A7 通过。广域失效只允许设计指定的真实领域依赖/超限降级。

## 7. S4：AI 具名操作目录

### T12 替换所有数字语义分派

**修改/迁移：** ToolRegistry、ToolExecutor、ToolBatchEngine、OperationContract、OperationScopePlanner、ScopedToolReader、ReceiptVerifier、ConfirmationTargets、CapabilityCatalog、TaskPlanner、边界检查与操作覆盖测试。

**创建：** `B/application/conversation/operation/{OperationId,OperationDefinition,OperationCatalog}.kt`、`BT/conversation/OperationCatalogTest.kt`。

- [ ] 从 T02 兼容清单建立显式 OperationId/toolName/operationCode/legacyIndex/displayOrder 映射；不由 ordinal/列表长度派生。
- [ ] 在 OperationCatalogTest 中随机重排注册顺序并验证每个工具的 schema、确认、读写、作用域、执行器和回执策略不变。
- [ ] 将所有 index==数字、数字集合和范围推断替换为具名 OperationId 或目录元数据；legacyIndex 只留兼容映射/历史展示。
- [ ] 建立重复 ID/工具名/兼容码、缺执行器、写操作缺作用域、回执或确认规则等负例，目录构造立即失败。
- [ ] 对原全部工具执行契约兼容比较；ToolRegistryTest、ToolOperationCoverageTest、OperationScopePlannerTest、ReceiptVerifierTest、ToolBatchEngineTest、ScopedToolReaderTest 等语义回归仍需通过。
- [ ] 检查 AI 确认取消、未授权、目标改变、自有提交继续、COMMITTED_UNVERIFIED、秘密脱敏和闲聊零源查询，禁止借整理修改行为。

**门：** P3 完成；A4/A7 通过，发现排序和对外协议不变。

## 8. S5：结构整理与最终交付

### T13 清理全量协议与剩余目录

**修改：** 生产迁移清单列出的所有剩余项及其测试引用；根 Android 组件仅提取逻辑，保留 FQCN。

- [ ] 逐项完成文件清单：机械移动，拆混合职责，收紧 internal，删除只为迁移存在的转发/别名/旧入口；清单记录实际目标和任务。
- [ ] 删除 BackendStore.read/transaction 全量协议、StorageSession.load/save、coreScopeValues；完整快照走 FullSnapshotReader 专用模型，普通服务禁止引用。
- [ ] UI designsystem 中业务卡片移 feature/shared；state 与 designsystem 反向引用归到 app/feature 组合层；不改视觉样式。
- [ ] AppearanceSettings→ThemeSpec 的适配归 UI app/bridge，designsystem 只处理视觉参数；主题配置和小组件/overlay 的最终视觉保持一致。
- [ ] 根 Service/Receiver 壳只做系统调用适配/委托，实际控制器进 entrypoint；更新调度、资源和测试引用但不修改组件身份。
- [ ] 更新 PROJECT.md、README.md 当前架构与验证命令；保留历史文档为历史，不批量改写旧审查证据。

**门：** 所有生产文件有归属，P1/P4 旧入口无残留；不存在两套生产业务实现。

### T14 架构守卫与 CI

**修改：** `tools/check_backend_boundaries.py`、`tools/test_backend_boundaries.py`、`tools/check_architecture.py`、`tools/test_architecture.py`、`tools/verify_backend.py`、`tools/verify_ui.py`、`.github/workflows/build.yml` 及使用旧验证入口的 release workflow。

- [ ] 硬检查 D1–D10、UI 编译类路径、domain 纯净、旧全量协议、数字操作分派、具体存储强转；测试 fixture 覆盖允许装配及禁止反向依赖的正负例。
- [ ] 仅检查具体危险模式，避免误拒绝合法 sealed 类型匹配/JSON 转换；不能用 broad allowlist 放过整个 app/bootstrap。
- [ ] contracts:test 和架构检查纳入统一命令及 CI；Windows 使用 gradlew.bat，CI 沿用实际 Gradle 入口，不新增不必要工具链。
- [ ] CI 触发覆盖实际 codex/**/PR 使用方式；依赖变化在普通构建中即可发现，发布流程继续遵守现有签名和版本规则。

**门：** 同类耦合重新引入会在 CI 中失败；旧工具入口可运行且包含新模块验证。

### T15 全量验收、设备与证据报告

**产物：** `docs/plans/layer-boundaries-evidence/acceptance.md` 和实际测试/构建日志索引，只在执行后创建。

- [ ] 执行第 9 节最终统一验证，记录执行 commit、命令、退出码、JUnit 数量、失败/跳过；结果与本阶段源码一致。
- [ ] 在 1千/1万数据夹具上执行访问范围与刷新矩阵；记录普通入口全状态装载/完整快照次数均为 0、无关查询增量为 0。
- [ ] 验证最终全部操作兼容清单、数据样本/旧编码、回滚、并发、取消、密钥和回执，不以单一 happy path 代替验收。
- [ ] 有隔离设备时运行现有 backendQa 设备验证并补新窄端口/组件测试；验证持久 Job、已有 widget、overlay、权限和后台恢复。不得清理用户数据或覆盖主安装做测试。
- [ ] 真实 SIM/待机/重启、覆盖升级和真实下载如设备或条件不具备，明确列为未验证；不能声称原有未验收链路因整理完成而通过。
- [ ] 按设计 A0–A9、下表 P1–P5 检查逐项结论；仅在所需证据存在时勾选阶段完成。

## 9. 验证命令与期望结果

### 9.1 当前基线（实施 T01 时执行）

在项目根目录 PowerShell：

```powershell
python tools/check_backend_boundaries.py
python -m unittest discover -s tools -p test_backend_boundaries.py
.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug --console=plain
```

期望：所有命令退出 0；Gradle 明确 BUILD SUCCESSFUL；JUnit 无失败/错误，跳过项单列。基线失败先查明，不带病宣称迁移保持行为。

### 9.2 迁移后的统一门

```powershell
python tools/check_architecture.py
python tools/check_backend_boundaries.py
python -m unittest discover -s tools -p test_architecture.py
python -m unittest discover -s tools -p test_backend_boundaries.py
.\gradlew.bat :contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug --console=plain
.\gradlew.bat :app:assembleRelease :app:assembleBackendQa :app:assembleDebugAndroidTest :ui:assembleDebugAndroidTest --console=plain
.\gradlew.bat :ui:dependencies --configuration debugCompileClasspath --console=plain
```

期望：前述检查、测试、构建均退出 0；UI debugCompileClasspath 含 contracts，不含 backend；Release 仅本地构建校验，不发布；签名按既有配置读取，不输出密钥。中间阶段某个新任务/脚本尚未建立时，使用当前已建立的定向门，不跳过对应里程碑最终门。

### 9.3 定向测试

新增测试使用本文路径中的包名；迁移现有测试若改包，立即同步筛选命令。示例：

```powershell
.\gradlew.bat :backend:test --tests "com.greatwall.callring.coordination.InvalidationTrackerTest" --console=plain
.\gradlew.bat :app:testDebugUnitTest --tests "com.greatwall.callring.data.LocalAccessContractTest" --tests "com.greatwall.callring.bootstrap.StorageResourcesTest" --console=plain
.\gradlew.bat :ui:testDebugUnitTest --tests "com.greatwall.callring.ui.state.RefreshIsolationTest" --tests "com.greatwall.callring.ui.state.ChangeObserverTest" --console=plain
.\gradlew.bat :backend:test --tests "com.greatwall.callring.conversation.OperationCatalogTest" --console=plain
```

期望：测试确有执行而非零匹配；目标断言在旧行为/负例上失败，在新实现上通过。不在文档阶段创建这些测试或声称已通过。

### 9.4 设备验证

在确认可用隔离设备后，执行 `python tools/verify_backend.py --device <实际 ADB serial>`；尖括号是由执行时 adb devices 实际结果填入的命令参数，不是设计决策缺口。验证工具必须先在 T14 更新以覆盖 contracts/新路径。设备测试保持 `com.greatwall.callring.backendqa` 隔离身份，runner 退出与 OK 结果双重检查。补充组件身份与现有 widget 持续可用的人工记录。

## 10. 五项问题与任务/验收追踪

| 问题 | 主任务 | 必须存在的证据 | 最终完成条件 |
|---|---|---|---|
| P1 全量状态 | T05–T10、T13 | 局部表/行探针、1千/1万数据、定向 SQL/回滚、报表/分类样本 | 普通业务零全量状态/全量差异；完整快照显式隔离；A1/A2 |
| P2 无关刷新 | T02、T05–T11 | 各片 ChangeImpact、实际 loader 计数、慢订阅/超限/恢复/并发矩阵 | 无关查询增量 0、有关变化最终可见、无旧全域兜底；A3 |
| P3 数字工具 | T02、T12、T14 | 全工具兼容表、随机重排、目录负例、确认/作用域/回执回归 | 数字仅兼容元数据，操作行为不依赖顺序；A4 |
| P4 宽上下文 | T04–T10、T13 | 各组窄假端口实例化、构造器/依赖检查 | BackendContext 删除，无改名万能上下文；A5 |
| P5 强转穿透 | T05、T10、T14 | 非 SQLite 替代资源、懒打开/并发关闭、禁止强转负例 | 抽象可替换，查询/metrics/close 同生命周期；A6 |
| 分层/结构 | T03–T04、T13–T14 | 类路径、包规则、完整文件清单、组件身份 | D1–D10 生效，全部文件归属，A0/A8/A9 |

## 11. 回退、范围调整与交付

每阶段以验证通过的代码检查点提交；纯移动与行为改变分开。回退使用对应提交的逆向修改，保留工作区和数据库，不执行 reset --hard/清库/重装替代排障。本方案不引入 schema 变更；如果实现证明必须改变 schema、算法或产品语义，先记录原因、影响与新的验收范围，修订本设计后再执行。

最终报告必须区分“架构/自动验证通过”和“设备真实行为已验证”。没有执行的设备或升级测试保留缺口，不勾选伪造完成。实施依照用户后续执行指令持续推进，每阶段记录证据，不将中间检查点当作全部完成。
