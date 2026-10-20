# 旧实现删除追踪

用户要求本次修改不留下迁移兼容代码或未删除干净的旧实现。本表追踪仍有真实消费者的迁移路径；不是最终保留清单。切片审查同时确认新实现与删除项，最终所有待删除项必须关闭。

| 当前旧路径 | 仍使用它的业务 | 删除门 |
|---|---|---|
| `BackendContext` 及扩展式业务服务 | 仅余总装配、bound/decision mirror生命周期与SettingsApi/UiEditsApi组装；业务服务已迁出 | T10c迁出并删除类及全部引用；禁止替换成另一个万能上下文 |
| `BackendStore` 全状态 read/transaction、`BackendState`、`SqliteBackendStore` 全量装载/差异保存 | 初始化及旧fixture；T10b1已删recording findCall旧read，完整快照已迁专用事实 | T10b1目标录音查找已退出；T13初始化/fixture退出后彻底删除；T14检查禁止旧符号 |
| `StorageResources` 的 legacy 资源投影 | 上述全状态调用 | 随最终旧存储消费者迁出删除，保留统一的显式资源生命周期 |
| classifier/correlation 接受 `BackendState` 的入口及全状态决策 capture 重载 | 旧入口已删除；剩余 BackendState 的 WorkInputs 实现仅随读/快照和 fixture 继续退出 | T06b/T08 已用实际输入闭包；T09/T13 删除最后旧模型消费者 |
| schema 初始化/升级中使用旧全状态协议 | onCreate 仍用 StorageSession.load/save；upgrade 已于T08迁出 | T13 改定向初始化；T08 已删除升级 saveCalls/State 关联入口，最终守卫不得豁免初始化 |
| nullable `ChangeFeed.snapshots()` 默认实现及 UI 对旧通知的回退 | 尚未迁移的 UI 消费者、旧测试假实现 | T11 全部消费者及测试切换后删除 nullable/default/observe 回退；累计失效成为唯一生产协议 |
| 旧通知与整库版本/全域刷新发布 | 尚未迁出的旧写入链路 | 每片接入唯一提交者，T11 删除旧发布路径；不保留双发布兜底 |
| AI 数字索引/顺序分派 | 当前工具目录和业务命令绑定 | T12 迁为具名操作，删除按序号找行为的路径；外部现有标识仅作为协议元数据 |

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
