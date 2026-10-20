# 分层边界、局部读写与刷新链路整理设计

日期：2026-10-20。分析基线：`fa886a431e44424fe9b483380ea97f6d792c003b`。

状态：用户在文档阶段后已要求由当前会话主负责人推进，实施已启动。按配套计划分阶段迁移、验证和审查；实际进展见执行记录，不将设计目标视为已实现。

配套文档：
- [分阶段实施计划](../plans/2026-10-20-layer-boundaries-plan.md)
- [生产文件归属与迁移清单](2026-10-20-layer-boundaries-file-map.md)

## 1. 目标与完成定义

保留 app / ui / backend 三大职责层，通过独立 contracts 模块建立编译隔离，整理每层内部结构，同时解决以下五项问题。

| ID | 问题 | 完成定义 |
|---|---|---|
| P1 | 局部操作依赖全量业务状态 | 普通业务入口按明确的数据范围读取、定向写入，不经过全量 BackendState 装载、全量保存或全量前后差异；完整快照仅在显式完整快照入口执行 |
| P2 | 变更通知导致无关功能刷新 | 每次实际提交发布准确变更范围；订阅按真实依赖失效；慢订阅、重订阅和合并通知均不会漏刷 |
| P3 | AI 工具数字编号形成隐式依赖 | 内部逻辑使用具名 OperationId；执行、确认、作用域和回执从同一操作目录关联；历史编号仅作为稳定兼容元数据 |
| P4 | BackendContext 使服务依赖过宽 | 移除生产 BackendContext；各服务显式声明窄依赖；公共执行、事务、修订机制不再承载业务状态和所有外部能力 |
| P5 | 存储抽象被具体类型强转穿透 | 存储、查询、诊断和关闭能力显式装配；不存在依赖 SqliteBackendStore 类型才能工作的抽象包装 |

架构整理也必须完成：UI 编译类路径不含 backend 实现；domain 不依赖 application、contracts 或 Android；app 中的平台适配不承载业务校验和跨能力业务编排；所有生产源码均有明确目录归属。

### 1.1 本次不改变的产品语义

- 保持页面功能、号码规范化、评分和分类公式、统计启用区间、历史原始事实、录音独立登记、AI 确认与冲突保护、应用更新行为。
- 保持 applicationId、版本与签名机制、数据库表结构及已有数据编码。通过定向 SQL 实现整理，不以数据库重建、清库或改格式替代迁移。
- 保持来电响应期限、会话去重、准备/提交协议、后台恢复和设备权限语义。
- 保持 AI 对外工具名称、参数 schema、发现结果标识、既有 O/X 操作标识及 D01–D21 数据域定义。
- 内部 Kotlin 包和内部 API 可以调整；不承诺内部二进制兼容。不引入常驻旧入口、双写、新旧实现开关。

不新增功能，不切换数据库/DI/导航/网络技术栈，不统一改写 Java/Kotlin，不继续拆成每功能一个 Gradle 模块，不做独立进程或远程服务改造。

### 1.2 可行性与估算

基线主源码约 227 文件、20,553 行；测试源码约 193 文件。文件数与此前 226 的扫描有口径差异，均不是测试项数量。已运行的现有边界检查通过；未以此推断全量构建、功能或设备验收通过。

估算 12～20 人日，预算按 16 人日安排。主要不确定性是历史重分类/通话关联的数据闭包、AI 既有作用域兼容、实际设备生命周期。阶段估算见计划；如需 schema 变更、新算法或产品行为调整，应单独提出设计变更，不静默扩大本次范围。

## 2. 现状证据与方案选择

