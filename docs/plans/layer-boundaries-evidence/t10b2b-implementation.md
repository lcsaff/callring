# T10b2b 录音业务与媒体边界实施证据

2026-10-21。基线 `a5e2a8ffc91f0e1f3229b1cc5e44b2f40f61f79f`；工作树 `C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring`，分支 `codex/layer-boundaries`。只关闭本片录音 API/身份/媒体/自动发现业务闭包；T10b3、T10c、T11、T13继续各自收口。未 stage、commit、push、操作设备或修改 primary 工作区。

## 真实边界与删除

- 新 backend `application/recording/RecordingService` 是录音业务及 command binding 的唯一实现，依赖明确的 identity reads、archive state、只读配置函数、共享 workflow/commands/registrations/settings、audio/prepared token、playback、directory 和明确调度/活动身份能力。没有 File、Context、MediaPlayer、第二状态机、第二持久 publisher 或万能 Dependencies。
- 身份顺序保持负 event、正 link、目标 archive metadata、历史核心 call、最终 link；getArchive 缺身份仍按目标 ID 查询。显式列表稳定 started 倒序；普通 metadata/号码/最近时段/pending仍走 b1 窄查询，没有重建 all/聚合 store。
- 删除 `AndroidRecordingRuntime.kt` 及其全部生产/测试类型引用。新增真实 IO `AndroidRecordingAudio`、`AndroidRecordingPlayback`，由 `bootstrap/RecordingAssembly` 组合唯一 lazy store/client/workflow/player/coordinator；Assembly 不实现 RecordingApi，没有 bound 或旧业务转发。Graph、Scoped/CompleteSnapshot、组件及测试全部使用真实 service/资源。close只关闭已初始化资源，不打开未用 store/client；读取身份不初始化 AI client。
- AndroidRecordingStore 新实现有真实 backend typed 消费者的 RecordingIdentityReads；原 raw state/mutations/workflow/settings/registration 能力不变。RecordingArchiveCommands/RegistrationService仍为 b2a 唯一持久提交/发布者，未改 frozen API 源、81工具、OX/D协议、v4 schema、持久JSON或组件 FQCN。
- CallCaptureService只提供权限事实并执行返回的 CapturePlan/MediaRecorder 操作；backend决定启用、身份、模式、错误记录与 tombstone/代次提交。phoneState 与 recorder启动原有权限差异保持。Job只执行/取消/调度任务；backend runScheduled 将目录发现失败和已导入 pending 处理隔离，并把真实 Job 取消贯穿自动导入准备及最终 fence。

## 提交、失败和取消语义

content URI、256MiB、16KiB流复制、取消、fsync、metadata准备在 fence 外；backend最终复核 caller/command、generation及自动 tombstone后才提交 opaque token 的 rename 和唯一 Workflow attach。GUI无 command 时也捕获真实协程取消。准备失效、rename/DB/CAS失败清新文件且保留旧持久路径；成功 retain 后清旧 owned 音频，清理失败不反称持久写未发生。capture只接受 owned 音频，准备不持 fence，最终代次/tombstone验证。

播放 prepare 在 fence 外，最终 caller、ticket、generation、command同 fence；失效候选释放。替换失败而旧 player已停止时仅发布实际 RUNTIME；stop无 player时零发布但仍 record command。成功 attach/delete 的实际 stop 与 archive提交仍合并一个 marker；owned删除失败保留持久路径供重试、零archive发布。natural completion/error先释放 player锁再请求coordinator，避免反序死锁。已持久后active=false继续publication/record的b2a行为未回退。

自动发现保持12h、原号码filename边界、本地yyyyMMdd[_-]HHmmss、±3s、双方唯一、MICROPHONE可替换及deleted跳过；未知文件仍可手选。folder配置只由AiSettingsService提交。配置 afterSave既有错误返回语义未改；Job的目录/授权失败不再阻止已导入 processing。

## RED/GREEN 与实际验证

