# T11 累计失效与刷新闭包实施证据

基线 `0ce2011e6a0f9b29b12830a6636dca91952bb966`，工作区 `C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring`。本记录为本片候选实施与实际验证证据；独立规格/质量审查结论由负责人另记。没有提交、暂存、推送、版本变更或设备操作。

## 唯一协议与加载 owner

- `ChangeFeed.snapshots(): StateFlow<InvalidationSnapshot>` 必需且无默认体。删除 `DataChange`、`observe`、nullable feed、旧事件 fake、`DataChange.affects`、`UiViewModel.observeChanges`。coordinator 删除第二份 `changes` 状态及 `observe/changed/commit/publishScopes/runtimeChanged`，保留真实 resource versions、scope markers、incarnation、domainMarker 和 own-command continuation。
- `ChangeSubscription.entities` 只接受域内非空实体集合；未指定该域实体才表示该域全部事实。令牌为所选实体及 wideRevision 的最大修订；跨域 selector 和空实体集合立即拒绝，没有 String 兼容重载。
- `SnapshotViewModel`、HomeQuiet 删除双 loader 和迁移开关。`ObservedQuery` 是带累计 token 的单个查询 owner，复用现有 `RefreshCoordinator`；caller/search/report 的参数代次随当前 query 前进，旧结果不能覆盖新参数。已有一次性预览、文件目录等继续使用 RequestLoader。
- collector 拥有初读。删除页面初始化/恢复盲刷、录音 route 初始 effect 和定时 payload polling；写成功回调不再补查。显式用户 refresh 保留。cancelled read 若已有 pending 变化且 scope 仍活跃，会重新调度；显式 cancel/owner 关闭仍终止本轮。

## 实际消费者闭包

| 消费者 | 最终失效闭包 |
|---|---|
| caller list/editor | caller + position options；editor 按号码，列表按集合 |
| report / overview quality | 号码 caller/calls/reports + 实际全局设置；RUNTIME 只 `capability:CALL_LOG`。岗位对已引用号码的影响由 PositionService 的 CALLERS/REPORTS publisher 表达，未引用岗位选项不使报表重查 |
| Home | 实际 home DTO 的业务域 + RUNTIME `status`/`ringtonePlayback`，不订阅诊断/录音播放 |
| overlay | overview 与 quality、完整 SettingsSummary 的全部实际字段、status/ringtonePlayback 各有独立 token；不以诊断唤醒 overview |
| Runtime page | status/ringtonePlayback/diagnostics；没有全 RUNTIME 或无用途 RINGTONE 设置依赖 |
| ringtone settings | 只持久 RINGTONE；该 DTO 不读取播放/平台 status |
| AI / scoped recording settings | AI_SETTINGS/RECORDING_SETTINGS 的真实表单闭包，加 RUNTIME `recordingFolder` 外部 provider 观察；writing 期间保留 pending，冲突只在 writer 结束统一重基一次 |
| conversation | AI_SETTINGS 的本地目的端观察；独立 conversation state 保留，不触发付费探针 |
| archive list / detail | 列表订阅 archives 集合；详情订阅真实 canonical archive，正 route 另订阅 `statistics:<id>` link 实体 |
| theme/navigation | 唯一 appearance / settings summary models 随 feed，不再每次 RESUMED 重读 |
| widget configuration | binding 按 widgetId；appearance；caller/position 搜索按参数代次，编辑选择不被观察结果覆盖 |
| displayed widgets | quiet/leaves/appearance，WORK_CALENDAR 只 leaves；不依赖 caller/binding/report/score/statistics |

## 录音身份、平台事实与生命周期

正 statistics ID 不能当作负 recording ID。详情每次 payload 读前保留绝对累计快照；以同次返回 canonical ID 对读前/读后 token 复核，有真实并发才重读。成功采用新身份时同步当前 token 基线，后续无关提交不会额外重查。null payload 时必要的 `getIdentity` 是一次窄 metadata 解析，不是第二次 payload 读取；失败作为真实错误呈现，不伪造能力或身份。link 建立发布 canonical 与 `statistics:<id>` 两个域内实体。没有 reverse link 全表扫描、新索引或 schema 升级。

