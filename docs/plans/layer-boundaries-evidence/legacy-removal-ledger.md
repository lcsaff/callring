# 旧实现删除追踪

用户要求本次修改不留下迁移兼容代码或未删除干净的旧实现。本表追踪仍有真实消费者的迁移路径；不是最终保留清单。切片审查同时确认新实现与删除项，最终所有待删除项必须关闭。

| 当前旧路径 | 仍使用它的业务 | 删除门 |
|---|---|---|
| `BackendContext` 及扩展式业务服务 | 报表/首页、铃声、小组件/运行状态等尚未迁出的业务；设置/档案/来电服务已迁窄依赖，但当前总装配仍在 Context | T09–T10 逐片迁出，T13 删除类及全部引用；禁止替换成另一个万能上下文 |
| `BackendStore` 全状态 read/transaction、`BackendState`、`SqliteBackendStore` 全量装载/差异保存 | 尚未迁出的普通业务和旧完整快照 | T06b–T09 改为窄事务/专用快照模型，最后消费者迁出后删除；T13/T14 检查禁止旧符号 |
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

最终证据应包含生产与测试符号搜索、编译类路径、架构正反例，以及无旧路径可执行的回归结果。历史基线与审查文档保留原貌，不将历史文字当作当前代码残留。