| 现状 | 证据入口 | 影响 |
|---|---|---|
| app 同时依赖 ui/backend；ui 依赖整个 backend | 三个模块的 build.gradle | 当前没有模块环，但 UI 仍能在编译时接触 backend 实现 |
| domain 的六个源码引用 application.api | backend/domain 下各文件 | 领域事实和对外契约混杂 |
| BackendStore 暴露全量 read/transaction | application/ports/BackendPorts.kt | getQuietHours、档案编辑等局部操作也加载全业务状态 |
| SQLite 每次 StorageSession.load/save 分派所有表组 | app/data/SqliteBackendStore.kt | SQL 即使有行差异优化，仍有全量装载、遍历和耦合 |
| coreScopeValues 遍历档案、通话、配置等 | application/BusinessScopes.kt、BackendContext.kt | 小写入仍需全量前后比较 |
| changed/publishScopes/runtimeChanged 通知所有 DataDomain | application/BusinessRevisionCoordinator.kt | UI 现有 affects 过滤失去大部分效果 |
| StateFlow 合并中间状态 | 同上及 ui/state/UiViewModel.kt | 不能直接把通知改成“最近一次小事件” |
| 操作序号决定执行和策略 | ToolRegistry、ToolExecutor、OperationScopePlanner、ReceiptVerifier 等 | 注册顺序影响多处业务行为 |
| 懒存储强转具体实现取得查询能力 | app/bootstrap/LazyBackendStore.kt | 假存储和替代存储不能真正替换 |
| 业务组合和校验位于平台包 | NumberDeletionService、AiSettingsService、AndroidRecordingRuntime | 改目录之外还需提取职责 |
| BackendGraph 直接定位具体 widget receiver | app/bootstrap/BackendGraph.kt | 装配与具体 UI 能力绑定过深 |

选择“保留三大职责层 + 独立 contracts”。只加强三模块的包规则无法提供 UI 编译隔离；进一步拆成大量模块会使本次演变为全项目模块化。层内使用包、internal、检查规则和窄接口约束即可。

## 3. 目标目录与包结构

以下路径相对于各模块 `src/main/kotlin/com/greatwall/callring/`。保留 Java 文件的语言与 `src/main/java` 根，按相同职责包组织。目录代表职责，不要求只有一个小文件也建立多层空目录。

```text
contracts/                         新增纯 JVM 模块
  contracts/
    CallringApi.kt                  对外能力聚合入口
    common/                        ApiResult、分页、错误、公共标识
    change/                        订阅规格、累计失效快照、观察令牌
    callers/                       档案、号码删除契约
    settings/                      设置、日历、外观契约
    report/                        报表与导出契约
    runtime/                       运行状态、铃声、来电展示端口
    widget/                        绑定契约
    recording/                     录音请求、公开结果
    ai/                            AI 设置、会话、查询、快照、JSON 值类型
    command/                       前台命令授权与修订令牌的跨层定义

backend/
  domain/
    model/                         领域事实、设置值对象、号码身份
    calling/                       分类、来电决策、通话关联规则
    report/                        评分、质量、汇总纯计算
    ai/                            连接配置校验、厂商/思考策略
  application/
    CallringBackend.kt             对外 facade，组合各业务能力
    callers/                       档案读写与组合删除
    settings/                      工作安排、岗位、日历、免打扰、外观
    calling/                       来电登记、结果更新、系统日志导入
    home/                          首页聚合查询
    report/                        报表查询、导出编排
    recording/                     录音流程、AI 设置业务、身份采用
    runtime/                       铃声与运行状态用例
    widget/                        绑定用例
    conversation/
      operation/                   OperationId/目录/schema/元数据
      execution/                   执行、批次、任务、确认、回执、作用域
      query/                       AI 查询、投影、完整快照组装逻辑
      context/                     会话上下文与预算
      foreground/                  前台授权、资源句柄协议
    coordination/                  命令提交、修订、失效、决策快照持有者
    mapping/                       domain 与 contracts 的显式转换
    ports/
      store/                       业务数据查询/写入/同库会话端口
      recording/                   归档、配置、音频与网络能力端口
      platform/                    权限、时钟外部观察、文档、调度等端口
      query/                       AI 源查询端口

app/
  CallringApplication.kt            唯一应用装配根，保留现有组件身份
  bootstrap/
    BackendGraph.kt                依赖总装配和关闭顺序
    CoreAssembly.kt                核心存储与业务装配
    RecordingAssembly.kt           录音、AI 设置与工作流装配
    ConversationAssembly.kt        AI 工具、源查询、完整快照装配
    UiBridgeAssembly.kt            具体 UI 能力接线
    StorageResources.kt            显式存储能力与懒生命周期
  entrypoint/                      系统组件实现逻辑，见组件身份约束
    calling/、recording/、jobs/、runtime/
  data/
    database/                      helper、迁移、事务资源、会话
    callers/、settings/、calling/、report/
    recording/                     归档及录音配置持久化
    preferences/                   外观、绑定偏好存储
    query/                         只读 AI 数据源投影
    codec/                         持久化编解码及历史值兼容
  platform/
    calling/、audio/、document/、permission/
    calendar/、scheduler/、diagnostics/、runtime/
    recording/                     Android 录音获取、媒体、文件能力
    ai/                            HTTP、协议 JSON、流解析
    update/                        更新下载/校验/安装平台实现

ui/
  app/                             Activity、导航与页面总装配
  bridge/                          UiApiOwner、通知及平台展示接线入口
  feature/
    callers/、home/、report/、recording/、ai/、conversation/、update/
    settings/                      现有设置子功能，按职责拆混合文件
    shared/                        业务公共展示组件/反馈
  designsystem/                     无业务实体的视觉与交互原语
  state/                           请求、编辑、变更观察机制
  format/                          显示转换与标签
  widget/、overlay/                 各自完整的 Android 展示入口
```

