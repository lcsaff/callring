# T10b1 录音读取与 AI 音频边界实施证据

2026-10-21。生产基线 `c188167b49fb49b2c91c1e26075ae9e34cf39c82`；运行 HEAD `0e2846fbd002d082157233ada7006693b0cde786`（负责人仅提交切片计划）。工作树 `C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring`，分支 `codex/layer-boundaries`。本片只完成 T10b1；T10b2 录音业务/发布、T10b3 组合删除/采用、T10c 装配仍有真实消费者。未 stage、commit、push、装包或设备运行。源码/测试正式冻结；主门及规格两项修复补门均已通过，交负责人规格→质量双审。不是整轮 T10 完成声明。

## 唯一读取路径及删除

- Graph.findCall 不再调用 `store.read`、遍历 calls 或创建全库 RecordingCallLink。backend `findRecordingCall` 在同一个 `readCalling` 租期内读取 requested ID；仅 hidden 请求再查 `hidden=0 AND linked_screen_id=? ORDER BY id LIMIT 2`，两者复用唯一 `CallingRowCodec`。hidden 要恰好一个可见关联；visible 使用其 linkedScreenId；旧 collection resolver 入口删除，无 fallback。真实 schema 的 linked_screen_id UNIQUE 仍在，实际 SQL 验证重复关联插入拒绝；多关联为 null 的纯规则仍测试。
- RecordingStore/AndroidRecordingStore 的 `all()` 删除，显式列表能力命名为 `listArchives()`，只供 Runtime 的显式列表功能。无普通查询或调度通过该列表能力回退。`metadata`、`generation`、`earliestPendingAt`、`dueCandidates`、`recentMetadata`、`numberMetadata` 分别声明目标/调度/发现/单号码所需事实。
- Runtime 的 identity、import 的请求身份/initial-final generation、noteMissing 存在性与最近 12h 自动发现走 metadata/generation；发现保留原 calls 快照和每候选当前 metadata 复核。12h/3s 匹配、activeIncoming、statistics link 优先、负事件 ID、历史正 ID、tombstone 保持。
- app NumberDeletionService 本片仅替换归档读取：必需的单号码 lookup 返回 ID/generation/status/audioBytes/started；预览与待删集合不再 listArchives，也不再自己构造 File.length。归档顺序仍 started desc、同时间 call_id asc，保证失败前实际已删集合不改变。绑定宽列表和组合业务唯一化留 T10b3，未新增 fallback。
- Workflow 的 nextRunAt/processPending 使用 metadata/candidate 端口；64 个候选一页，每页消费游标后才执行 AI，处理前仍重取目标并复核 generation/CAS，保持 running exclusion、cancel/client.cancel、manual/auto、transcription 复用及 retry backoff。attach 只读取目标 generation。pending 的真实集合和 Long.MAX_VALUE 排除规则保持。
- AiClient/Workflow 不再 import/构造/传递 java.io.File，使用数据型 AudioReference；CompatibleAiClient 和 AndroidAudioTranscriber 在平台将引用解析为 File，HTTP 上传/解码/分块算法不改。客户端 operation/cancel 从默认 this/noop 改必需；两生产实现已有明确覆盖，两个测试 fake 也显式声明。RecordingStore.isDeleted 默认 false 删除，无生产静默绕过 tombstone。

## 历史读取策略与实际访问范围

v4 schema、archives(id/generation/payload)、原始 JSON、D16 raw number 过滤及既有全文索引完成定义均未改变。

warm 路径只读与权威 archives generation 匹配的 projection。号码闭包流式读全部最小 raw number 投影，以原 `filter(Char::isDigit)` 算法匹配任意分隔符/Unicode 数字；只对命中目标读取 payload 的 audio metadata 和平台文件大小。不能用 raw 等号或固定 replace 列表替代。Unicode 数字不转 ASCII，`١٢٣` 与 `123` 仍不匹配。

