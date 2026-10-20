# T11 刷新与兼容路径删除准备（非验收）

独立只读复核基于 `b99c5ae`；当前 T06a 的过渡实现需在实施 T11 时补查。本清单落实用户“不要留下兼容代码和遗留未完全删除的代码”的要求。

## 最终删除清单

- contracts 的 `DataChange`、`ChangeFeed.observe()`、nullable/default `snapshots()`；累计 StateFlow 必需且无默认替代。所有生产实现和测试 fake 同步迁移，不保留旧 fake 专用生产接口。
- coordinator 的旧 `observe()` 与 `MutableStateFlow<DataChange>` 第二份状态；BackendContext 的 observe/events/revision、CallringBackend 的 observe 投影。
- UI 的 `UiViewModel.observeChanges`、ChangeObserver nullable/旧 feed 分支、HomeQuiet 双 loader/state/cumulative 模式、ImmediateEditor 的 null-token 强刷分支，以及后续新增的同查询迁移开关。
- RecordingViewModels 的可选 feed 与无订阅分支；各服务/adapter 的双发布、`notifyChanged`、全域 `runtimeChanged`、“adapter 未发布则补发”兜底。
- SnapshotViewModel 及 AI/录音等写成功后的无条件重查；实际提交由唯一 feed 触发查询。旧 `DataChange.affects` 与旧 SharedFlow/StateFlow 测试假件同时删除。

RequestLoader 仍可服务号码预览、目录读取等一次性请求；须删除的是同一订阅数据查询的双路径和兼容开关。

## 消费者闭包

1. 统一累计 feed 假件与生产实现，覆盖 epoch、域/实体和无操作。
2. 通用 SnapshotViewModel/ImmediateEditor、caller/list/report、Home/Overlay 均接累计令牌及参数代次；旧请求不得回写新参数状态。
3. AiSettingsViewModel 两处直接 collector；录音列表/详情按实际归档身份订阅，不能将正 statisticsId 直接当负 recordingId；ConversationViewModel 只替换 AI_SETTINGS 观察，保留独立消息状态流。
4. widget 的 provideGlance 与应用级 WidgetUpdates 都迁移，按当前实际 quiet/leaves/appearance 数据闭包收窄。WidgetRefreshSignals 还有错误展示/系统时间职责，不能一并删除。补核 widget configuration 的自动订阅。
5. Report/Overlay quality 依赖 RUNTIME；Overlay 若仍读完整 SettingsSummary 必须覆盖其全部字段，或同步改专用查询。

## 版本与冲突的删除边界

旧 UiEdits 的 `keys=setOf("core")` 准备→保存保护应随最终事务 dirty merge 删除。core source version 与冲突 key 是不同职责；不能为了删宽刷新而停止必要的命令冲突记录。

`CommandContext.expectedScopes=null` 仍代表完整 RevisionVector 检查；若收窄该模式须同步全部创建者，保留 incarnation/generation/前台授权/取消/自有提交继续。组合删除的 recording/preferences 集合 guard 必须覆盖确认后新增归档或绑定，不能只检查现有实体 token。domainMarker 与 own-command marker 仍用于密钥/复制/继续提交，不是旧 feed 适配。

## 验收反例

- 不提供累计 StateFlow 的 ChangeFeed 实现不能编译；订阅查询不能选择无 feed 模式。
- 生产和测试无旧 DataChange、nullable/default feed、fallback、双发布兜底；架构负例拒绝重新引入。
- 慢消费者跳过 A 后收到 B、恢复订阅、1025 实体淘汰和 epoch 更新仍可见；无关查询零增量，同次提交无双查，no-op 零失效/修订。
- 保存期间外部变化不能被旧成功结果覆盖，draft 保留；提交后取消不丢发布。
- 精确刷新不弱化新增归档/绑定、字段并发、密钥、自有提交与 incarnation 的冲突保护。

现有工具标识/schema、D 编号、持久值 codec、组件/Job/PendingIntent 身份与历史基线是现有协议数据。保留其语义须由最终唯一实现承担，不能借此保留旧业务实现或迁移桥。

## Runtime 实体与生命周期信号补充（4eac375只读预查）

T09b的publishRuntime已保证真实RUNTIME累计发布，当前仍是域宽impact。T10a开始迁铃声自然状态发布；T11必须按最终publisher复核capabilities、phone/audibility、ringtonePlayback、recordingPlayback、processing/jobs的实际依赖，不能把所有scope key直接投成跨域实体，也不能把报告仅需CALL_LOG质量扩成依赖所有播放变化。Report/CallerOverview/Overlay的质量查询、Home完整状态、Runtime完整状态和录音播放器各自闭包不同；补实际loader的“录音播放→报告0、相关权限→质量1”等反例。

SourceDependencies的D12/D19等固定源并集须随真实runtime key同步；精确UI实体与AI sourceVersion/命令scope仍三种不同职责。仅发布新fine key但源仍看旧runtime会造成源版本不推进；借发布runtime:capabilities覆盖所有播放变化则又过度冲突。

CallringActivity.onResume以及time/date广播当前递增refreshSignal，RuntimeViewModel组合status/diagnostics。系统设置返回与时间变化确实需要重新观测平台，不能一刀删除这些信号。整理为明确的生命周期/系统观察入口，去除对相同订阅数据的无条件全页面重查；设备未执行时如实保留权限/自然播放生命周期缺口。原PlaybackStateTracker.mark/clear只有赋值，由T10a补真实变化后发布，T11不能再在API成功回调补第二次同状态发布。
