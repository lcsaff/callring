# T10b2a 录音持久事务与唯一发布实施证据

2026-10-21。生产基线 `86fdfcf1f6ed385fab1a93d6e6eaad128025cca5`，验证 HEAD `83b105b65aa718b5fbee96875fb7768ae4eac54c`（两者之间只有负责人登记 b2a/b2b 的计划文档）。工作树 `C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring`、分支 `codex/layer-boundaries`。负责人在实施中明确接受 b2a/b2b 两个独立闭包；本片只关闭 raw data 自发布与 backend 持久提交闭包，不表示完整 T10b2 或媒体/API 迁移已经完成。未 stage、commit、push、创建线程或进行设备操作。

## 本片真实边界及删除

- 新增 backend `recording/RecordingArchiveCommands`，只依赖 RecordingArchiveState（get/generation/isDeleted）、RecordingArchiveMutations（CAS/delete）与共享 coordinator；它是所有生产归档 CAS/delete 的唯一业务提交者。实际 delta、精确 impact、持久 recording sourceVersion、scope keys 与 record(context) 在同一最终 fence 内产生。
- 新增 backend `RecordingRegistrationService`，只依赖三方法 RecordingRegistrationStore 与 coordinator/clock。登记的初始归档由 backend 构造；raw store 返回 eventInserted/archiveInserted。登记 event、初始 archive、tombstone 检查及派生索引仍在原同一 SQLite 事务，异常回滚包括已插入 event。statistics link 的唯一 INSERT 是同库原子语句，backend 在同 fence 解析精确 session identity、决定与发布实际关联变化。
- AndroidRecordingStore 删除 coordinator 字段、默认新协调器、所有 guard/publishScopes/changed/record 及旧 registerIncoming/linkStatistics/hasSession 和 Internal 写包装。保留唯一 raw insertIncoming/insertStatisticsLink/CAS/delete，以及 SQL CAS、codec、加密、同库事务、tombstone、派生索引和数据身份完整性检查；不是另一份业务 store。
- scopedReads 的 recordingVersion getter 必须显式注入。生产由原 Runtime 装配传入共享 coordinator 的 recording resource version；data 只转交 getter，SQL/codec 回调不读取它、不反向取得 fence。没有生产 default 0 版本。测试中只需静态 data fixture 的显式 0 getter是测试输入；源一致性测试使用真实共同 coordinator。
- Workflow 构造只取得五方法 RecordingWorkflowReads、真实 RecordingArchiveCommands 与 AiClient。旧 RecordingStore 聚合接口已无类型消费者，立即删除，未保留别名/兼容重载；app 数据资源显式实现多个窄接口。Workflow 原 running exclusion、版本生成、activeClients/cancel flags、分页/到期、manual/auto、transcript 复用和 backoff 保持。AI 请求期间没有 fence/SQL cursor。增加返回后取消检查，避免晚到分析把可恢复队列写成 COMPLETE。
- 原 AndroidRecordingRuntime 的真实 observeIncoming/link、noteMissing/captureFailure、attach/retry/delete 全部调用上述唯一 backend 写服务。retry/delete 中多余的外层 record 删除；import 外层仍需记录其绑定 command，因为媒体/API 业务迁移属于 b2b。没有新增 implements RecordingApi by service 或测试兼容 facade。

## 实际发布、源和命令闭包

| 事实变化 | 唯一 publisher / keys | 资源与累计 impact |
|---|---|---|
| 新 event + 初始 archive | RecordingRegistrationService；recordingEvents、archive:id | recording 各一次；RECORDING_ARCHIVES 的负 event callId 实体 |
| 已有 event 缺失 archive 的真实补入 | 同服务；archive:id | 同上，不伪造 recordingEvents 变化 |
| 新 statistics 关联 | 同服务；recordingLinks、recordingLink:statisticsId | recording 一次；实际关联负 callId 实体 |
| 真实 CAS/Workflow 转换/删除或新增 tombstone | RecordingArchiveCommands；archive:id | recording 一次；RECORDING_ARCHIVES 的 id 实体 |
| attach/delete 同时实际停止 native player | 同一 archive publisher 合并 runtime:recordingPlayback | 一个 marker，RECORDING_ARCHIVES 实体 + RUNTIME；core 不变 |
| 停止后 CAS/owned 删除失败 | Runtime 最小实际停止连接；runtime:recordingPlayback | 仅 RUNTIME，一个 marker；零 archive/sourceVersion 变化 |

publishResource 自动包含 recording 集合 scope，单次只推进一个全局 marker、一次 recording resource version、一次累计 impact。D15/D16 仍取该明确 recording sourceVersion；无全域 changed、无 publishScopes+changed 双链。只有真实播放停止才加 runtime scope，不用 recording version 冒充播放状态；自然 completion 原先释放 filesLock 后才 publishRuntime 的顺序保持。D19 与 command scope 规划器本片没有改动。

