# T09b AI 源、完整快照与组合一致性实施证据

2026-10-21。生产基线 `b1bf4b0`（T09a已提交）；完整门执行 HEAD `390c651fbd661bcc62ad62c2958f74ad990e6171`，其间根仅提交后片准备文档。本片所有编辑限 `C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring`。本片实现冻结待规格→质量双审；不表示整个分层计划或设备验收完成。未暂存、提交、推送、装包或设备运行。

## SQL 源与专用完整事实

- `application/conversation/query/CoreScopedReadSource` 负责 work、范围、分组/评分/汇总、截止与完整性预算以及公开 JSON；统计使用唯一领域 `StreamingReportAccumulator` / `ReportTotalsAccumulator`，SQL visitor逐行消费，不把全 calls 缓存进后端。保留原 group≤200、deadline、Long边界、range、未知时长/未核验/未解决事实与 shared-score-v1。
- `CoreQueryFactsPort` 只暴露有租期的 `StatisticsQueryFacts` / `WorkQueryFacts`。SQLite adapter仅负责SELECT过滤/排序/分页/持久映射，readStatistics/readWork都在同一只读SQL事务中，callback结束后拒绝escaped lease；不调用业务服务/分类器/累加器，不构造BackendState。Work配置仍限当前候选及后续10日闭包，非完整业务快照。普通SQL不拿协调fence，version读取在事务外。
- 实际 `ConversationJson` + `StrictJson` 实现迁至 `app/data/codec/AiJsonCodec.kt`、`StrictJson.kt`；全部network、持久化及test/debug引用同步迁移，旧文件删除，无forwarder、无新解析器/库、无backend org.json依赖，严格校验及JSONTokener数字语义保留。
- `FullSnapshotReader.capture()` 返回专用 `FullSnapshotFacts`，仅显式complete入口收到该能力；无BackendState、next IDs、写状态或写能力。profiles/positions/rawcalls、历史/日历/override/leaves/version、intervals/score/quiet/lastMode/myPosition/overlay/audits均保留，嵌套集合深冻结。rawcalls使用唯一CallingRowCodec，保留hidden/current-original/linked screen/legacy tokens/account/screeningKey。
- `SqliteFullSnapshotReader` 复用同一资源、CoreReadSession、SQL连接与lease；原SnapshotPreflight在全量分配前运行，20000项/8MiB预检、服务层累计items/bytes预算、审计尾与秘密脱敏继续执行。显式full不再增stateLoads；普通query、聊天、发现仍零完整捕获。
- `BackendStore.captureFull`、`CallringBackend.captureCore`、BackendContext完整捕获、旧CoreSnapshotProjection及ReportCalculator删除；旧stats/currentSchedule/score/quality/visible/reportTotals/quietCopy/scheduleCopy/windowsCopy最后消费者迁出后删除。显式完整投影纯函数位于query/FullSnapshotProjection，继续复用领域唯一公式。

## D依赖与真实publisher矩阵

sourceVersion是下列固定资源集合的**真实提交全局last-change marker之max**，复用协调器scopeChanges；不是coarse core计数、UI观察令牌、hash/sum或时钟。`RevisionVector`公开形状保留；`SourceStamp.scopeRevision`仍为读取前捕获的全局披露/冲突marker。完整与scoped使用同一D集合并集；完整服务另复核内部D向量，补公开vector没有runtime项的空隙。

| D | 固定源依赖 | 实际publisher闭包 |
|---|---|---|
| 01 | profiles/calls/positions/myPosition | CallerCommand/CallingChanges、PositionService、本人人岗写入 |
| 02 | positions/myPosition | PositionService/本人岗位 |
| 03/04 | calls/score/schedule | calling/import/correction/delete、ScoreService、WorkScheduleService；aggregate也读score/default zone |
| 05 | statistics/calls/profiles/positions/myPosition/score/schedule/runtime | 上述各typed writer、统计区间、runtime观察发布；full quality/rolling并集 |
| 06 | schedule/calendar/overrides/leaves | WorkSchedule/Calendar/Leave及历史更正 |
| 07/08/09 | calendar / overrides / leaves | Calendar/Leave；leaveVersion随leaves提交 |
| 10 | quiet/score/statistics/overlay/leaves/myPosition | 对应typed writer；覆盖实际rawsettings与open intervals |
| 11 | appearance四个实际字段key | AppearanceService实际差异publishResource |
| 12 | ringtone/runtime | AndroidAudioController、现有runtime通知；配置与播放观察 |
| 13/14 | recordingConfig / aiConfig | 实际RecordingSettings写入publish；D13含provider观察 |
| 15/16 | recording | 实际录音event/link/archive/index/text写入 |
| 17 | recordingConfig:systemFolder | 实际目录配置写入；provider内容另外披露非原子 |
| 18 | widgets | AndroidPreferences及WidgetBindingsService现有真实变更分支补集合key，不借appearance的preferences变化 |
| 19 | runtime/runtime:recordingPlayback | runtimeChanged、AndroidRecordingRuntime play/stop/finishPlayback |
| 20/21 | diagnostics / audit | 实际diagnostics及calendar/override/leave审计写入 |

