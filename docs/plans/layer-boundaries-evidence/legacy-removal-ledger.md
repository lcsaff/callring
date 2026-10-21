# 旧实现删除追踪

当前执行切片 T13b2a：bootstrap 查询/完整快照投影及 job JSON 已退出；RecordingPorts、RecordingAssociations、BackendPorts、PlatformSafety 旧混合文件与 playbackSnapshot/activitySnapshot/scopedBindingRows 旧入口全部删除，真实消费者迁至窄 port、typed observation 与 backend projector。没有旧包转发、alias 或双实现；逐项最后消费者/唯一新入口见 [T13b2a 实施证据](t13b2a-implementation.md)。此处仅记录候选实现，实际门/独立审查以同片 summary 和负责人 review 为准。

仍待 T13b2b：Theme/Appearance 桥、CallerCard/form、混合 UI 文件；T13b2c：六系统组件 controller、PROJECT/README 与最终全集清单。T14/T15 仍 pending，本片不能记为 T13 全部完成。

用户要求本次修改不留下迁移兼容代码或未删除干净的旧实现。本表追踪仍有真实消费者的迁移路径；不是最终保留清单。切片审查同时确认新实现与删除项，最终所有待删除项必须关闭。

| 当前旧路径 | 仍使用它的业务 | 删除门 |
|---|---|---|
| `BackendContext` 及扩展式旧工厂 | T10c已删除类、全部生产/测试引用和with-context工厂，bound共享独立DecisionMirror；服务只接明确窄能力 | 已关闭：完整门及双审PASS，详见t10c-review.md |
| `BackendStore` 全状态 read/transaction、`BackendState`、`SqliteBackendStore` 全量装载/差异保存 | T13a已迁出onCreate及全部test/debug/backendQa fixtures；旧模型、协议、四Store、StorageSession均已删除 | T13a候选已冻结，实际门与审查见t13a证据；T14统一CI仍待执行 |
| `StorageResources` 的 legacy 资源投影 | T13a已删除legacy/read/transaction/stateLoadCount；唯一typed CoreStorageResource生命周期保留 | T13a候选已冻结；新唯一SqliteCoreStorageResource不留alias/wrapper |
| classifier/correlation 接受 `BackendState` 的入口及全状态决策 capture 重载 | T06b/T08/T09已删除旧业务入口；T13a删除最后测试模型消费者，纯算法用实际WorkInputs/DecisionInputs与独立call facts | T13a候选已冻结；全快照仅FullSnapshotReader专用facts |
| schema 初始化/升级中使用旧全状态协议 | onCreate已在原SQLiteOpenHelper创建事务内定向写config/calendar/audit；upgrade已于T08迁出 | T13a新增初始化原始事实/无默认settings物化/资产失败回滚测试；不更改schema3 |
| nullable `ChangeFeed.snapshots()` 默认实现及 UI 对旧通知的回退 | T11 已删除 nullable/default/observe/DataChange、双 loader/迁移开关和旧 fake；全部消费者及设备 fixture 只用必需累计 flow | 本片源码已删除，实际最终门与独立审查见 t11-implementation.md / t11-verification-summary.json / 负责人 review |
| 旧通知与整库版本/全域刷新发布 | T11 已删除 coordinator 第二份 changes、observe/changed/commit/publishScopes/runtimeChanged；diagnostics 及 runtime 只发真实实体，command/domainMarker 原职责保留 | 本片源码已删除；无双发布兜底，剩余全状态初始化协议仍属 T13 |
| AI 数字索引/顺序分派 | T12 已以单一81项 OperationCatalog/OperationId 迁移目录及全部执行链 | 本片删除 ToolDefinition/index、toolPurpose/purposes、72+9双API、旧OperationContract projection整文件及数字重载、nullable queries与旧UI只读分支；外部标识仅协议元数据。实际最终门和双审见T12证据；T13–T15仍待执行 |

T06a 额外清理已完成并经双审：删除 `UnavailableCoreUnitOfWork` / `UnavailableQuietTransactions` 及构造器默认替代；装配显式要求必需端口，计算测试使用测试专用假实现。对应简单设置旧实现、premerge、无用验证助手和偏好重复发布均删除，见 t06a-review.md。SnapshotViewModel 的 refreshFromChanges 双路径仍有未迁移消费者，T11 必须删除该开关及旧订阅分支；一次性请求仍可使用 RequestLoader。