重复 register/link、同 generation 且完整值相同的 CAS、重复 tombstone 删除均不发布，但成功/noop 仍在同 fence record(command)。generation 不同或插入本来已保证实际变化，不能为了比较相同值再解码目标 payload；只有同 generation 的潜在同值 CAS 才读取目标作完整比较。前置 generation/CAS 拒绝、持久异常、非法/取消/无前台授权或过期 command 不发布 archive，也不记录失败成功。持久写已经返回后即使 active 变 false，也继续 publication+record，不把已保存的变化丢掉。

## 播放最小连接与失败语义

原 Runtime 仍是 b2b 的真实业务消费者，本片只传递 stopPlayer 返回的实际变化 Boolean；Workflow/commands 的 playbackStopped 参数必传，没有默认伪造变化。成功 attach/delete 的 archive 与 stop 合并同 marker；失败支路只发布真实 stop。无 player 时 archive 操作只有 RECORDING_ARCHIVES。

原锁序保持 fence → files/player → raw store → SQLite；native completion 先释放自己的 files/player 锁，再取得 coordinator。raw store 已完全退出协调器，数据库内部没有反向 fence。AI/network 在两次状态提交之间、锁外执行。新 callback 初始归档工厂只做 backend 值构造，不调用 coordinator。

真实 Robolectric/SQLite 测试覆盖：播放后删除成功只有一个额外 marker；非空 owned 目录不能删时，仅 runtime 变化，archive/tombstone/sourceVersion 不变且持久路径保留；SQLite trigger 拒绝 capture UPDATE 时，仅 runtime 变化，新文件删除、旧文件内容与旧 archive 保留。原 owned canonical-parent/先音频后归档顺序保持。跨文件/DB 非原子失败仍按实际事实披露，不声称本片已解决 b2b 的所有 rename/caller Job/prepare/generation 竞态。

## 真实 RED、回归及夹具迁移

| 验证 | RED 证据和观察 |
|---|---|
| raw data 零业务发布 | t10b2a-raw-red.log（原始文件 t10b2-raw-red.log 的原样拷贝）；改生产前 raw register 将 recording 从0推进1 |
| 一个登记一个 marker | t10b2a-marker-red.log；改生产前实际 marker 为2而断言1 |
| generation 转换零额外目标解码 | t10b2a-transition-red.log / t10b2a-decode-red.xml；新窄 fake 目标 get 原2次，修复后0次 |
| late analysis 取消后恢复队列 | 同日志 / t10b2a-cancel-red.xml；原实际 COMPLETE，修复后 QUEUED、analysis空、attempts不消费、nextAttemptAt=0 |
| precise publication 的必要播放连接 | t10b2a-playback-red.log/xml；只暂时回退最小 stop-effect 连接，成功删除缺 RUNTIME、失败删除缺实际 stop marker；随后恢复并验证 GREEN |

以上是实际断言失败，编译修正日志没有冒充 RED。raw/marker 是生产修改前运行；播放连接是最小差异回退验证，未冒称原始基线全测试运行。各日志及保存的 RED XML SHA 全部在 verification/summary JSON 中。

RecordingArchiveCommandsTest 只构造 state+mutations 窄 fake，覆盖同值/noop、同 generation 真实字段变化、拒绝/失败/stale、唯一实体、精确资源以及持久后 active 变化仍发布记录。RecordingRegistrationServiceTest 只构造三方法资源，覆盖不存在 session link/noop、register/link、continueOwnCommits、取消/前台/无效/失败。原 Workflow fixture 改为它真正需要的 reads/state/mutations；不能 listArchives 的安全断言改为窄接口形状断言，另有真实 SQLite 全页测试继续证明读取范围。

ToolBusinessIntegrationTest 中 disclosure/approval 期间的实际业务 CAS 全改真实 runtime.archiveCommands，RecordingScopedQueryTest 的途中 CAS 同样调用真实 commands 并用共同 recordingVersion；保留所有 cursor stale/变更后拒绝断言。CompleteSnapshotAssembly、NumberDeletion/metadata/parser 夹具初始 data seed 与存储 codec/CAS 测试仍可写 raw，但不再假设 raw 会推进业务版本。关联 fixture 可用 raw insertStatisticsLink；实际生产关联只经 RegistrationService。原 IndependentRecordingRuntime/CommandContext/Publication/Association/Workflow/NumberDeletion/FolderView/SharedBusinessCommands/ToolBusiness/Graph 测试均保留。

第一次完整 backend/app 回归 t10b2a-regression-2.log exit0；后续专项 t10b2a-targeted-green.log 与 publication-final-green.log exit0。最后的候选源码完整门如下，未为证据重复无关 --rerun：

```powershell
.\gradlew.bat :contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug :app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin --console=plain
```