测试结构跟随生产职责；共享 UI 测试保留 sharedTest，设备测试保留 androidTest/backendQa。docs、tools、资源仍按现有职责组织，仅更新受迁移影响的引用，不清理无关历史材料。

### 3.1 组件身份约束

已登记的 Application、Service、Receiver、Widget receiver、Activity 完整类名本轮保持稳定，避免持久 Job、PendingIntent、小组件和系统角色丢失。根包 Android 入口文件是有意保留的组件壳，不是遗留业务兼容入口；其逻辑委托 entrypoint 包的控制器，不保留业务规则。UI 的既有入口身份同理保留。`SessionGate`、`PreparationGate` 等非系统组件可迁移包，同步所有引用及测试。

完整文件去向见迁移清单；混合职责文件先分解内容再删除原文件，不能只改包名视为完成。

## 4. 依赖规则

```text
app -> ui -> contracts
app -> backend -> contracts
app -> contracts
backend.application -> backend.domain
backend.application -> backend.application.ports
app.data / app.platform -> backend.application.ports / domain / contracts
```

| 编号 | 允许 | 禁止及检查方式 |
|---|---|---|
| D1 | contracts 依赖 Kotlin/JDK、Flow 等当前必要基础库 | 依赖 app/ui/backend、Android、SQL、具体网络/存储库；构建及 import 检查 |
| D2 | ui 依赖 contracts 与现有 Android/Compose 展示依赖 | Gradle 直接或传递得到 backend 实现；import backend/domain/data/bootstrap；编译类路径检查 |
| D3 | backend.application 依赖 domain、contracts、窄端口 | Android/UI/SQL/平台具体类；源码边界检查 |
| D4 | domain 使用 Kotlin/JDK 与领域内部模型 | application、contracts、Android、协程调度、文件/网络 IO；纯计算测试及 import 检查 |
| D5 | app.bootstrap 引用各层具体类做装配 | 查询、校验、删改顺序等业务规则；人工职责审查及禁止调用清单 |
| D6 | app.data 实现端口并做 SQL 映射；platform 实现设备能力 | data 调用 UI/平台 AI 协议解析器或业务服务；platform 定义业务事务/冲突策略 |
| D7 | 同一业务包共享内聚 helper；服务构造器接收窄能力 | 通过新 ServiceContext、Any/Map、服务定位器替代 BackendContext；结构审查 |
| D8 | UI designsystem 依赖视觉类型，feature/shared 可依赖业务 DTO | designsystem 直接接收 CallerProfile 等业务实体，或反向依赖 state/feature |
| D9 | app 装配注入 UI 通知、安装状态、来电展示桥 | data/platform 引用具体 widget/Activity/Overlay 类；Graph 的 UI 引用集中在 UiBridgeAssembly |
| D10 | 显式注入实现端口的对象，可受控使用 interface/密封类型分派 | 将 BackendStore 强转具体 SQLite 类获取额外能力；架构负例检查 |

contracts 新包统一为 `com.greatwall.callring.contracts`，一次机械更新消费者。拆分旧 558 行主契约；旧 docs/contracts v1 文件冻结为历史基线，新契约说明记录当前公开声明及兼容差异。删除边界脚本对旧单文件源码完全相同的要求，改查公开名称、参数、默认值、schema 兼容清单与依赖方向。不得通过大量 typealias 长期保留旧包。

