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

AndroidRuntimeProbe.getStatus每次生成checkedAtEpochMillis；T11若新增平台重观察的actual-diff发布，比较应针对实际capabilities/phone/audibility/playback等依赖事实，不能因checkedAt每次不同就推进RUNTIME。保留状态返回中的真实检查时间，将“重新检查成功但事实未变”与业务变化区分；用假时钟推进而状态不变的真实observer测试证明零失效，状态变化则对应实体推进。

基于a5e2a8f补查：RecordingRoutes仍有LaunchedEffect(Unit)手动refresh，RecordingViewModels构造又有feed初始发射；累计feed成为必需后，初次加载只能由一个明确入口拥有，不能保留route effect造成双查。AiRoutes的复制/credentialSaved与AI设置模型写成功refresh、录音详情import/retry/delete成功refresh都需与feed一起核对，测试覆盖初始化后一次实际提交只增加一次查询、route重组不增加查询、失败/noop不因成功回调补查。显式用户刷新和平台时间/恢复信号仍按真实职责存在，不能机械删除所有refresh调用。

## 诊断发布与消费者实际闭包（9aca4c4只读补查）

AndroidDiagnosticsStore.addAt仍调用coordinator.commit("diagnostics")，其changed会把全部DataDomain都失效。这是最后真实旧publisher之一，T11必须改一次实际持久完成后diagnostics资源版本及明确impact，失败不发布；同步删除无其它消费者的commit/changed/publishScopes/changes旧设施。当前没有DIAGNOSTICS DataDomain，可使用RUNTIME内明确diagnostics实体表达实际依赖；不借这次整理随意改业务数据域协议。D20 sourceKeys仍需按diagnostics资源推进。

RuntimeViewModel同时读取status与diagnostics，应明确订阅二者；Home/Report/CallerOverview/Overlay的状态或质量查询不因此依赖诊断历史。新增真实loader反例：诊断提交→诊断页面有关查询1，Home/report/caller/widget/AI设置无关查询0；播放提交→仅实际需要播放事实的页面，CALL_LOG权限变化→质量查询。不能只把全域改为RUNTIME域宽就宣布刷新彻底隔离。

WidgetUpdates的displayDomains当前含caller/position/calls/report/score/statistics等，必须按最终provideGlance实际读取字段核对（不是照抄旧域列表）；应用级observer与Glance组合内部observer不能对同次提交重复发相同更新。系统time/date的leave acknowledgement语义仍存在，由WidgetTimeReceiver按真实职责处理，不把系统信号一并删掉。

现有ChangeSubscription在每个域最多选一个实体key。Runtime既有多个事实时，应明确采用满足实际查询的实体分组/令牌组合，或有依据地扩充订阅模型；不能将一组scope key硬转为跨域实体列表，也不能为了诊断隔离漏掉Home所需权限/电话/音量/铃声状态。D05、D12、D19的sourceKeys按实际最终投影核对：D12当前只读ringtone设置与铃声playback，D19含RuntimeStatus、录音playback和live processing/jobs。现有live_probe诚实表示外部即时事实，不以一个持久资源version冒充所有provider观测的版本；不为机械整理引入无消费者的进度订阅框架。

T10c候选期间补查：`ui/app/RouteSupport.ActiveRefresh` 在每次进入RESUMED及signal/boundary/interval变化时先无条件调用current()，与feed初始发射及各route的LaunchedEffect初始化可能重复。T11应一起迁移其全部调用方，明确首次加载、真实时钟边界和权限/系统返回各自入口；不可只删RecordingRoutes的effect就宣称初次单查询。`ArchiveViewModel` 的 nullable adoptCommand/recording.adoption_unavailable 也只有测试省略参数，实际route始终注入；迁录音订阅时同步改必需能力，删除该假不可用分支及无使用的callers参数，测试按实际依赖构建。