已删除且经既有检查点验证：`LazyBackendStore`（T05b，以明确资源和窄能力替代）；UI 对 backend 的生产/测试编译依赖及旧包导入（T03）；domain 对 contracts/application 的依赖（T04）。

T06b 已删除历史设置旧业务、work/leave premerge、WorkClassifier.reclassify(BackendState) 及无调用助手。currentSchedule 仍由 CallersService/ReportCalculator/CoreSnapshotProjection 使用，scheduleCopy 仍由 CoreSnapshotProjection 使用；T09 替换这些消费者后删除。quiet.get 的 core 复核与旧 SettingsStore 全量保存路径一并审计收紧；不要以未定位的“restore”等泛称作为永久保留依据。

T07 已删除 CallersService、旧 CallerProjection 包装、UiEdits.profile 预合并、无消费者 BackendContext.mutate/events/allDomains，以及 CallerEditorViewModel nullable 写入回退/full-field 保存/whitelistTouched。页面和悬浮窗都必需注入同一草稿命令。剩余 visible(BackendState) 的真实消费者是 Reports/Home/CoreSnapshotProjection；options(BackendState) 仅 CoreSnapshotProjection 使用，T09 替换后一并删除。coreScopeValues 的过渡 profiles 集合键已随 T08 整个全量差异函数删除。完整删除项/消费者见 t07-implementation.md。

T08 已删除 calling 全状态业务、coreTransaction/coreScopeValues、DecisionSnapshot.capture(BackendState)、CallCorrelation 的 State overload 及无调用 StorageSession.saveCalls。旧 CallStore 与新 calling 复用唯一 codec。普通生产 writer 已全部退出 BackendStore.transaction，剩余接口/转发与 seed fixture 是 T13 必须删除的债；不得声称仍有 calling 消费者。完整旧存储的读/快照尚有 T09、录音 findCall 尚有 T10、onCreate 初始化尚有 T13 真实消费者。notifyChanged 仍有 RingtoneService 三处，changedScopeKeys 仍有 AppearanceService；随对应后续片关闭。

T09a 已删除旧 Home/Reports Context工厂与业务、ReportCalculator.timeRange/report、Context.page/meta/read/lock/revision、QuerySupport.paged和SettingsValidation.requireIssues。Context当前普通消费者仅剩运行状态/铃声/小组件及总装配；CoreSnapshotProjection仍使用score/quality/reportTotals/visible/currentSchedule/stats与复制助手及旧完整状态捕获，T09b迁出即删。CSV及CallerRowCodec均为共享唯一实现，无兼容转发。

T09b 已删除BackendStore.captureFull、CallringBackend.captureCore、Context完整入口、CoreSnapshotProjection/ReportCalculator文件及score/quality/reportTotals/visible/currentSchedule/stats、quiet/schedule/windows复制旧助手。FullSnapshotReader只返回专用深冻结facts；SQL源的BackendState/分类/评分业务退出，唯一JSON/StrictJson codec真迁data且旧platform文件删除。SettingsValidation只剩真实widgetId消费者T10。目录会话和完整快照资源各有唯一生命周期，无旧validateRead重载/无迁移fallback。

T10a源码已冻结并通过规格修复复审及独立质量审查：旧app AiSettingsService、Runtime/Ringtone/Widget的Context工厂、SettingsValidation、Context末用io/write/validation/notifyChanged等helpers、RecordingConfigurationPort/RecordingCapability及Runtime旧配置管理、Audio默认command重载/guarded fallback、Widget/Runtime接口与平台构造器无消费者默认均已删除。配置和binding数据adapter退出业务差异/发布，音频备份只有锁外准备加最终generation保护一条路径。尚未关闭的archive登记/CAS/delete旧发布和旧通知协议有真实后续消费者，分别在T10b/T11删除；不将它们算为本片已完成项。

最终证据应包含生产与测试符号搜索、编译类路径、架构正反例，以及无旧路径可执行的回归结果。历史基线与审查文档保留原貌，不将历史文字当作当前代码残留。

## 最终删除验收口径

用户再次明确“修改不要留下兼容代码和遗留未完全删除的代码”。最终验收不能只证明新入口已启用：表中各项必须记录实际删除文件/符号及最后调用方的迁移位置，生产、测试、调试设备入口、验证脚本和 CI 都不得继续引用旧实现。