cold/旧 generation projection 路径在同一只读事务中枚举未索引 ID/generation，用 `substr(CAST(payload AS BLOB), offset, 4096)` 的 InputStream→增量 UTF-8→JsonReader 提取必要字段并 skipValue 全文。不会 cursor.getString(payload)、JSONObject(payload) 或构造整条 CallArchive/transcript/analysis；元数据对象仅保留必要字符串与标量，源缓冲最大 4096 bytes。必要 id/generation/number/started/status/source/next 保持必读和非法值失败，nullable audio/duration 保持原语义；重复字段按后值覆盖。每块及每行复核取消，取消不返回假零。

metadata 读取完全不写 archive_projection、archive_parse_progress、text_fields/chunks；不把只含 metadata 的历史行冒充全文索引完成。遗留 2,500,225 bytes 转写在 metadata 操作后原 payload 不变、当前 generation 仍未 indexed、parse_progress 无行，之后 D16 继续原完整索引并实际读取 transcript 最后 8 codepoint，验证 LEGACY-尾。D16 格式化 raw number 可命中，归一化号码不误匹配。

以下是实际消费行/源字节计数，不是 SQLite 引擎扫描行，也不以耗时证明边界。不存在“所有历史常量读取”声明。warm pending 最早时间需流式检查所有 pending metadata。初版 due 每页检查全部剩余 pending metadata，曾引入可避免的多页放大；已按下方质量 P2 补证修正为两个分区各自读取到 limit 个真 due 即停，再取全局前 limit。future/nonpending 历史行可能导致额外读取，64 不代表最多消费64个源行。未索引旧历史每次操作都需重扫，未持久化另一套索引。

| 场景 | 实际读取 |
|---|---|
| Graph 1k / 10k 无关 calls | 普通目标各 1 consumed call row；hidden 闭包各 2；coreStateLoads=0，LOAD/SNAPSHOT_ROWS=0 |
| warm 1k / 10k 无关归档 | 四次查询共 4 metadata rows；单号码 raw number 1001 / 10001 rows；只读取 1 个目标 metadata payload，sourceBytes 800434 / 800436，max chunk4096；无关归档全文对象=0 |
| 未索引 1k / 10k 无关归档，四操作 | coldIdentityRows/payloadMetadataRows 4004 / 40004；sourceBytes 832404 / 8356408；sourceChunkQueries8008 / 80008；max chunk209 / 210；fullArchiveDecodes/listArchiveDecodes=0 |
| 单个旧格式大转写，五次 metadata 操作 | payloadMetadataRows=5，sourceBytes12501125，metadataCharacters25，max chunk4096；未 materialize transcript/analysis 或写索引 |

warm 字节计数会受合成夹具音频的运行期 cache 路径长度影响，当前分别如表所列；路径未写入计数证据。相同目标解码次数与无关全文对象为零分别独立断言，不拿微小字节差伪称相同。

最小 metadata 结果集合（如最近 12h 候选/同号归档）仍随必要业务结果大小增长；不能把源块有界描述为所有返回集合常量内存。安全计数只保留固定 key 的累计数，不包含号码、路径、SQL 参数、转写或异常正文。原始 JSON 观测在 `app/build/qa/layer-boundaries/t10b1-{call,metadata-*}-*.json`。

## 真实 RED 与保留回归

- `t10b1-call-red.log`：Graph 1k/10k 两断言都 expected coreStateLoads=0、actual=1。最小目标 SQL 修复后相同入口通过。
- `t10b1-workflow-red.log`：调度普通操作 listReads 实际为2、期望0；AiClient 反射仍发现 File 参数。随后改端口与所有生产/fake 消费者，完整 Workflow 回归保留。
- `t10b1-preview-red.log`：组合预览实际调用 listArchives 一次、期望0；改必需 metadata lookup 后通过。
- `t10b1-order-red.log`：部分失败前预期访问[-2,-1]，实际[-3,-2,-1]；恢复原排序后验证较旧归档、失败归档、核心档案与绑定均保留，后续步骤停止。
- 新测试还覆盖实际 positive/negative/Long.MIN_VALUE、linkedStatistics 优先、tombstone、generation 过代、缺失必要 metadata 拒绝、中途取消、warm/cold 1k/10k、任意分隔符与 Unicode、D16 raw 过滤/大转写尾、140 正负候选跨 64/64/12 页。原 IndependentRecordingRuntime/NumberDeletionIntegration/RecordingAssociation/CommandContext/FolderView/Publication/Workflow/AI/SharedBusinessCommands 等语义测试没有删除安全断言。
- `t10b1-play-generation-red.log`：播放一次 fullArchiveDecodes 实际2、期望1；final ticket 用 generation 复核后，目标 prepare 仅解码一次，归档 generation 不变。
- `t10b1-nullable-red.log`：合法重复 audio/duration 先非空后 null，原 RecordingJson 结果均为 null；新 metadata 错留前值。NULL 分支显式清空后同时断言 hasAudio=false、duration=null、audioBytes=0。
- 开发期间的编译错误、JUnit 方法返回类型错误和触发真实 UNIQUE 的夹具修正均只作调试，不冒作 RED 证据；最终完整门只依据冻结源码对应新 XML。

