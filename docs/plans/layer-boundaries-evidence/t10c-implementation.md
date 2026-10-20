# T10c 装配、Decision 镜像与必需能力收口

2026-10-21，基线 `9aca4c4c7f2966f4f63d670396d6c4ecb196d35e`；工作树 `C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring`，分支 `codex/layer-boundaries`。本片候选冻结待规格→质量双审；未 stage/commit/push、改版本、操作设备或进入 T11。

## 实际装配与删除

- 删除 `BackendContext.kt`、其全部生产/测试引用、with-context API 工厂与无业务用途的 CallringBackend `BackendStore` 参数。`createSettingsApi/createUiEditsApi` 只组合显式声明的服务；没有 Context2、万能 Dependencies、旧构造器、别名或迁移转发。
- `CallringBackend` 是明确的完整 API 组合根，直接构造窄服务，必需真实 CalendarSource 和 conversation/aiSettings/update/snapshots/recordings 惰性 provider；删除生产 `CapabilityFallbacks.kt` 全文件和全部 Unavailable API 引用。完整 fixture 仅存在 backend/app test 与 UI sharedTest。bound 保留同一 coordinator、DecisionMirror、operationActive 和平台资源 provider，只绑定 command。
- `DecisionMirror` 唯一持有 AtomicReference 和 calendar/leaves/whitelist/work/overlay/quiet/statistics typed patch。原服务最终提交 fence 内调用 patch，DecisionQueryService 冷读、预热和共享 snapshot 语义保持；没有在 app 重写决策业务。
- app `CoreAssembly` 接线核心 adapters/服务，既有 `RecordingAssembly` 继续持唯一惰性录音资源，`ConversationAssembly` 接线 AI queries/source/完整快照、JobScheduler 观察、foreground registry 和 conversation，`UiBridgeAssembly` 接入通知/来电 UI 与具体 widget 组件安装查询。Graph 保留总装配和生命周期。
- `IncomingCallController` 接入登记后的录音 session、phoneState 和恢复；`AndroidIncomingRecovery` 负责权限/调度规则。BackendIncomingCallEngine 只接受 snapshot/refresh/record/complete callbacks。Graph 不再组合来电业务或执行具体 UI/Job 查询。
- incoming 初始化只固定 RecordingAssembly 引用，不打开 store/decoder/client；已排队观察与 link 在 drain 时不再经过 Graph initializer 的 ensureOpen。Graph 先标关闭并在锁外 drain，再关闭 diagnostics/录音/数据库；未用资源不初始化，ConversationAssembly 关闭也清理仅 query 已用时的资源。

## 生命周期边界与真实 RED/GREEN

保留旧公开 API 后，非 SQLite 写入原先仍可在旧 Graph 关闭后更改新 Graph 共享的 preferences。新增 AssemblyLifetimeTest：旧普通/命令绑定 API 的 appearance/widget 写入、录音配置/复制、铃声/预览、runtime 同步均拒绝，marker 与共享持久值不变；构造及关闭未用 assembly 不创建数据库。五类非 SQL writer 与 RecordingService 显式必需 owner predicate，最终 guard 内复核；bound 沿用同一 predicate。内部 incoming 登记/link 与 diagnostics drain 不走公开 owner gate，不全局关闭 coordinator。

- `t10c-closed-preferences-red.log`：1 项实际失败，关闭后的 saveAppearance 仍返回 Success(DARK)；修复后通过。
- `t10c-prepared-close-red-2.log`：移除 ringtone 最终 fence 的 owner 复核时，关闭发生于真实 AndroidAudioController 文件准备期间，1 项实际失败；恢复后最终专项通过，原音频/metadata/marker 不变且 scratch/backup 清理。首轮负例未去掉该深层检查而通过，保留日志且未冒称 RED。
- 纯移动与 mandatory 覆盖复用原领域、Decision、组合删除/采用、来电期限、录音与工具回归，不写镜像结构测试、不删安全断言。

## 审查发现的完整闭包