- 禁止保留迁移专用的类型别名、转发方法、旧构造器重载、nullable/default 回退、双订阅、双发布或以异常捕获切回旧业务的分支。
- 删除专门支撑旧路径的测试替身与无效测试，保留业务行为断言并通过新唯一入口执行；不能用删除断言掩盖回归。
- 数据库现有编码读取、对外工具名称/代码以及 Android 组件身份属于本次明确保持的持久化/外部协议。它们可由唯一实现继续解释；不得据此保留第二套业务路径。数字工具兼容标识仅作协议元数据，不参与语义分派。
- 分阶段仍有真实调用方的旧设施只能登记到明确删除门；最终报告不能将待删除清单非空的状态标为完成。历史说明文件与冻结协议样本不作机械关键字清除。

本节是验收约束，不表示当前清单已经关闭；实际完成仍以各检查点和最终源码/测试/守卫证据为准。


T10b1 源码正式冻结待规格→质量双审：已删除 RecordingStore/AndroidRecordingStore.all 入口及普通消费者、Graph.findCall 全状态集合解析、旧 collection resolver、Workflow/AiClient 的 File 边界、isDeleted 默认false、AiClient.operation/cancel无消费者默认。显式 listArchives 只有列表真实消费者；identity/import generation/noteMissing/发现/调度/号码预览使用窄读取，play final只查generation。v4未索引历史只流式metadata且不写全文派生完成标记，raw号码/Unicode及原归档删除顺序保持。实际后续：Runtime facade/owned音频/play prepare/Store旧发布在T10b2；app与core组合服务、widgets全量lookup与SharedBusinessCommands宽依赖在T10b3；这些有真实消费者，没有新增兼容桥。见 [t10b1实施证据](t10b1-implementation.md)。本段不表示T10整体或双审已经通过。


T10b1质量P2分页补证：已消除dueCandidates每页扫描全部剩余warm/cold源的可避免放大；两个有序分区各收齐limit个真正due后停止，再合并全局前limit，future额外读取如实披露。只有RecordingMetadataReads与新增SQLite分页测试变化，未新增兼容入口/索引写。分页补完整门backend315/app302实际执行、UI298沿主门XML，完整561/变动26路径指纹及10k总19936源行安全计数见t10b1实施证据补节。重新冻结待规格核变化与质量复审，不表示后续T10b2/b3已经完成。


T10b2a 候选实现正式冻结：AndroidRecordingStore 已完全移除 coordinator/guard/业务差异发布/旧 registerIncoming、linkStatistics、hasSession 与 Internal 写包装；明确 recordingVersion getter 必传。唯一 backend RecordingArchiveCommands/RecordingRegistrationService 负责实际 CAS/delete/registration/link 的精确一次 marker、recording source/集合scope、RECORDING_ARCHIVES实体及 command 记录；Workflow 改实际窄 reads+commands。旧 RecordingStore 聚合接口零类型消费者后立即删除，无alias/default/shim。原Runtime仍是T10b2b未迁API/媒体/身份业务的真实消费者；只最小连接attach/delete实际stop与失败runtime，使新精准发布不依赖旧全域兜底。b2b/b3/c/T11/T13剩余边界见 [t10b2a实施证据](t10b2a-implementation.md)，本段不表示整片T10b2或双审已通过。完整门backend322/app308实执行、UI298缓存，111JVM及17/4守卫通过；未设备。

T10b2a规格P2补门：初始archive插入改即时changes判据，并对零行且目标不存在回滚event/archive；合法首个负ID正常建索引。闲置RecordingArchiveQueries零typed消费者后删除，无未来预留。相对主门仅3路径修复，最终backend322/app310实际执行、UI298缓存全0失败，源码重新冻结，详见同一实施证据补节。


T10b2b候选源码冻结：已彻底删除AndroidRecordingRuntime文件、implements RecordingApi/bound及生产/测试类型引用；Graph/Scoped/CompleteSnapshot/CallCaptureService/RecordingJobService和测试全部转向真实backend RecordingService与RecordingAssembly持有的唯一IO资源，没有app API转发facade。backend拥有身份/API/媒体/自动发现与capture计划，raw store仅增实际typed消费者的窄identity reads；唯一b2a commands/registrations/workflow继续复用。当前仍有真实后续消费者的CallringBackend→UnavailableRecordingApi及BackendContext总装配属于T10c，NumberDeletionService/Graph/SharedBusinessCommands组合采用属于T10b3，旧feed/存储分别T11/T13；未扩本片或添加兼容桥。统一门backend334/app317实际执行、UI298缓存、113JVM与17/4负例通过，详见[t10b2b实施证据](t10b2b-implementation.md)；本段不表示T10整体或双审已通过。