兼容分两个等级：对外 AI 工具协议、持久化格式和 Android 组件身份必须保持；仓库内部 Kotlin API 允许为窄接口和秘密隔离做有记录的签名调整，并同步全部调用方/测试，不要求逐字相同。尤其 RecordingApi 中返回原始 RecordingSettings、接收完整连接密钥的旧管理方法，迁移为 backend 的 RecordingConfigurationPort/AiClient 端口，UI 继续使用安全视图和 LocalSecretChange；契约兼容检查显式记录这些内部差异，不能将原始密钥模型复制到 contracts 规避迁移。

AiJson 值结构及无 IO 的基础访问器可以属于契约；厂商识别、连接校验、请求 endpoint 组装、思考策略和业务校验移至 domain 或 backend/application。Unavailable 实现放 backend 的显式能力缺失适配；生产装配校验必需能力，不能靠默认 unavailable 掩盖漏接线。含密钥的内部配置归 backend 模型；UI 使用公开安全视图和本地密钥变更命令，保持现有产品输入功能，禁止投影明文密钥到 AI 查询或日志。

现有 CredentialDialog 的复制连接预览直接调用 profile 解析，需同步消除。由 backend 在 AiConnectionView 增加安全的 copyToAnalysisProfile 派生字段，表示复制到分析并归一 AUTO 后的解析结果；UI 只显示结果，不构造内部 AiConnection。不要直接复用受当前 compatibility 影响的 resolvedProfile 作为复制后结果。主题对 AppearanceSettings 的适配放 UI app/bridge，designsystem 接收视觉 ThemeSpec；颜色计算和控件不直接解释业务配置。

## 5. P1：局部读取与定向写入

### 5.1 数据端口与事务

以业务能力建立 `CallerReader`、`CallerWriter`、`SettingsReader`、`SettingsWriter`、`CallReader`、`CallWriter`、`ReportReader`、`DecisionInputsReader`、`FullSnapshotReader`。接口返回有类型的领域事实/投影，不返回全量 BackendState，也不把 SQL、Cursor、AiJson 当作普通业务存储 API。

`CoreReadSession` 与 `CoreWriteSession` 是同一数据库的事务作用域接口：只暴露上述能力，不能取得其他平台能力。`CoreUnitOfWork` 打开它们；一次跨表业务操作共享同一连接/事务，服务不能通过多个独立 repository 调用假装实现原子性。只读会话保持一次查询组合的一致性；可一次执行单 SELECT 的入口不必创建庞大会话。

写入结果携带 `MutationDelta<T>`：结果值、实际变更的业务实体/字段、冲突作用域、需失效的展示依赖、是否改变来电决策输入。SQL 仅在值确实变化时写入；无操作结果不提升数据修订号、不触发业务刷新。平台瞬时播放状态变化作为独立运行状态更新。

变更描述依据事务内读取的旧值和成功执行的行级写入构造；不依据 UI 提交意图推测。不保留通用全状态前后 diff。数据库 ID 使用 SQLite 原有自增机制，不用 max(id)+1；删除后不复用 ID；保留旧编码、去重键和外键顺序。

### 5.2 覆盖范围与数据闭包

| 操作 | 允许读取与写入的范围 | 不允许的隐式依赖 |
|---|---|---|
| 免打扰、评分、悬浮窗开关 | 对应配置、必要版本/校验输入；评分变更只影响派生计算，不重写通话事实 | 加载 calls、全部档案和日历审计 |
| 岗位增改/删除、本人岗位 | 目标岗位、唯一性/引用存在检查、本人岗位；展示失效按引用或全关系范围传播 | 加载完整档案验证一个引用；无关通话重写 |
| 档案编辑/白名单 | 目标档案、关联岗位及本人岗位；必要的单号码关联计数 | 所有号码历史和全部录音 |
| 档案搜索/列表 | SQL 过滤、稳定排序、分页及必要关联投影 | 在内存载入所有档案再分页；每行加载全量通话 |
| 最新来电、详情、待补全检查 | LIMIT/EXISTS/目标 ID，必要档案、配置投影 | 全量 calls 后排序/扫描 |
| 号码报表、CSV | 指定号码、指定日期范围、评分配置与岗位；保留既有区间内重复评分，在一致读快照内流式计算/导出 | 先加载所有号码通话；将筛选后页数当总数；引入区间前通话改变评分 |
| 来电登记/决策更新 | 目标会话、统计区间、分类输入、该号码关联候选及必要时间窗口 | 每次登记加载所有通话；无关写入重建决策快照 |
| 系统日志导入 | 当前批次涉及 logId、号码、旧新时间窗口和匹配闭包 | 每批加载全库通话；只读窗口导致漏掉全局唯一性约束 |
| 日历/工作安排/请假更正 | 受影响配置和历史区间，目标通话按批流式读取，同库原子提交 | 构建 BackendState 全库副本；把有业务必要的批量更正误当禁止的全量处理 |
| 组合号码删除 | 单号码核心记录、单号码录音、相应绑定；顺序与重试语义保持 | 列出所有归档再过滤；把多资源删除宣称为跨库原子事务 |
| 完整快照 | 显式入口可读取全部 D 域事实，继续大小限制、秘密脱敏和完整性声明 | 普通页查询/AI 闲聊/工具发现触发完整快照 |