- Runtime 的 status/权限探针继续锁外执行；最终调度前重新进入 coordinator.guard(command) 复核 owner、外部命令取消/foreground/scopes。真实 blocked-probe RED（`t10c-runtime-probe-red.log`）证明只做入口检查仍可错误调度；最终专项覆盖四种变化且 scheduler 调用零次。
- 关闭标志与 incoming drain 之间存储仍开放，原公开 SQL writer 也可落盘。`t10c-public-sql-drain-red-2.log` 真实失败后，九组公开 SQL 服务明确必需 operationActive，runner 入口早拒绝，唯一 CommandCommitter 在入口和最终 guard 内复核。Caller save/whitelist/delete、quiet、work、score、position、leave、calendar、statistics、overlay 全部接同一公开 owner；不在持久化后打断 publication/afterCommit。
- Graph 另明确提供 persistenceActive，仅供内部 incoming/import 的 CommandCommitter；公开 owner 在 closed 立即失效，persistence owner 在 incoming drain 后且 storage 关闭前的 finally 失效，两者均无 true 默认，bound 共享。嵌套 finally 确保前一关闭步骤异常仍继续 drain/关闭后续资源；没有全局关闭 coordinator 或提前关闭 StorageResources。
- AssemblyLifetimeTest 真实 Graph 测试在 store monitor 阻塞排队 statistics link，close 标记公开关闭且等待 drain，释放后用新 Graph 核查 link/archive/core 持久结果。另以测试反射只阻塞现有 observations executor，让冷首次观察排在 close 后执行，并在 drain 期间验证旧普通/绑定 SQL API 早拒绝。没有生产测试 hook；撤销内部 RecordingAssembly 捕获时该冷观察用例在 call.record_failed 诊断断言真实 RED（`t10c-cold-incoming-red.log`），恢复后 GREEN。该冷用例默认录音关闭，只验证资源初始化及诊断排空；实际录音链接持久结果由前一用例覆盖，不冒称 Keystore/设备实测。
- 完整门首次 UI298 中1例失败暴露 WidgetModel 旧 quiet.enable_unavailable fallback 的最后消费者。删除该第二恢复路径、preferredMode/rememberMode 与唯一 last_quiet_mode 偏好读写，WidgetModel 只调用必需 backend setQuietEnabled。测试 fake 明确实现命令，原规则/恢复/FIFO/午夜断言保留。首次失败日志 `t10c-full-gate.log` 保留，完整门以修复后 -2 为准。
- CallringBackend 三个小服务直接接本实例 commandContext，commandApi 直接创建共享镜像/两 owner 的 bound root；删除旧覆写 decorator 和重复小服务实例。

## 契约收紧

只移除缺能力/第二实现默认体，要求明确 override：UiEdits 七方法、Callers 三方法、Widgets.listBindings、Recording.inspectSystemRecording/getIdentity/listArchives、AiSettings.patchCombined/testDraft、Conversation.sendTo/continueTask、CallringUiApi.commandApi/adoptIdentity、Settings.setQuietEnabled/synchronizeCalendar 和 AiQueryApi.reset。删除 aiUnavailable helper；唯一 QueryService 已真实清理会话资源。LocalSecretChange.Keep 等真实参数默认保留。current-api.md 仅按这些声明要求更新 reviewed inventory；历史 T02 v1 两快照与 ai-operation-compatibility.json/pin 未改。真实平台 ApiError、协议、schema/v4、编码、算法、组件 FQCN、Job/PendingIntent 与 81 工具 O/X/D 保持。

## 冻结与验证

94 个生产/测试源码路径候选 SHA256 `34c7b0c86d0a6df0f15a432cb4a287b67fbdea000b005b9c9cfb1fd545fb59b3`。ignored `build/qa/layer-boundaries/t10c-source-candidate.json` 以基线 `git diff --no-renames --name-only` 与 untracked .kt/.java 并集，按相对路径排序累计 path+LF+rawbytes（删除为 DELETED）+LF。tracked diff 与 untracked EOF/尾空白检查通过。

修复后最终专项 `t10c-reviewed-focused.log` 实际 exit0、19s；backend/app 实际执行，UI Widget8例沿用同源码缓存。两 Python guards 与 17/4 个负例实际 exit0，114 JVM 源及 reviewed 当前库存/冻结 AI golden 通过，日志 `t10c-architecture-final.log`。修复后完整门 `t10c-full-gate-2.log` 实际 exit0、2m34s，155任务32执行/123缓存。backend344、UI298、app327均实际全量执行，失败/错误/跳过全0；contracts:test为NO-SOURCE。assembleDebug、backendQa 与 UI AndroidTest编译实际执行；app DebugAndroidTest 编译任务实际NO-SOURCE。app/UI lint analysis及report均实际执行，Fatal/Error0，UI30Warning+3Hint、app6Warning（如实按本次XML，未沿用旧UI31计数）。XML聚合SHA/时间范围、task状态、lint及原始日志均见 [t10c-verification-summary.json](t10c-verification-summary.json)。

完整门日志 SHA256 `5e49adef8c9e8cbd5824efdb5c74c7acaf0a63dd763bbcc8b3910443460210f4`；ignored verification SHA256 `675779e8da9a69e20f1e6f9218bc15272a99800cc5cbff2a5a9ef9269a84e228`。源码在完整门后独立重算仍为94路径/同一冻结SHA，tracked/untracked空白通过。本次实际生成的32份raw fixture JSON复制到ignored t10c-replay-*，未改测试输出路径、oracle或pin；1k/10k组合预览另外保留 `t10c-number-access-1000/10000.json`。本次实际计数：warm号码投影1001/10001、目标metadata与payload各1、最大源块4096B、sourceBytes1400476/1400477；保存绑定物理枚举1002/10002、返回1、installed查询0。源字节不是常量访问承诺，随机夹具path/namespace长度可能改变字节数，以本次原JSON为准；没有把warm访问说成全部历史cold成本。

真实后续仍有 T11 DataChange/nullable snapshots 与原 coordinator feed 消费者、T13 BackendStore 全状态初始化/fixture 及 StorageResources legacy；本片没有将这些门标为完成。无设备验证。