主门 t10b2a-full-gate.log exit0，BUILD SUCCESSFUL in 1m 9s；147 tasks：24 executed /123 UP-TO-DATE。SHA256 `b0047f3f0a098378475ac0c4368f343eb3ff9dee17ff9e233ba5f410247a8d32`。backend:test 实际执行：64 suites/322 tests；app:testDebugUnitTest 实际执行：63 suites/308 tests；UI task UP-TO-DATE，沿用主门原70 suites/298 tests XML。三者 failures/errors/skipped 全0；contracts:test NO-SOURCE。每个 XML 时间戳/SHA 已记，不把 UI 缓存称作本轮重新执行。

Debug assemble、app/backendQa Kotlin 与 app lint analysis 实际执行；UI AndroidTest 编译及 UI lint analysis 等成功缓存如任务图所示。UI/app lint Fatal/Error均0；UI原Warning31/Hint3，app原Warning6，不称零告警。

两 Python 守卫 PASS：111 JVM sources、冻结81工具/12API/golden等兼容库存不变；17/4负例全部通过；git diff --check通过。architecture日志 SHA256 `54f687f873fb2544620f2fa7767d02a8a4cefb0ead71b97bd38dbd40ae7b8b20`。曾用 python -m unittest 带 tools 路径错误触发 import error，随后改为两个脚本的正确直接入口；不把该调用错误当产品失败或RED。

## 读取范围与可审计指纹

完整门重新运行真实1k/10k样本：目标 call各消费1行、hidden最多2行、coreStateLoads均0；metadata完整/list解码0，普通操作没有无关全文解码。新 commit 的插入/新generation路径额外 target get=0；play final generation仍由原断言证明只解码一次目标归档。

warm号码metadata仍需分别消费1001/10001个必要号码投影，之后只读1个目标payload metadata，不更改 Char.isDigit 语义；cold四类操作分别累计4004/40004行，sourceBytes832404/8356408，如实保留b1成本，没有把cold说成常量。分页1k冷热各968、10k冷热各9968（含空终页），10k sourceBytes2235644；future仍可能额外读取。原 v4 schema、持久JSON、4KiB UTF8 JsonReader skip、nullable重复与 required失败规则、原始D16过滤/Unicode尾文、派生索引完成定义均未修改。

当前测试输出从 app/build/qa 下原 b1 计数文件原样复制到 build/qa/layer-boundaries/t10b2a-*.json；verification记录每文件SHA，summary只汇总计数，不包含客户号码、音频路径或秘密。不是用b1旧运行冒充新运行。

完整模块源码/资源/Gradle566路径 SHA256 `aee9e2f921e53b32aa0d4a88a79e8f69b0be7632a1c82fdf33913caf065d21db`；本片变动源码/测试28路径（含5个新增）SHA256 `77b58fc6dc0b64c1c50c306ef976e2edaa6095820a26d01b5ea6a294c7f4d440`。聚合算法为相对路径排序，逐项 path+LF+原始字节+LF；逐文件SHA、全部XML/lint/日志/计数存于 `build/qa/layer-boundaries/t10b2a-verification.json`，其SHA256 `26d51fe236901b5625d12bfb776fe15222caa8721e4afa0a73b795b4a71049a6`。精简可审快照：[t10b2a-verification-summary.json](t10b2a-verification-summary.json)。新增未跟踪源码同样已逐行检查尾空白。

## 明确剩余消费者与冻结

T10b2b仍需迁移 AndroidRecordingRuntime 的 RecordingApi/bound facade、身份映射、导入/发现/capture业务、完整播放/stop与prepare竞争、真实组件调用与Graph.recordings装配；现存Runtime/Graph/CallCaptureService/JobService/Scoped/CompleteSnapshot/NumberDeletion是其真实消费者，不是为了兼容测试而新增的桥。stop重复noop/caller Job/final generation/tombstone/rename全失败矩阵也不冒称本片完成。T10b3继续组合删除/身份采用；T10c继续根装配/BackendContext；T11删除剩余旧feed协议；T13机械目录/旧存储门不变。

源码与测试本片正式冻结，等待负责人独立规格→质量双审；本实施者不自行进入b2b。后续发现修改须重新补证。未设备验证，不能把离线构建视为设备门通过。负责人在本片工作期间修改的t11-preparation.md属于未来准备，与本片源码指纹及实现范围无关。

最终逐文件核对日志 `build/qa/layer-boundaries/t10b2a-evidence-check.log` SHA256 `ed887c32667ae96b2987be6befa0ad84b784c9dc26c8a27379b2d95c77fdc3c0`；566/28路径当前源码、322/298/308各XML、lint/日志/RED/计数以及summary所引用verification哈希全部匹配，tracked+untracked源码尾空白及git diff --check通过。当时冻结后经下述规格P2定点解冻；补门与最终指纹以下节为准。