`runtimeChanged`补 `runtime:capabilities`，保持46/67/68/69/70原精确冲突key；录音play/stop/finish改为单marker的 `publishRuntime(recordingPlayback)`，发布RUNTIME累计impact、不推进archive版本，旧observe消费者仍能刷新。59原精确key、81 planner刻意粗recording/preferences及其他命令scope未放宽。Widget/Preference既有双发布/noop、Ringtone fallback的后续收口仍属T10/T11，未据此改变本片精确源依赖。

D05/D06/D09按时钟观察披露live_probe；同次work计算、D09状态各仅采一次clock。D12/D13/D18/D19同为live_probe，D17保留provider_walk_not_atomic，完整同步。provider撤权/改名、系统时间、installed/access、D19 processing/jobs均不由数据库marker证明物理原子；scoped D19比full多processing/jobs。现有workflow/job没有独立可原子版本，本片如实保留live观测；后续T10/T11的具名publisher整理不能改称已验物理一致。

## 一致性、资源与外部写边界

- StorageResources.version/consistent与直接SQLite源同步改D集合，三次相关重读后CONFLICT；QueryService外层捕获原始version与全局scopeRevision再复核，不能给旧事实贴新版本。实际SQL测试连续3次写quiet返回CONFLICT/无observations，随后读回all_numbers=1与sourceVersion/scopeRevision=3同版；score/default zone与override/leaves竞态分别拒绝。
- ScopedToolReader 2/4/5/9/51整个组合用相关D向量前后复核，冲突不交付混合数据；coherent不自动回放D17。ReceiptVerifier63/81及52读回同样复核，已提交命令变为COMMITTED_UNVERIFIED，不重放命令。原planner min-baseline/own-commits及receipt最终恢复语义保留。
- coherent以CoroutineContext attempt所有权记录本轮新refs/cursors/observations；失败仅撤销自己的新资源并条件恢复观察，不拿全session快照覆盖并发成功。连续513次冲突仍可分配text ref；真实并发另一次成功get的ref和同key观察保留。顶层成功剥离全局attempt所有权并清旧前驱；嵌套成功保留必要前驱至父生命周期可解析，不能成为独立的已成功观察。
- D17字节截断保留真实upstream continuation和未交付tail；source已complete但tail未返回也能继续。允许续页改变limit/maxBytes，identity规范化不变。D17游标成功后单次消费，普通SQL仍原keyset可复读；不把文件handle当walk token，不盲重放walk。
- QueryService会话cursor≤512、text refs≤512、observations≤4096；pending tail全会话≤4MiB。先预览投影与字节、统一容量检查，再原子登记refs/observations/cursor；替换D17游标预算扣旧tail。首项放不下明确item_too_large，实际测试无返回、无遗留handle且walk关闭。
- ForegroundResourceRegistry独立上限20000，与一次显式完整快照最多20000项相容；它是会话累计资源容量，和请求items/bytes预算职责不同。issuePending / delivered / discardPending只加最小引用计数：复用/已交付handle不因失败误删，未交付tail归pending所有，TTL/reset/拒绝时回收并关闭provider cursor。
- cached D17 tail交付前重新验证真实授权/目录。撤权、清folder不能从缓存泄露尾项；测试同时证明pending handle删除、已交付handle仍有效。源配置变化使旧cursor失效时也释放pending。
- 显式full D17使用同一pending资源机制；SnapshotPart仅有本次资源publish/discard回调，整份snapshot成功才标交付，异常/预算/取消/三次版本重读的finally回收。>20000目录先context_budget拒绝，不能先填registry再改报capacity。实际失败/重试、后续source失败、累计预算、成功与已交付复用样本均验。
- 原CSV捕获协调和外部documents.write未改；T09a真实一次外写、字节oracle及失败不重放样本继续全门执行，没有把外部写置入新重读边界。

## 行为RED与夹具问题