报表继续复用领域累加器和评分函数；SQLite 只提供过滤/排序/原始汇总事实，不复制业务公式。AI ScopedReadPort 保持现有 D 域和查询协议，复用有类型读能力/专门只读投影，不要求所有普通业务查询绕经 AI JSON API。

CSV 的一致读取和文件输出在后台执行，不持协调 fence 或写事务等待文档输出；可在 WAL 一致只读快照中迭代，输出失败按现有导出错误语义处理。大范围历史更正仍保持既有必要原子性，分批读取不等同于擅自改成多次可见提交。

通话关联保留现有候选匹配、跨账户和 ID 行为：按号码读取足够的候选闭包，必要时允许读取该号码全部候选，但不读取其他号码；全局 logId 唯一性单独检查。报表重复呼叫评分沿用现有累加器：先排除统计区间外记录，previous 仅由区间内记录推进，不引入区间前通话扣分。工作分类的前一日跨午夜配置依赖仍须加载。

### 5.3 决策快照

把 `DecisionSnapshot` 的输入从 BackendState 改为专用不可变 `DecisionInputs`：白名单、工作配置/历史、日历、覆盖、请假、免打扰、统计区间、悬浮窗开关。它不包含通话明细、录音、报表或音频文件。

只在相关输入提交后更新镜像；普通档案名称、录音进度、通话结果、外观写入不重建。保留实际来电时间计算，不缓存“现在是否工作”的布尔值。持有者只接受不可变输入；数据库预热在后台，Telecom 主线程不等待全量加载。冷启动准备和原有兜底协议沿用并验证。

## 6. P2：变更、冲突与刷新

### 6.1 三种语义分开

1. `ConflictScope`：写入前置条件，精确到字段/实体/存在性及引用依赖；用于拒绝过期写入。
2. `ChangeImpact`：成功提交后的事实变化及派生展示影响；用于刷新。
3. `DecisionInputs` 失效：只控制来电决策输入镜像。

三者由同一次业务提交描述关联，但不能用“刷新全部页面”代替写冲突保护，也不能因 UI 无需刷新就跳过修订。保留 incarnation、自有提交继续执行、generation、取消/前台授权校验和已提交结果读回规则。

AI 查询的源版本也按实际 D 域依赖维护，不再对所有核心 D 域只返回一个 core 版本；相关数据改变应使游标/缓存失效，无关外观或其他域变化不误作该源变化。QueryStamp 的 scopeRevision 继续用于披露观察与写冲突，不拿 UI 观察令牌替换它；跨域一致快照仍用现有版本向量校验。

### 6.2 提交与锁顺序

固定顺序为：后台准备/文件解析/网络请求 → 取得协调 fence → 检查命令与冲突 → 同库写事务、内部复核并提交 → 发布实际冲突修订和变更快照、更新必要决策镜像 → 释放 fence。通知消费者和设备调度在锁外执行。

数据库内部不得反向取得 fence。任何需要提交的业务路径都使用同一协调入口；只读 SQL 不在事务中读取协调器。需要版本一致性的读取在开始前取得令牌、结束后复核，若相关版本改变则重读；不得给旧数据贴上读取结束后的新版本。

事务失败不发布；提交成功后不能因调用者取消丢失发布。保留短小 NonCancellable 提交与发布段；网络、文件扫描和前台交互可取消，不纳入该段。无操作提交仍可记录命令已观察/完成证据，但不制造数据变化。单次同库提交只形成一次逻辑数据修订，消除 publishScopes + changed 重复通知。

进程在数据库提交后、内存发布前终止时不引入持久事件总线：重启 incarnation 改变，旧命令失效，新订阅初次加载和镜像重建从数据库恢复。后台恢复仍使用现有持久 Job/状态，不依赖内存通知。