## 规格 P2：首个负归档插入与忽略插入修复

独立规格审查发现同一处 SQLite 插入结果判据错误：合法首个 `call_id=-1` 的 rowid 本来就是 -1，不能把 `insertWithOnConflict != -1` 当作成功；BEFORE INSERT RAISE(IGNORE) 还可能零行插入却提交 event。真实 RED `t10b2a-initial-insert-red.log/xml` 两项实际失败：首个负ID archiveInserted/index未确认，以及忽略初始归档时事件未回滚。RED日志SHA `23e12cea52e00672602c4ba9f756a58e1eaaf19222a76337ca2160da0264eb65`；对应原始XML SHA `1ab38121c3e42f9aebb0d4322d8ca952841fa480e3071d39d01a9da69554ef32`。

最小修复仅改 raw insertIncoming：INSERT OR IGNORE 后立即 SELECT changes()；1才写派生索引并报告 archiveInserted；0时明确查目标存在性，已有归档是合法noop，不存在则抛错并回滚整个 event/archive 事务，backend零发布。tombstone原先阻止自动初始归档的分支保留。另删除仅被数据类implements、没有typed消费者的闲置 RecordingArchiveQueries 接口及对应override；实际metadata/list/recent/number方法仍是Runtime真实能力，未来服务有真实需要时才定义接口。

相关 GREEN `t10b2a-initial-insert-green.log` exit0，14s；保存的 Publication原始XML `t10b2a-initial-insert-green.xml` SHA `d72d2514444a39588aa3a548974792de323d5e17c55b8ff8cf6e939144447dd1`。随后同一规定完整门 exit0：`t10b2a-review-fix-full-gate.log` SHA `f1eb0178c5dca5f2c7829bdfef37f3c56a73adf1ee283bdaf0f0185fcadfd9aa`，BUILD SUCCESSFUL in 1m15s，147tasks：17executed/130UP-TO-DATE。backend322和app310实际执行，全failures/errors/skipped0；UI298仍UP-TO-DATE并保留原XML来源。contracts仍NO-SOURCE。app assemble/backendQa/lint analysis实际执行，UI AndroidTest等成功缓存按日志披露。lint UI31Warning+3Hint、app6Warning，Fatal/Error均0。

两守卫与17/4负例再次通过，日志 `t10b2a-review-fix-architecture.log` SHA `8ec89b27ac029b5c9ab9d29c1dbf59af5d08b76d38575dcd1d38def999283590`。未扩大业务/媒体迁移范围。

主门原verification原始字节已保存 `build/qa/layer-boundaries/t10b2a-before-review-fix-verification.json`，SHA仍为 `26d51fe236901b5625d12bfb776fe15222caa8721e4afa0a73b795b4a71049a6`；原门、日志及原安全计数拷贝不覆盖。当前verification仅追加reviewFix：相对主门只有3个源码/测试路径变化，记录差异SHA而不复制另一份566路径清单；各模块当前XML聚合SHA及时间范围、所有补门日志/RED/GREEN XML、最终lint、当前原始测试安全计数均单列。旧XML哈希是历史主门记录，当前XML以reviewFix为准。

最终完整566路径源码 SHA `de84f6d15864676db0b2a83a432f2cba17fa11ff93011a241894fcc90f16bbac`；本片28变动源码/测试 SHA `9eec4149c0594bd1015aaec8aec413e793411cc1f3428d2bb9d1db049b85ccf7`。当前verification SHA `54f22fee9581241d8d2ce6be101f9ee12e21be2b3182053b6dc840a4282f9b5d`，summary的reviewFix与之对应。计数按本次实际fixture原始数据记录，路径长度造成的sourceBytes差异不靠修改oracle来匹配。

修复后源码/测试重新正式冻结，等待负责人最终证据回验及规格→质量结论；不自行开始b2b，未stage/commit/push/设备。


门后仅空白修正：双审PASS后，负责人暂存检查发现新增RecordingPersistence.kt末尾多一个空行，现只删除该EOF空行，无语义变化、未重跑Gradle。322/298/310结果仍对应上节语义一致的reviewFix候选（28路径SHA 9eec4149c0594bd1015aaec8aec413e793411cc1f3428d2bb9d1db049b85ccf7）。当前566路径源码SHA `e78c0c9ae81032c7efdb938d1b696b14011171ed3db0fecc9e3a67daa388cfda`，当前28变动源码SHA `e5187209de9c866eb7c16ec411f30ba27be5dbed12104f45b5d748b11281d4c0`；文件前后SHA见verification/summary的postGateWhitespace，仅追加该delta，未重造清单。当前verification SHA `7efccbb8ae9ec040c1340c55b7638380ff268636b8fd5fbdf9e0f4a561205345`。未stage/commit/push，交负责人重新stage。