T10b3候选源码冻结：app/platform/callers/NumberDeletionService、backend/CoreNumberDeletionApi及SharedBusinessCommands类与其最后生产/测试调用全部删除；requiredValue有真实消费者，独立为RequiredValue.kt。唯一backend callers/NumberDeletionService及recording/IdentityAdoptionService由CallringBackend/commandApi供Graph/UI/工具共同使用，Graph退出业务UUID/bound/adopt编排。CallringBackend显式提供必需录音/身份/operationActive窄能力；测试空资源只在test目录。无消费者生产UnavailableNumberDeletionApi/UnavailableUiEditsApi同步删除；没有archiveCount=0/unsupported/Unavailable组合fallback、旧构造器/alias或app业务转发。WidgetBindingStore目标保存项读取与最终预期binding复核替代组合删除全listing/installed访问；同计数不是同集合，GUI与工具均有读前基线和最终scope/代次保护。真实剩余BackendContext/其它Unavailable总装配消费者仍由T10c迁出即删，旧feed及全状态初始化/fixture分别T11/T13；本片不扩入这些门。详见[t10b3实施证据](t10b3-implementation.md)，完整门与双审结论由对应最终证据记录，本段不表示T10整体完成。

T10c 候选冻结：已删除 BackendContext/with-context SettingsApi 与 UiEditsApi 工厂、CallringBackend 无用途 BackendStore 参数、生产 CapabilityFallbacks 全文件/Unavailable API 及 contracts 缺能力默认体/第二归档身份推导/aiUnavailable helper。完整 API 真实能力强制惰性装配；测试专用 fixture 只在测试源，所有 mandatory 方法显式覆盖。Core/Recording/Conversation/UiBridgeAssembly 接线，Graph 退出 widget/Job 查询与 incoming session/phoneState/恢复业务；DecisionMirror 持唯一共享缓存与 typed patch。公开非 SQL writer 的 owner predicate 在最终 fence 复核且 bound 共享，内部 incoming/diagnostics 排空不受公开关闭 gate 阻断。仍真实存在的 coordinator.observe/DataChange/nullable snapshots 归 T11；BackendStore 初始化/fixture、StorageResources legacy 归 T13。没有以本片候选关闭后续门；实际统一门与双审结论见对应证据。

T10c 审查闭包补充：WidgetModel 的 quiet.enable_unavailable 第二恢复路径及 preferredMode/rememberMode、唯一 last_quiet_mode 偏好读写消费者已删除，全部改唯一 backend setQuietEnabled。CommandCommitter 必需明确 owner；九组公开 SQL writer 及非 SQL writer 共用公开 operationActive，内部 incoming/import 使用只延续至 drain 完成的 persistenceActive，无 true/default/空检查。CallringBackend.commandApi 直接返回真实 bound root，旧小服务覆写 decorator/重复实例删除。两种真实 Graph 排空测试分别核持久 link/core/archive 和冷首次观察/公开 SQL 拒绝；详见同片实施证据的真实 RED/GREEN。T11 feed 与 T13 旧存储门保持。

T11 候选源码冻结：唯一 mandatory 累计 flow 覆盖生产、测试与 debug/AndroidTest fixture；旧事件协议、nullable feed、UiViewModel.observeChanges、Snapshot/HomeQuiet 双查询模式、ImmediateEditor null-token 分支、录音无 feed 模式、adoption_unavailable 与无用途 callers 参数已删除。各 route 盲刷、写成功补查和双 widget feed owner 已删除，保留显式用户刷新、真实系统/时钟/provider 观察及 WidgetRefreshSignals 错误呈现。Runtime/folder baselines 独立放在具名 observation tracker，不回塞通用版本协调器；实际授权有稳定 proof+observedAt、scoped/full-vector 最终 fence 与唯一 own marker。T12 数字语义、T13 全量初始化/fixture/目录、T14 CI整合及 T15 最终设备门仍有真实消费者与明确后续任务，不由本片代为关闭。最终验证与双审以本片证据和负责人记录为准。


## T13a 全状态协议删除候选