`ArchiveViewModel.adoptCommand` 变为必需，删除无用途 callers 参数和 `recording.adoption_unavailable` 假分支。原共享命令 generation、字段合并、多资源 collection guards 和 tombstone 防护保留。

RuntimeObservationTracker 持唯一平台事实 baseline，由 root/bound capabilities 共用；时间戳不是事实。capabilities/audibility/phone/pin 支持变化推进 `status`，具体 capability/pin 支持另有精准 key。PlaybackStateTracker 是铃声播放唯一 publisher，后续平台 probe 不再第二次发布同一播放事实。diagnostics 只有真实持久文本改变才推进 diagnostics resource 与 RUNTIME diagnostics 实体；持久失败及18条相同事件饱和后的 no-op 都零发布。

RecordingFolderObservation 仅保留已观察 URI/provider facts。Activity 的显式 `UiApiOwner.reobservePlatform` 由装配根接线：平台 status 重观测，且仅已初始化 recording/AI 能力才重查已有 URI 的 provider facts；不为 Home 恢复装载/解密录音配置。Graph.reobservePlatform 明确在 configuration.dispatcher（生产 IO）执行整个重观测，避免 runtime 挂起恢复至 Main 后进行 SAF provider I/O。folder reobserve 捕获调用 coroutine，实际 provider 在 fence 外读取，最终 baseline/owner guard 同时 ensureActive；取消或已关闭 owner 不得发布读取结果。配置初读建立 baseline，未变恢复不触发 data 查询，provider 变更只使相关表单查询一次。新 URI 的持久提交由原 settings writer 发布，不重复认领为外部观察。

TimeRefreshSchedule 只在实际 elapsed interval、missed boundary、date/timezone、明确系统 time signal 或时钟回退时刷新时间派生数据。它的 baseline 由真正查询 owner（SnapshotViewModel 的 Home/Leaves 与 ReportViewModel）保留，组合销毁/导航 saveState 恢复也不重建。仅成功且被接受的查询按读取开始时刻校准，discarded stale 结果不校准；默认 SystemLocalClock 动态读取当前时区。短恢复零查，后台跨过边界则恢复时立即 catch-up；初次仍由 feed 加载。

SourceDependencies 同步真实 publisher：D05 只用 CALL_LOG 质量事实，D12 只 ringtone/ringtonePlayback，D13 加 recordingFolder 外部事实，D19 用 status/两个播放事实。processing/jobs 仍是 live_probe，不以持久版本伪装外部原子快照，也未新增无消费者进度框架。

## 前台授权与自有观察

新平台失效暴露旧系统隐藏的问题：Host 在授权回调先 `getStatus`，之后 deliver 的旧 command 检查会把本次合法授权判成 STALE。实际链路先真实 RED 后修复：

- 67 只观察目标 capability，46 只 CALL_LOG；69 观察 pin 支持，70 观察 OVERLAY，未复用整个 status。
- ForegroundCommandValidator 只对已 launch 且回执 VERIFIED 的 CAPABILITY 目标 key 允许预期迁移；其余原 scopes、active、incarnation、expiry 和最终 foreground gate 保留。实际 RuntimeStatus 不为 GRANTED/NOT_REQUIRED 时拒绝假 VERIFIED。
- 最终实际读取使用 marker→锁外 status→target unchanged 的最多3次稳定采样；若 probe 自身推进预期授权事实则重采。recordCapability 必传该次 proof 的 observedAt，不在记录时取最新 marker 覆盖旧 proof。最终 guard 对所有 command（包括 expectedScopes=null 的完整向量模式）独立复核 target unchanged(observedAt)，并保留原完整向量/其余 scopes 检查；最终 guard 在同一 fence 复核该 proof 及其余原 scopes 并记录该目标观察的 own-command marker，既有 engine continuation 只推进该目标。真正后续外来变化继续拒绝。
- APP_SETTINGS 只完成已启动设置页的 LAUNCHED 语义，容忍本次系统返回的 status 变化但不记录/前移任意 Runtime own scopes，不把打开设置当作权限验证。
- ForegroundActionService 的 checker 现在是必需装配，没有默认放行；hasHost 仍为 volatile 读取，不引入 fence/foreground 反向锁。