| 行为 | 真实RED日志 |
|---|---|
| D17三长名音频limit3/maxBytes800丢尾/STALE | t09b-directory-red.log；最终另覆盖limit4使upstream已complete仍有tail |
| 反复超预算get泄漏text refs | t09b-text-ref-red.log；实际原QueryService回退对照 |
| Quiet误使D04游标失效 | t09b-source-version-red.log；真实SQLite事实与提交marker |
| read9混zone/新事实、verify81 absence后重建误VERIFIED | t09b-combination-red.log（2 failures） |
| coherent连续失败耗尽refs | t09b-attempt-resource-red.log |
| cached tail撤权仍返回 | t09b-tail-permission-red.log |
| full D11/12/19贴core标签及runtime不进vector漏复核 | t09b-full-stamp-red.log（实际原snapshot实现回退，2 failures） |
| 整份full失败/重试未释放资源 | t09b-full-resource-red.log（临时去掉本次publish/discard闭包，资源lookup反例） |

先前测试编译类型推断、JUnit返回类型导致no-tests、FullSnapshot夹具最初少于21域、schedule夹具effective唯一冲突均纠正后再取行为RED/绿，不计产品缺陷证据。所有临时生产回退在运行后恢复，未留下生产双实现或tmp源。

RED日志SHA-256：

| 文件 | SHA-256 |
|---|---|
| `t09b-directory-red.log` | `dc55ebfc1c9968168ba3a1efbe08ca275486f0b929915495d7f49f9cef9f34a0` |
| `t09b-text-ref-red.log` | `95a780525da5ce87ef13a671bf2b4ec9d1a9147c399e128018d97c33482de0cc` |
| `t09b-combination-red.log` | `aef886d1dc4b206c0590641d0720f11001d8499b48ab49c855b3847305c4b3c6` |
| `t09b-attempt-resource-red.log` | `e573e4843bf00cb0af0fbeab7d8c93d4ee20b625ce6e0314a504e5dd12e0568f` |
| `t09b-tail-permission-red.log` | `7cb130af154c7181669acdc46a94a379da3836d66358c64bf219e2af523442ee` |
| `t09b-source-version-red.log` | `058cda606291940747e45035c569c8cc00cca5c571e5c4b62dc2155e3c411d5c` |
| `t09b-full-stamp-red.log` | `424a65f62ddddb90eb3c49df7e4e11696d49704afab51fb23f90cde54c69818c` |
| `t09b-full-resource-red.log` | `d4368adf10ee77876a92011595487c555407774e56780a45d0aeeb3febb3df44` |

## 规格首审后的两项窄修

首审仅P2：A/B同key重叠，A先失败、B后失败时，B.old可能恢复已经失败的A。新增真实CompletableDeferred编排，分别从空观察和预置成功观察开始；旧实现两个新增测试均RED（双失败及同父重叠子成功后父失败）。保留原A失败/B成功、513次失败容量和其余一致性样本。

修复仅在QueryService attempt增ACTIVE/SUCCEEDED/FAILED终止状态及父链；sessionLock内回滚时沿原前驱解析，跳过失败owner及失败祖先，成功顶层观察才视为稳定。嵌套成功保留必要的原前驱，供父失败或其他重叠attempt后续回滚解析；失败释放自己的refs/cursors并清暂存资源集合。没有恢复全session快照，没有改SQL/D版本/命令重放边界。

根另授权核实D17的真实租期反例：共享fake clock的Directory + QueryService + Registry，t0首页、t+6min第二页、t+12min第三页，qcursor每次续期但底层handle初次issue+10min过期。旧实现第三页错误成功，实际RED后，将现有validateRead改为(domain, pending rows)，cachedtail交付前同时验证真实授权/目录及每个handle的真实registry租期；过期/已移除返回CONFLICT stale，既有拒绝路径回收tail并关闭walk。未无验证延长旧handle寿命，旧重载删除；测试同时确认首个已交付handle不会因续页获得新授权寿命。

- `build/qa/t09b-overlapping-attempts-red.log` SHA-256 `356264882ca941f12bbec1cce079e092754e01334c5a14e5217a2f3caf7c0617`。
- `build/qa/t09b-pending-lease-red.log` SHA-256 `0a9fd7ba0524490c8cab462597acccf8afc32982a420d103920543ac1105b95b`。
- 窄修定向 `t09b-review-fixes-green.log` exit0（11s）；随后重新运行完整门，最终证据以下方最新日志/XML为准。

## SQL 1千/1万范围证据