| 新增缺陷回归 | 实际 RED / GREEN |
|---|---|
| idle stop 不应产生 runtime marker | `t10b2b-stop-red.log`：原两次stop断言marker0失败；迁移后stop+publication专项GREEN |
| GUI取消发生于metadata准备时不得rename/attach | `t10b2b-cancel-red.log/xml`：取消后原归档仍变为带audioPath；caller取消检查后 `t10b2b-cancel-stop-green.log` exit0 |
| Job取消发生于自动导入准备时不得attach | `t10b2b-job-cancel-red.log/xml`：准备中取消仍替换归档；取消事实贯穿后 `t10b2b-final-targeted.log` exit0 |

新增 backend窄fake业务12项覆盖真实身份顺序、稳定排序、prepared代次拒绝/清理、失败attach与stop、成功合并marker、播放替换失败、owned删除重试、capture权限差异、特定错误映射、Job目录失败继续处理与取消。真实SQLite/File/MediaPlayer回归补充：import CAS trigger失败后仅留旧文件与路径；GUI播放取消；native completion在持coordinator的并发观察下先释放player锁并最终只发布一次；既有stale/conflict/cancel断言未删弱。前述新回归和已有录音/工具/组合/快照专项通过后才运行整模块回归与统一门。

最终统一门：

```powershell
.\gradlew.bat :contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug :app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin --console=plain
```

`t10b2b-full-gate.log` exit0，BUILD SUCCESSFUL in 1m20s，147 tasks：23 executed /124 UP-TO-DATE；SHA256 `e07cb520b88819c0a6f5ae3e77ae62e4881604cdcd76b0465f0eeea068d969dc`。backend65 suites/334 tests、app63 suites/317 tests本门实际执行；UI70 suites/298 tests为UP-TO-DATE既有XML。三者failures/errors/skipped全0；contracts:test NO-SOURCE。app assemble/backendQa Kotlin/lint analysis实际执行；UI AndroidTest编译/lint analysis缓存成功，未冒称重跑。lint Fatal/Error0；UI31Warning+3Hint、app6Warning。

两Python守卫PASS（113 JVM sources、现行冻结兼容库存保持），`unittest discover -s tools -p test_architecture.py` 17例与 `test_backend_boundaries.py` 4例PASS；日志 `t10b2b-architecture.log`。未 global clean 或重复无关 --rerun。期间机械消费者编译修正和测试属性名修正只记编译日志，不冒称业务RED。

1k/10k安全计数从本次app测试实际写出的 `app/build/qa/layer-boundaries/t10b1-*.json`复制至 `build/qa/layer-boundaries/t10b2b-*.json`：目标call各1行、hidden最多2行、coreStateLoads0；warm号码1001/10001个必要投影、各1目标metadata，max源块4096字节，sourceBytes各800435。cold四类累计4004/40004行、sourceBytes832404/8356408，不宣称常量；分页各968/9968 warm及cold行，sourceBytes234176/2235644，future额外读取另记。未改Char.isDigit、4KiB streaming、D16索引完成定义或oracle来匹配数字。

## 指纹、剩余消费者与冻结

20变动源码/测试路径（含删除和untracked新增）候选SHA256 `f4f8c485cc37d724b619045ae606a5e1c30a632231efd8183bb0910ed41e5398`；算法是基线git diff --no-renames路径加所有untracked .kt/.java，相对路径排序，逐项path+LF+rawbytes（删除DELETED）+LF。完整清单在ignored `build/qa/layer-boundaries/t10b2b-source-candidate.json`；各XML来源/时间/SHA、lint、日志及实际计数在 `t10b2b-verification.json`，SHA256 `d8b13f5d3fa952c39a909962746cbe1bdb478318c932c965cf8d5696fe17d120`。可审摘要见 [verification-summary](t10b2b-verification-summary.json)。tracked和untracked新增源码EOF/尾空白及git diff --check均PASS。

仍有真实后续消费者：CallringBackend→UnavailableRecordingApi、BackendContext/总装配由T10c删除；app NumberDeletionService及Graph/SharedBusinessCommands身份采用由T10b3统一；旧feed由T11、旧完整存储/组件机械整理由T13。没有为这些门新增兼容桥。本片源码测试冻结，等待负责人规格→质量审查；无设备，A9未验证。