## 真实后续消费者

- AndroidRecordingRuntime 仍实现 RecordingApi、管理身份/导入/attach/retry/delete/播放/发现等业务；AndroidRecordingStore 仍为这些真实消费者提供 CAS/delete/register/link 及旧发布。这些是 T10b2 工作，未称迁完。
- Workflow.retry/process 需要目标完整 transcript/analysis 做状态转换；Runtime.getArchive 是明确详情；Runtime.attach 的旧音频清理、captureFailure copy、delete 的 owned 路径和 play prepare 仍有目标 get 消费者；play final 只查 generation，不再第二次解码目标归档。这些都不扫描无关归档，后续 T10b2 将收窄音频/最终提交与唯一发布边界。
- CoreNumberDeletionApi/CallringBackend 和 app NumberDeletionService 仍有真实直接消费者；widgets.listBindings 仍供本片未迁的组合步骤，T10b3 统一唯一服务/绑定 lookup/集合 guard。SharedBusinessCommands 仍接受 CallringUiApi，身份采用收窄也在 T10b3。
- BackendContext/完整生命周期/UiBridgeAssembly 在 T10c/T13；旧 DataChange 协议在 T11。没有借这些后续事项保留本片已无消费者的 all/collection resolver/File/default 路径。

## 主门与规格修复验证（分页质量修复前）

完整主门与补门均 exit=0。JAVA_HOME=`D:/Apps/zulu17.54.21-ca-jdk17.0.13-win_x64`，ANDROID_HOME=`D:/Apps/AndroidSDK`。

```powershell
.\gradlew.bat :contracts:test :backend:test --rerun :ui:testDebugUnitTest --rerun :app:testDebugUnitTest --rerun :app:assembleDebug :ui:lintDebug :app:lintDebug :app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin --console=plain
```

两个 Python 守卫与 17 architecture / 4 backend boundary 测试及 git diff --check 已通过，日志 `build/qa/layer-boundaries/t10b1-architecture.log`。未改 allowlist/pin/兼容 golden/12 API/组件身份/schema。设备未验证，离线门不等于真机通过。


主门日志 `build/qa/layer-boundaries/t10b1-full-gate.log` SHA256 `4ca3038a37f076b640226667270af623a7ad84ecbad638a68fbca20ea7e17ef0`；BUILD SUCCESSFUL in 1m50s，147 tasks：24 executed / 123 UP-TO-DATE。backend/UI/app 三个 test 任务均通过任务级 --rerun 新执行；contracts:test 无测试源码，NO-SOURCE。主门新 XML：backend 62 suites / 315 tests，UI 70 / 298，app 62 / 297；failures/errors/skipped 全部0。主门完整560路径源码 fingerprint `b753808a2ad1513b49342ac217381ef9d7572129e97972ab8a6179042ed46114`。

主门后按规格反馈只解冻两个已知问题：play final 的目标 generation 冗余 payload 解码、nullable 重复字段最后 null。两项各先真实 RED 后最小修复；生产2文件/测试2文件的差异已逐路径记录在 verification.json.reviewFix.deltaFromMain。然后重新冻结，执行同一完整任务列表（去掉测试 --rerun）：

```powershell
.\gradlew.bat :contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug :app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin --console=plain
```