最终门app/build/qa生成并复制本目录的4个JSON，仅固定标签/表名/计数，无号码、SQL/绑定参数、秘密。`scopedRows`定向D04目标号码：一次SCOPED_ROWS消费1行、一次count结果消费1行；`scopedAggregate`定向D05：calls一次消费1行，score settings固定4次/4结果行，latest schedule固定1次/1行。1000与10000无关B事实下完全相同。

这是**query invocations / successful cursor moves consumedRows**，不是SQLite engine扫描行数；不据此声称引擎零扫描或任意毫秒性能。两个入口fullStateLoads=0、completeCaptures=0、revisionDelta=0；原夹具继续检查B全部持久列/profile和audit不变。

| 文件 | SHA-256 |
|---|---|
| `scoped-source-access-1000.json` | `8f669dff515916f58fe2d0c767b5ba80529df0f9d5bc953f648eae4ecbacf9ec` |
| `scoped-source-access-10000.json` | `e0188c2d62898ff5a9987763388b4485c6e126a8918aee78d898958702171a33` |
| `sql-scoped-source-1000.json` | `1365a3ff5285d81758a7e2013a239ee4485de22c5a096ff6a0fbe99ac4d2dd18` |
| `sql-scoped-source-10000.json` | `1365a3ff5285d81758a7e2013a239ee4485de22c5a096ff6a0fbe99ac4d2dd18` |

## 最终门、CSV跨平台与限制

环境JAVA_HOME `D:/Apps/zulu17.54.21-ca-jdk17.0.13-win_x64`、ANDROID_HOME `D:/Apps/AndroidSDK`。

```powershell
.\gradlew.bat :contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug :app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin --console=plain
```

首审前两轮完整门均exit0（86s与50s），日志分别保留为t09b-full-gate-first.log与t09b-full-gate-pre-review.log；50s审前日志SHA-256 `46e21c64ad27da99c9e01c970d05476115799e24ec8ae13b8d3de6828224891a`。规格P2与根授权TTL窄修后，再次清三个模块旧XML并重新完整门：**exit0，94s，147 tasks（17 executed / 130 up-to-date）**。最终 `build/qa/layer-boundaries/t09b-full-gate.log` SHA-256 **`30e7d3f1136b853b36bc3093d29748d9e5a366d971df7cdc2291fe43b9b5d927`**。最终源门后无生产/测试源码调整。

| 模块 | XML suites | tests | failures / errors / skipped |
|---|---:|---:|---|
| contracts | 0 | 0 | 0 / 0 / 0（NO-SOURCE） |
| backend | 60 | 296 | 0 / 0 / 0 |
| ui | 70 | 298 | 0 / 0 / 0 |
| app | 59 | 280 | 0 / 0 / 0 |

最终门开始UTC `2026-10-20T19:07:40.5439134Z`；backend/UI/app全部XML均在此次门开始后生成（没有复用审前XML），细目见build/qa/layer-boundaries/t09b-verification.json。UI lint31 Warning/3 Hint/0 Error；app6 Warning/0 Error。两个架构脚本PASS，test_architecture17项/test_backend_boundaries4项OK；git diff --check通过。contracts NO-SOURCE如实，不声称存在contracts测试。

精确`.gitattributes`将原CSV oracle设为`-text`，防autocrlf把已保存的真实CRLF字节转为LF；根暂存后为这一文件加`whitespace=cr-at-eol`，使Git将真实CRLF识别为合法行尾而非尾随空白。没有重新生成oracle/放宽逐字节断言。工作文件1197 bytes、7 CRLF，SHA-256 `10615668b38d370cf649a56f358214e3ab0a2a821eb4da897022cd3a20eeb66a`，git hash-object --no-filters=`764a14aa570e511eed344399a9b6cedefdd00045`。旧HEAD blob的1190-byte LF转换问题由根发现；根已renormalize并独立验证index原始blob同为1197-byte、7CRLF且SHA相同。此为Git属性修正，未改最终门测试过的CSV字节或源码；Linux实际执行留远端CI。

冻结81操作golden/hashpin、12API源、schema、分类公式及产品行为未改。BackendContext仍有Runtime/Ringtone/Widget实际装配消费者（T10/T11）；BackendGraph recording findCall旧read属T10；BackendStore/BackendState/StorageSession/onCreate/旧fixture属T13。SettingsValidation仅真实widgetId验证留给Widget片；本片未新增仅服务fixture的生产协议或重载，既存transaction的fixture依赖须在T13彻底迁出并删除。

没有device/安装/真实Telecom、权限与SAF生命周期冒烟；Robolectric/窄port/真实SQL竞态不是物理provider原子性证明。debug APK为构建验证产物，不是发包。等待根规格→质量双审后决定T09b验收。