### 6.3 采用累计版本快照，保留 StateFlow

`ChangeFeed` 发布不可变 `InvalidationSnapshot`，不再只发布最近一次 DataChange：
- 全局 epoch/incarnation 与提交 revision。
- 每个 DataDomain 的 `lastAnyRevision`：该域任一实体变化时更新，列表/汇总订阅使用。
- 每个 DataDomain 的 `wideRevision`：无法或无需限定实体的域级变化。
- 有限的 `entityRevisions`：按 `(domain, number/widgetId/archiveId)` 记录该实体最新变化修订。

单实体订阅令牌取 `max(wideRevision, entityRevision)`；域订阅取 lastAnyRevision；多域组合各自令牌。投影到订阅令牌后 distinctUntilChanged，StateFlow 跳过中间快照也能识别相关变化。领域间分别维护实体范围，不能用一组全局 numbers 同时解释所有域。

每域实体版本最多保留 1024 项；删除/淘汰或合并清空前提升该域 wideRevision 至当前修订。超限降级仅影响该域，确保已删除实体也不会漏刷。该机制允许极端大批次的安全过度刷新，禁止降级为所有 DataDomain。没有固定时间 debounce，也不使用无限事件队列。

新订阅先注册观察，再执行一次初始加载；观察令牌在数据读取开始前捕获。加载期间若依赖令牌再次变化，单航班刷新器标记 pending，完成后按最新令牌重载；旧请求不得覆盖新参数或新数据。恢复前台/重订阅重新校验累计令牌，不依赖停订期间收到每个事件。

本地写成功不再无条件 `refresh()` 加事件刷新两次：有变更 feed 的查询交给观察器；无操作返回用结果更新本地状态；外部系统观测/用户手动刷新保留显式请求。多次实际提交可合并为一次最终加载，不要求每条事件恰好一次查询。

### 6.4 必须验证的依赖矩阵

| 变化 | 应刷新/重算 | 不应触发 |
|---|---|---|
| 外观 | 主题、依赖外观的小组件/悬浮窗显示 | 业务报表查询、录音列表、档案查询 |
| 号码 A 名称/部门 | A 编辑/详情/报表标题、档案搜索列表、显示 A 的小组件/最新来电/悬浮窗 | B 的独立编辑/报表、录音任务重跑、决策快照 |
| A 白名单 | A 相关展示、档案列表、决策输入 | B 独立查询、录音重跑 |
| 岗位或本人岗位 | 岗位设置、实际引用关系展示及评分/质量相关结果；无法廉价限定时允许关系相关域级失效 | 外观、录音转写、更新检查 |
| A 通话新增/更正/删除 | A 报表/概览、首页最新及统计、相关绑定；身份关联改变时对应录音身份投影 | B 独立报表、工作配置重载、决策输入重建 |
| 工作安排/日历/请假 | 工作状态、相关历史更正、受影响报表/统计、决策输入 | 外观、录音文本重跑、更新检查 |
| 评分参数 | 报表/概览评分、质量和显示评分的绑定 | 通话原始事实重写、录音分析、决策输入 |
| 免打扰/统计区间/悬浮开关 | 对应设置、首页相关块、决策输入及确有依赖的汇总 | 无关录音、AI 配置、外观 |
| 录音归档状态 | 对应归档详情/列表及当前会话相关 UI | 档案和普通通话报表、其他号码独立归档详情 |
| AI 设置 | AI 设置视图和现有请求配置变化/取消逻辑 | 普通报表、外观、通话查询 |
| widget 绑定 i | 配置 i、绑定列表及 widget i | widget j 的绑定加载、普通报表 |
| 铃声/播放、权限/系统时间 | 对应运行状态、首页及依赖该观测的展示；系统时间触发工作状态重算 | 所有业务数据统一失效 |

表中“不应触发”指无其他真实依赖时。派生结果包含姓名、岗位、评分、质量、权限时，订阅必须覆盖这些依赖；不能只按主表判断。

## 7. P3：具名 AI 操作目录

新增内部 `OperationId` 枚举，名称描述语义，如 CHECK_NUMBER、SAVE_CALLER_PROFILE、SET_APPEARANCE、DELETE_NUMBER；不使用枚举 ordinal，也不依据注册数组下标派生标识。