补门日志 `build/qa/layer-boundaries/t10b1-review-fix-gate.log` SHA256 `7b2f2bc1598225478bb53efb15688d493096588631a2afc41660c96c93600552`；BUILD SUCCESSFUL in 1m10s，147 tasks：16 executed / 131 UP-TO-DATE。app test 实际重新执行，最终新 XML 为62 suites /299 tests，failures/errors/skipped=0/0/0；backend315/UI298 test任务诚实记 UP-TO-DATE，引用上方主门 --rerun 的同 SHA XML。app Debug/backendQa/assemble/lint 实际编译验证此两处生产修改；UI AndroidTest/构建等由本任务图确认成功缓存，不冒称全部重编。

UI/app lint Fatal/Error 均0/0，Warning分别31/6，UI另有Hint3；不把现有告警称为零。两守卫仍PASS（108 JVM sources），17/4测试全部通过，规格修复后日志 `build/qa/layer-boundaries/t10b1-review-fix-architecture.log` SHA256 `23dc5378333a49c70c2cbe77cd045c26f7d7bf28c657015b49e6157c6d3e15d6`。diff检查通过；未跟踪新增源码也纳入完整源码指纹和尾空白扫描。

最终变动源码/测试25路径（含新增）聚合 SHA256 `ac0110256bd3cb9a18bf1030a95b4d7bfe85de29e8ca62397c4c1cc2a101d291`；完整模块源码/资源/Gradle560路径聚合 SHA256 `4e51cdb06b848040ea0290ff6acd421c038b35be78a4ff00dbefef6f243f13b3`。算法均为排序相对路径+LF+原始文件字节（缺失用DELETED）+LF；每文件 SHA、主门/补门源码与全部 XML 时间戳/SHA、lint SHA、所有 RED/验证日志 SHA、安全计数 JSON SHA 均在 `build/qa/layer-boundaries/t10b1-verification.json`。这些观测只对应本片冻结版本；后续双审若有修改须另补证，不把本次 XML 冒作新源码执行。

正式冻结之后只允许负责人独立规格→质量审查；本实施者不继续 T10b2/T10b3，也不顺手修改。设备状态仍为未验证。

前次冻结逐文件校验 `build/qa/layer-boundaries/t10b1-final-evidence-check.log` 当时已通过：完整560/变动25源码当前字节、backend315/UI298/app299当前XML、lint/日志/观测JSON SHA均与verification记录匹配，新/旧变动源码无尾空白，git diff --check通过。该阶段 verification 原始字节原样保存到 `build/qa/layer-boundaries/t10b1-pagination-prior-verification.json`，SHA256 `4e114fa9d34ccbc6712d6287871233c21c9877d2245a6d273ceec7a760617336`；前两门记录不覆盖，当前 verification 另增 pagination 阶段。


## 质量 P2：多页候选源读取放大修复

仅解冻并修改 `RecordingMetadataReads`，新增真实 SQLite `RecordingCandidatePaginationTest`；与前次冻结比较只有这两个源码/测试路径变化，没有扩大到其它业务。最初 warm/cold 已按 ID 排序却每页读完剩余源行，只用 TreeMap 留64；完整处理1万条全due会重复读取大量metadata和cold文本。不能用内存候选有界掩盖源读取放大。

真实 RED：`t10b1-pagination-red.log` 中，1k/10k冷热混合全due的第一页分别消费warm/cold=500/500和5000/5000，违反每分区最多64个due前缀断言。RED XML和实际计数原样保存为 `build/qa/layer-boundaries/t10b1-pagination-red.xml`、`t10b1-pagination-red-{1000,10000}.json`；不是编译错误或MemoryStore模拟。

最小修复：warm有序游标读取到limit个真正due候选后关闭；cold的唯一metadata流式visitor返回是否继续，到其limit个真正due后关闭。两个分区始终各自查询，不能warm满就跳过cold；合并只取全局前limit。earliestPendingAt/recentMetadata/numberMetadata的visitor继续读完原必要范围。原generation匹配/旧projection归cold、正负ID顺序、取消、必要字段/nullable重复解析、原始payload和全文索引完成定义不变，没有索引写或新默认回退。