T13a关闭最后onCreate和test/debug/backendQa消费者，删除BackendStore/BackendState/StorageSession及旧四Store、TableSnapshot/fullDiff旧投影、stateLoadCount/coreStateLoads等不能再执行的计数。defaultWorkSchedule迁domain/model唯一实现；SQLiteDatabase.scalar、显式ID/sequence/旧编码SQL seed仅在debug测试支持，同一真实资源连接，无production测试接口。原纯算法与事务/分类/关联/CSV/ID/范围断言经现行窄入口执行，完整事实比较明确调用FullSnapshotReader。

当前候选仅完成T13a行为删除。T13b仍需目录/职责归位、bootstrap查询投影、UI bridge/designsystem与组件薄壳、最终全文件清单及PROJECT/README；T14统一CI和T15设备/最终验收仍未执行。不得将此候选记为T13全部完成。实际验证、源码指纹与后续审查状态见[t13a实施证据](t13a-implementation.md)及summary。


## T13b2b UI职责删除候选

ThemeRegistry/DefaultAppearance/偏好解析归唯一app/bridge；designsystem只保留ThemeDefinition/ThemeSpec及唯一CallringTheme(spec)，旧外观渲染overload、旧包入口与测试专用companion转发删除。Compose缓存保留palette身份，widget继续既有configuration模式。CallerCard与rememberFormBack迁feature/shared；UnsavedChangesDialog只有designsystem内一份纯参数实现。RecordingScreens、RecordingViewModels、SettingsViewModels三个mixedfiles删除，完整owner迁实际screen/VM，presentation内聚共享录音反馈。

本片不关闭T13b2c系统组件controller/PROJECT/README、T14统一CI或T15设备门。实际门、缓存与冻结见[t13b2b实施证据](t13b2b-implementation.md)。backend fixture未变，沿用[t13b2a来源](t13b2a-implementation.md)，不复制历史SQL样本。

## T13b2c 系统组件工作流删除闭包

六根组件原工作流主体已删除，唯一实现分别为 RuntimeEventController、CalendarSyncJobController、CallLogSyncJobController、RecordingJobController、CaptureServiceController、PlaybackServiceController。原类只保留实际Android生命周期、Intent/Job/通知/媒体/权限/调度资源接线和稳定注册身份，没有旧入口业务forwarder、typealias或第二工作流。controller不收Graph、Context、Service或完整业务API；既有gates、JobRuns与CalendarRefreshPolicy继续唯一复用。

T13代码和最终文件归属闭合，PROJECT/README已同步；实际验证与源码冻结见[t13b2c实施证据](t13b2c-implementation.md)。这不关闭T14统一CI或T15设备/最终验收。沿用[T13b2a归档43安全样本](t13b2a-implementation.md)（含2历史SQL基线），本片无额外归档；当前完整门执行原数据集测试。

## T14 守卫与工具入口闭包候选

新文件/改名 helper 的具名 operation metadata 数字选择、局部别名与 Java switch，实际 SQLite 资源 import/typealias 强转，以及旧 Context/全状态协议/资源 legacy/default capability/双 publisher 和 loader 的具体模式纳入守卫；正常分页、非操作 enum、显式目录排序/校验/导出、JSON/sealed/Keystore/自有 token 与直接持有具体资源的白盒 SQL 保留。静态规则不证明任意跨函数混淆。

旧 verify_backend/verify_ui 内各自维护的 Gradle 任务表、JVM/XML 汇总和 subprocess 路径已替换为唯一 verify_common；没有新旧入口分叉。原两个 CLI 继续可运行且执行全部新模块门，UI 专用设备/解锁/app-ops 恢复保留；backendQa 增单 APK/真实包/在线/安装/runner target/正数测试/限时/专包 finally 清理。两 CI 使用同一无设备入口，签名和版本约束保留。当前为冻结候选，完整门与双审以 t14-implementation.md / t14-verification-summary.json 及负责人记录为准；不关闭 T15。

## T15 生命周期最终缺口闭包

已删除 ConversationService.close 的 reset 代替最终关闭及重复资源清理；ConversationAssembly 以唯一 finally 关闭 QueryService/资源。终止查询、图/录音 lazy 初始化与实际存储关闭共用各自现有生命周期锁；不存在保留查询或 SQLiteOpenHelper 引用重新开库的旁路，reset 不复活已关闭会话。完整快照密钥读取有 owner 检查，来电排空先于物理关闭。真实竞态回归 RED→GREEN、完整门及双审 PASS，见 t15-lifecycle-review.md。本条关闭终审 A6 P2，不代替最终版本/远端 CI 与设备缺口报告。