`OperationDefinition` 集中保存 OperationId、稳定 toolName、历史 operationCode、legacyIndex、schema、描述、读写类别、确认策略、回执策略、依赖计划类别及执行处理器关联。schema 构建 helper 可复用，不复制为多个目录。目录启动校验：ID/名称/兼容码唯一；所有操作有执行与回执；写操作有作用域策略和所需前台策略；读操作有源/纯计算声明。

ToolExecutor、ScopedToolReader、OperationScopePlanner、ReceiptVerifier、ConfirmationTargets、ToolBatchEngine、CapabilityCatalog、TaskPlanner 共同使用此目录。允许按 OperationId 做穷尽分派，不建立反射式插件框架；只共享元数据和明确策略，不把业务用例重写进一个巨型目录。

既有 O01–O72、X 编号、对外工具名与 schema 保持对应关系；legacyIndex 只在兼容映射/历史证据展示处存在。工具发现排序由显式 displayOrder 决定，改变注册顺序不会改变语义。检查脚本与历史覆盖映射同步适配，不修改历史审查结论伪造新证据。

## 8. P4/P5：窄依赖与装配

### 8.1 服务依赖边界

| 服务组 | 允许的核心依赖 |
|---|---|
| callers | 档案/岗位查询、核心写会话、号码规则、提交执行器 |
| settings | 对应配置读写、必要权限与日历/文档端口、时钟、提交执行器 |
| report | 指定范围报表读取、领域计算器、文档输出、必要运行质量观察 |
| calling | 决策输入持有者、通话写会话、关联规则、时钟；不持有 UI 实现 |
| recording | 归档/配置端口、音频和 AI 客户端端口、调度、修订提交；不持有 Android Context |
| runtime/ringtone/widget | 对应平台/偏好端口与提交执行器，不取得其他业务存储 |
| conversation | 操作目录、公开业务能力/源查询、命令协调与前台协议；不能直接获得数据库 |

公共 `UseCaseRunner` 只负责 dispatcher、错误映射、取消边界；`CommandCommitter` 负责已有授权/修订/提交协议；`DecisionStateHolder` 只持有不可变镜像。这些对象不提供 service locator，也不拥有所有端口。BackendContext 中号码校验、分页、CSV、范围计算拆为明确纯函数/领域对象，不再作为上下文扩展方法。

### 8.2 app 中的业务迁移

- NumberDeletionService 的删除影响计算、顺序和冲突校验归 backend/application/callers；文件大小/删除、单号码归档查询由端口提供。继续“录音→核心→绑定”的可重试多资源语义；部分成功后的影响和错误必须如实返回，取消/继续命令修订行为不弱化。
- AiSettingsService 的字段合并、校验、密钥变更冲突、复制规则归 backend/application/recording；存储、麦克风权限观察、目录名称与连接测试 IO 留在适配器。
- AndroidRecordingRuntime 拆出业务 API facade，Android 录制/解码/播放、文件和生命周期仍在 app。工作流继续复用，不重写转写/分析算法。
- 更新模块本轮整理职责和装配，不重写已验收下载/校验/恢复算法；通用协议值移 contracts，GitHub/安装器保持平台实现。

### 8.3 显式存储资源

`StorageResources` 在装配层持有懒资源工厂，资源声明 coreUnitOfWork、scopedRead、fullSnapshot、metrics、close 能力。各服务只收到自己需要的接口；不把这个总资源包注入业务层。首次实际 IO 才打开 SQLite，构造 Graph/工具发现/安全计数不打开数据库。

同一核心资源的会话、查询、快照和关闭共享一个生命周期，防止分别懒构造产生两套数据库/锁。metrics 是只读计数接口，不靠 `as? SqliteBackendStore`；缺失诊断能力显式为空实现，不能把缺失查询能力降级成全量 read。close 幂等，关闭后全部端口一致拒绝操作；关闭不能与新开库竞态。

## 9. 迁移策略

按设置→档案/岗位→通话/分类→报表/完整快照→录音/组合操作的顺序逐切片替换。每片同时完成窄端口、SQLite 实现、服务依赖、实际变更描述和订阅；不等所有存储迁移后才修刷新。