真实 SQLite 多页GREEN不只检查第一页：冷热各半、正负ID、部分旧generation projection、cold大文本；每页断言两分区prefix，完整结果与所有预期ID/generation逐项相同；140候选的MemoryStore测试继续保留但不冒充本次SQL证据。

| 真实场景 | 分页结果与消费计数 |
|---|---|
| 1k全due冷热混合 | 1000个唯一候选，16个非空页+1个空终页；warm968/cold968，cold sourceBytes234176 |
| 10k全due冷热混合 | 10000个唯一候选，157个非空页+1个空终页；warm9968/cold9968，cold sourceBytes2235644；每页每分区消费最多64行 |
| 180行混合future，limit7 | 返回120个到期候选；warm175/cold175，sourceBytes38679。future需要额外读取，不声称每页至多7个源行或所有数据分布总读取线性 |

上述计数均为游标实际消费行，不是引擎scan。全due用例总源行各不超过2N；没有把这个断言推广到future/缺少可搜索投影的全部历史。两次取消分别在warm读取和cold源块读取中触发，返回CancellationException而非假空；断言无业务marker变化、projection当前/旧generation行数保持、parse_progress仍空。新原始安全计数随证据保存为 `docs/plans/layer-boundaries-evidence/t10b1-pagination-{1000,10000,future}.json`。

相关回归 `t10b1-pagination-green.log` exit0，22s：新SQLite3项+原metadata7项+Workflow12项通过。随后源码/测试候选冻结，独立执行相同完整任务列表、不加全--rerun：

```powershell
.\gradlew.bat :contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug :app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin --console=plain
```

分页补完整门 exit0，BUILD SUCCESSFUL in 1m9s，147tasks：15executed/132UP-TO-DATE；日志 `build/qa/layer-boundaries/t10b1-pagination-full-gate.log` SHA256 `4e2d099385b7db9c9b00d7a571dd90c02b59ccf075d61030c33f1e31f0b86963`。backend:test实际执行，62suites/315tests；app:testDebugUnitTest实际执行，63suites/302tests；UI任务UP-TO-DATE，仍用主门--rerun的70suites/298tests XML。三者failures/errors/skipped均0。不得把backend本轮新XML误写为缓存。contracts:test仍NO-SOURCE。

app Debug/backendQa/assemble/lint及相关依赖均由本次任务图验证；UI AndroidTest等成功缓存如日志所示。UI/app lint Fatal/Error0，Warning31/6，UI Hint3不变。两守卫108 JVM与17/4负例及diff检查均PASS，分页专项日志 `build/qa/layer-boundaries/t10b1-pagination-architecture.log` SHA256 `e90de28d3552cfe7e1d37a5829c23cfe9f1994f612ebbed82b6aaa5404d34ba4`。

最终完整561路径源码/资源/Gradle fingerprint `08680c200c2ddffad3d19dbebcc70958e1165b821570830e0b3179d05ea68d66`；最终变动26路径源码/测试fingerprint `4eb7c9db7b3fccdbe3ca5a9e57dcfc168833368d577bda89a1b75487da5899c8`。本轮完整源码、每XML时间戳/SHA、lint、RED/回归/完整门日志SHA、所有新分页计数原始JSON保存在 `build/qa/layer-boundaries/t10b1-verification.json.pagination`；精简可审快照为 `docs/plans/layer-boundaries-evidence/t10b1-pagination-verification-summary.json`，前两门main/reviewFix及原计数SHA未改写。

此P2修复后重新正式冻结，等待负责人规格核对变化→质量复审。未stage/commit/push/设备，不继续T10b2/T10b3，不扩大验证。

分页最终逐文件核对日志 `build/qa/layer-boundaries/t10b1-pagination-evidence-check.log` SHA256 `31ae7a748e3e31cd2da8005b3ee75f05d1f6dd631db194f959a55b4bef335f7c`，确认main/reviewFix原记录未变、561/26路径源码与backend315/UI298/app302当前XML及所有新日志/计数SHA匹配。扩充分页阶段后的 `t10b1-verification.json` SHA256 `4b8c48d1efdf2ce7a584f689abedf764f75c85f1140357dc7e86365922624a68`。本补节之后无源码/测试改动。