## Widget owner 与可验证边界

已核实际依赖 androidx.glance:glance-appwidget:1.1.1 的 sources.jar：GlanceAppWidget.kt 143–157 的 update 对 cold session 调 startSession，对 active session 调 session.updateGlance；AppWidgetSession.kt 202–211/252–254 的 UpdateGlanceState 重读 stateDefinition，不重调 provideGlance。来源：`https://dl.google.com/dl/android/maven2/androidx/glance/glance-appwidget/1.1.1/glance-appwidget-1.1.1-sources.jar`。这是源码核查，未声称设备实测。

应用 observer 保留 initial catch-up，使进程恢复可唤醒已有 widget；cold session 的 provideGlance 拥有一次 initial model.load，active session 只接应用 bridge 的 data revision。provideGlance 内删除第二个 feed collector，初始信号不再触发第二次查询。WidgetRefreshSignals 将 data revision 与 error presentation 分开：失败/后续成功 no-op 能显示/清除错误而不重读 active data；用户 retry 与系统 time/date 仍显式重读。真实 WidgetModel 计数覆盖 cold 初读、active 单提交、初读期间变化、i/j 的全局 leaves 及 error→success noop。

## RED / GREEN 记录口径

`build/qa/layer-boundaries/` 中本片日志：

- `t11-red-snapshot.log`：默认 Snapshot 写提交初读后重复查询/无操作补查的行为反例。
- `t11-red-diagnostics.log`：相同时间事件18条饱和后 no-op 仍发布。
- `t11-red-time-resume.log`：时间恢复 schedule 的实际到期断言 RED；实现后纳入完整门。
- `t11-red-cancel-pending.log`：read 取消会丢掉已 pending 的提交。
- `t11-red-ai-conflict-behavior.log`：AI 冲突同时直接重基及 feed pending 导致两次读取；统一 writer 结束后一次重基。
- `t11-red-permission-grant.log`：真实 DENIED→授权→tracker→deliver 链被旧 checker 错判 stale。
- `t11-red-old-capability-proof.log`：真实 wrapped runtime 先返回旧 Granted proof、期间另 probe 已发布 Denied，旧实现错误认领新 Denied marker；稳定采样与显式 observedAt 修复后 `t11-permission-stable-proof-green.log` 四例通过。
- `t11-red-full-vector-proof.log`：完整 RevisionVector 本身不含 runtime scope，旧 observedAt 不得认领新 target；独立 proof guard 修复后 `t11-full-vector-binding-green.log` 五个权限例及真实 i/j binding 模型例通过。
- `t11-red-review-resume-behavior.log`：真实 Compose 销毁/重建且保留 SnapshotViewModel，跨工作边界后 expected 2 reads、actual 1；初次及短返回已零额外加载。
- `t11-red-provider-thread.log`：真实 Graph→RecordingAssembly→SystemRecordingSource→阻塞 ContentProvider，query 实际仍在调用 t11-ui 线程。
- `t11-red-provider-cancel.log`：provider 返回前取消调用，旧实现仍发布 runtime marker（expected 0、actual 1）。
- `t11-review-delta-green.log`：上述修复后 focused 门 SUCCESS 13s，Compose 还覆盖跨 interval 补读、隐藏时 feed 成功校准后短返回零补读；阻塞 provider 期间 t11-ui heartbeat 可运行且不持 coordinator fence，未变 facts 零 marker。
- `t11-red-guards.log`：新增四组守卫负例在旧规则下未拒绝，21项中4项失败。
- `t11-final-focused-2.log`：本片定向综合门成功；其后最终门见 verification summary。