阶段内允许未迁移片继续走原入口，并由唯一 facade 路由，禁止同一业务双写。旧式全域通知只允许存在于未迁移路径且显式计数；已迁移片不能因旁路兜底仍触发全量刷新。最终移除 BackendContext、BackendStore.read/transaction、StorageSession.load/save 全状态协议和 coreScopeValues，完整快照替换为 FullSnapshotReader。domain 中完整快照专用模型可保留，但不能成为普通服务输入。

纯文件移动和行为改变分开提交；每阶段可构建，可独立回退代码。回退不清数据、不回退已交付数据库格式；本设计不引入 schema 变化。审查发现缺失必要字段时扩充有类型端口，不能恢复全量状态作为永久兜底。

## 10. 验收标准与证据

| ID | 必须通过的验证 | 失败判定 |
|---|---|---|
| A0 | 模块依赖/类路径、domain import、UI/backend 平台边界、全部源码归属检查及负例 | UI 能导入 backend 实现；目录迁移遗漏或循环依赖 |
| A1 | 局部读写的 table/row/端口调用探针；1千与1万条无关通话下设置/档案操作读取范围一致 | 普通入口触发全状态装载、完整快照、全状态差异；只靠耗时阈值证明解耦 |
| A2 | 定向 SQL、同库回滚、ID 不复用、跨实例去重/引用、导入幂等、报表边界和历史更正对照 | 业务结果、原始分类、旧编码、事务范围改变 |
| A3 | 上述刷新矩阵正反例；慢消费者连续 A/B 变化、超限、订阅恢复、加载中提交、本地写入去重 | 漏刷、B 无关刷新、旧请求覆盖新数据、只测事件而不测实际查询次数 |
| A4 | 全操作兼容清单、目录随机重排、重复/缺失定义负例、确认/作用域/执行/回执覆盖 | 编号顺序改变行为；schema 或 O/X 对应改变；取消/确认/读回保护下降 |
| A5 | 每组服务仅用其窄假端口实例化；无 BackendContext/替代万能上下文 | service locator、宽上下文换名、读服务仍取得全部写存储 |
| A6 | 非 SQLite 假资源查询成功、懒打开、计数不打开库、并发开闭和关闭后拒绝 | 具体类强转、两套实例、关闭泄漏或后台新开库 |
| A7 | 核心/录音部分失败和取消、密钥脱敏、AI generation/自有提交继续、闲聊零业务源查询 | 事务或权限/隐私保护被整理破坏 |
| A8 | 全 JVM 测试、Debug/Release/backendQa/AndroidTest 构建、lint、架构/契约检查 | 存在失败或用旧结果冒充本次结果 |
| A9 | 隔离设备入口、来电准备与取消、后台恢复、widget/overlay、数据保留冒烟 | 组件身份丢失、期限退化、数据被清理；无设备时此项必须标未验证 |

性能证据记录调用范围、扫描行数、全状态装载次数、快照次数、查询次数和锁等待；同数据与设备条件下对比耗时和内存辅助评估，不将任意毫秒断言作为架构正确性的替代。1千/1万是合成固定数据集，不写入用户生产库。

全量测试命令、阶段任务及覆盖关系见实施计划。报告记录执行 commit、命令、退出码、JUnit 实际项数、失败/跳过、设备标识与限制；最终要求 A0–A8 通过，A9 提供实测或明确缺口，不把离线构建称为真机通过。

## 11. 主要风险与控制

- 精准刷新漏关联：以返回 DTO 实际依赖制定订阅，姓名/岗位/评分/权限及时间变化都纳入矩阵；广域降级只作用于真实相关领域。
- SQL 读范围过窄改变算法：通过历史用例与新旧离线固定数据对照验证候选闭包、重复评分窗口、DST 和配置历史；不保留两套生产算法。
- 分拆存储失去原子性：跨表命令共享 CoreWriteSession；跨文件/录音操作保持原有分步重试语义。
- 锁与版本混用：固定 fence→数据库顺序，数据库回调不调用协调器；读取令牌前后复核，不在锁里执行网络和 UI。
- 庞大文件移动掩盖逻辑：单独提交机械移动；迁移清单逐项回填目的文件、对应测试和证据。
- 工作量超界：若需要产品行为、schema 或新算法改变，单独记录变更及影响；不把未解决路径标为完成。

## 12. 本轮交付与后续边界

本轮交付设计、阶段计划和生产文件迁移清单。清单描述目标归属，所有实施复选框初始未选；这不是已经完成架构整改的证明。用户评审文档后再开始生产代码修改。