编译准备失败与真实行为 RED 分开：`t11-matrix-first.log` 是测试缺少 PositionRelation import；`t11-red-ai-conflict.log` 是误用不存在的 ThinkingMode.AUTO；二者不是行为 RED。质量补测的 `t11-red-review-resume.log` 缺少 AccessState import；`t11-red-review-resume-behavior.log` 的 app 部分首次遇 Robolectric 无 AndroidKeyStore，`t11-red-provider-dispatch.log` 缺 RecordingCipher 正确 package，`t11-red-provider-dispatch-behavior.log` 的反射字段名误写，以及首次 `t11-review-resume-green.log` 的源码移动 Python locale 读取失败，均为准备失败而非行为 RED；以随后真实行为断言及最终全门为准。`t11-permission-green.log` 中2写操作 fixture 未声明显式计划导致等待，终止本片 owned test worker，日志含 worker -1/connection reset，不是 GREEN；修正显式 OPERATIONS plan 与超时后，以后续实际通过日志为证。中间全回归发现额外 POSITIONS 订阅破坏真实1k/10k未引用岗位零刷新断言，已修复生产闭包，未删除断言。

## 最终门与剩余范围

完整命令、退出码、task state、XML计数及SHA、lint、源码 manifest、真实1k/10k JSON阶段副本见 `t11-verification-summary.json`。源码冻结后如有修复，必须由同一 Gradle owner 重新验证并更新指纹。

未执行设备测试。Glance 真 launcher、系统设置返回、真实权限/电话/播放生命周期仍需要 T15 隔离设备验证；源码与 JVM 测试不能代替设备行为。T12 数字操作分派、T13 BackendStore 初始化/fixture 全量协议与剩余目录、T14 最终 CI/守卫整合、T15 最终/设备验收仍未完成；本片没有把它们记为完成。

### 最终候选实际结果

最终冻结为114个 Kotlin/Java 路径，aggregate SHA-256 `e2a1f5fcde1c664d3fe901ee9bd74236c5692827dc1b741e64d7cf09998f8aa9`，逐文件 manifest `build/qa/layer-boundaries/t11-source-candidate.json` 的 SHA-256 为 `b340fe206e8f8eea7b74a9844b129690444ac78f3842271b3616c0d395a0bc1e`。完整 gate-4 exit 0，SUCCESS 2m18s，155 tasks（32 executed、123 up-to-date）；日志 SHA-256 `c9aee0471e8847e0305c5e26d502b14a0ca76c315391c92bf5c0f43ce3cec2a2`。

全量 XML：backend 354、UI 312、app 331，共997项，failures/errors/skipped均0；三个测试task实际EXECUTED。contracts:test 为 NO-SOURCE，不记作执行测试。权限真实链5例、WidgetConfigurationModel 6例（修正早先口述估计8）；新增保留查询owner的实际Compose恢复例1、AssemblyLifetime真实装配例7、AiSettingsMerge 12例，完整计数及 XML aggregate SHA 均在 summary。assembleDebug、compileBackendQaKotlin、ui compileDebugAndroidTestKotlin 实际执行成功；app compileDebugAndroidTestKotlin 为 NO-SOURCE。

两组Python守卫通过（backend117 JVM sources），守卫负例21+4通过。lint无Error/Fatal，现有UI30 Warning/3 Hint、app6 Warning保留。最终门产生41份真实fixture JSON，含1k/10k场景，已逐一复制到本片 `t11-fixtures` 并记录SHA；gate-3原41份另存 `t11-fixtures-gate3`，未覆盖既有历史副本。源码冻结后没有生产/测试源码变化；独立规格增量及质量复审的结论由负责人记录。
