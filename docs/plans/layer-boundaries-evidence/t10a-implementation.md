# T10a 配置与小服务实施证据

2026-10-21。生产基线 `4eac375`；实施及最终门 HEAD `99d5178fbf071a734685c48a6acef24c72a2887e`（其后仅有本片未提交修改及负责人准备文档）。所有编辑限 `C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring`、分支 `codex/layer-boundaries`。本片完成后源码与测试正式冻结，等待负责人独立验证、规格→质量双审；不表示 T10b/T10c/T11 或整轮分层已经完成。未暂存、提交、推送、装包或设备运行。

## 配置唯一命令与持久化边界

- `application/recording/AiSettingsService` 只依赖 `RecordingSettingsStore`、协调器、麦克风权限观察、连接测试、folder 观察、提交后回调及绑定 command。字段 Keep/Set 合并、字段冲突、完整验证、secret scope、copy、所有安全 view 与 Combined 均留在同一 backend 实现。app 旧文件删除，无转发。
- `RecordingSettingsStore` 只有读取及原子 `persistSettings`；AndroidRecordingStore 只加密/codec/SharedPreferences 原子持久化及失败恢复，不再合并、业务验证、计算差异、发布或记录配置命令。实际配置 scope 映射移入 `recording/RecordingSettingsChanges`，没有第二套 app diff。
- Keep 读取当前值；Set 仅比较该 base 字段与 current。Replace/Clear 先解析 `domainMarker(aiConfig, baseRevision)`，再查 `ai:slot:key`，不以 configured Boolean 替代。copy 复核 source/target 五字段与目标 analysis compatibility，保持 AUTO、thinking DISABLED、transcription OPENAI，以及 `copyToAnalysisProfile` 和原安全投影。
- `publishResources` 只扩展既有协调原语：明确已知资源集合各加一次资源版本，同次只加一个全局 marker/一次累计 impact。Combined 的 AI+录音真实变化各加一次对应资源，core 不变；noop 仍 record command，但无写入/修订；失败不发布。
- fence 内完成 final command/caller-cancellation 校验、保存、真实 delta 发布、record、返回配置和所属 recording/AI version。folder/provider 投影、afterSave 与连接测试在 fence 外；返回旧值不得借用之后的当前 version。view 函数必须显式传入所属 version，未保留默认“稍后再读当前 revision”的入口。
- caller Job 在取得 fence 时与 persist 前检查；一旦 persist 开始，后续同步 publication 不再检查取消，避免已保存却丢失修订。真实 latch 分别覆盖 persist 前取消零写、persist 后取消仍发布、afterSave 与 folder callback 期间另一提交；不是只用 command.active 代替协程取消。
- 保持原 afterSave 契约并单测：patchRecording 的回调异常映射 STORAGE_FAILURE，但先前数据/marker 已提交；Combined 回调 best-effort。未借本片改变这项产品行为。
- 删除无生产调用者的 `RecordingConfigurationPort`/`RecordingCapability`，以及 Runtime/bound 的 getSettings/saveSettings/testConnection 管理入口；setSystemFolder 授权后走同一配置命令。Workflow fixture 使用明确 seedSettings，app fixture 直接种入明确持久端口，不保留旧管理实现或默认兼容重载。

## 三个窄服务与音频准备

- `runtime/RuntimeService` 仅接受 status/diagnostics 函数、scheduler、clock、dispatcher、coordinator、command，保留 guard→CALL_LOG 权限→schedule 次序；读取不发布。
- `widget/WidgetBindingsService` 仅接受 WidgetBindingStore、installed getter、dispatcher/协调/command。仅最终 binding 有差异时持久化一次，再发布一次；相同保存/缺失删除均 noop，失败保留旧偏好且零发布。列表及 installed ids 拷贝冻结；单项写不查询 installed 或 appearance/full summary。
- AndroidPreferences 的绑定保存/删除退出业务发布；删除无生产意义的 coordinator 注入。PreferenceStore.listBindings 与 RuntimeProbe.getInstalledWidgets 的 unavailable/UNKNOWN 默认实现删除，fixture 明确提供必要能力。
- `ringtone/RingtoneService` 承担角色/权限、phone/audibility、command 与真实差异发布。AudioController 只声明读、raw selection persistence、prepare token、restore、playback dispatch；删除 command/default overload 与 `audio.guarded_commands_unavailable`。没有 capture 前后比较、core fallback 或第二套发布。
- AndroidAudioController 的复制上限、解码、fsync、metadata、文件操作及 generation 留平台。prepare 返回不暴露 File 的 `PreparedRingtoneImport`；旧音频 rollback 备份同样在 fence 外准备。最后 guard 只做 generation/取消复核、原子 rename+metadata/必要 rollback。旧便捷 generation publish overload 删除，测试同步使用真实窄入口。
- 新音频即便 public DTO 相同也实际发布；重复 disable/restore 按真实配置差异不发布。disable 的 stop 位于提交后且 best-effort；import/restore 的 generation 保护不变。真实 Android adapter 样本验证同 label 不同内容各单发布、metadata 失败 file/settings 恢复且零发布、旧准备中途被新 import/restore 抢先，以及已持有 initial 旧文件备份的 token 都在 metadata 前拒绝，不能 rollback 覆盖较新选择。原原子 rename/取消/边界/文件回滚测试继续运行。
- 删除 Runtime/Ringtone/Widget 的旧 with(context) 工厂与 SettingsValidation；BackendContext 最后无调用的 io/write/fail/requireField/number/capability/notifyChanged helper 同步删除。SettingsApi/UiEditsApi 与真实总装配保留至 T10c，没有 Context2/万能依赖包。

## 实际 publisher / source / command 闭包

| 变更 | 一次提交的 scope keys | 累计 impact / source |
|---|---|---|
| AI 字段、secret、copy | 原 ai:slot:protocol/url/model/key/thinkingMode、ai:budget/compatibility 的实际差异 + aiConfig | AI_SETTINGS；D14=aiConfig |
| 录音配置 / folder | 原 recordingConfig:recordingEnabled/automaticAnalysis/captureMode/systemFolder 实际差异 + recordingConfig | RECORDING_SETTINGS；D13=recordingConfig，D17=systemFolder |
| Combined | 上两行实际 keys 并集，一次 marker；仅实际资源各+1 | 两个实际域并集，一次累计 snapshot |
| binding i | widgets、widget:i、preferences | WIDGET_BINDINGS 的 i.toString 实体；D18=widgets |
| 铃声选择/音频内容 | ringtone | RINGTONE；D12=ringtone/runtime/runtime:ringtonePlayback |
| 真实铃声播放准备/播放/自然结束 | runtime:ringtonePlayback | RUNTIME；不推进 recording/core/ringtone 资源版本；D12 与 D19 加入该实际 key |

PlaybackStateTracker 从真实 mark/clear 的最终状态差异发布；重复同状态为零。RingPlaybackService 的既有生命周期调用因此产生唯一真实 runtime 变更，preview/stop API 不再在 intent 后制造另一通知。D19 与 D12 同步使用此新精确 key，D19 仍保留 runtime/recordingPlayback；原 runtime:capabilities 与工具 46/67/68/69/70、recording playback 的 59、各设置字段/实体命令 scope 未放宽。其它 T09 D01–D21 依赖集合保持。

D13 folder、D17 provider_walk、D19 processing/jobs、installed/access、系统时间仍是原先如实披露的 live/non-atomic 观察；没有将物理系统状态宣称为同库事务。

## 真实 RED 与回归

| 行为 | RED 日志 / 真实失败 |
|---|---|
| Combined 仅一个 global marker | t10a-config-red.log；原多次发布不等于 1 |
| afterSave 竞态给旧值贴新 revision | 同上；真实双线程 latch 版本不小于第二次提交 |
| app preference 自发发布 | t10a-widget-red.log；持久 adapter 推进业务 marker |
| 重复关闭铃声的 fallback 修订 | t10a-ringtone-red.log；unchanged setting 不等于 marker 0 |
| 自然播放实际 mark/clear 未发布 | t10a-playback-red.log；实际 Graph marker 停 0 |
| caller Job 在 persist 前取消仍写入 | t10a-caller-cancel-red.log；真实 latch 后 writes 仍为 1 |

以上均在修复前运行并观察预期断言失败；不是编译失败充当 RED。最终完整门包含所有对应修复及扩充回归。独立 backend AiSettingsMergeTest/SmallServiceCapabilitiesTest 只构造声明的窄 fake，不构造 BackendContext，覆盖 secret 同 Boolean 替换、字段/copy scope、命令 own-commit/外国提交/停止/前台校验、Combined 原子失败/noop、权限错误顺序、copy 安全字段、连接 IO 锁外、两个 version latch、取消边界、widget 实体与失败、小服务权限/顺序、音频同 metadata 仍变化及准备后 command 复核。

旧 slow DataChange 测试曾因 widget 全域兜底错误要求后一次 score/widget 变化含 CALLERS/REPORTS。这是旧兜底 characterization，非冻结外部契约。现增量断言为实际 SCORE_SETTINGS/WIDGET_BINDINGS，同时显式验证累计 snapshot 仍保留先前 CALLERS 修订，未删掉跳帧保留 A 变化的安全证据。领域委托测试从已删除 SettingsValidation 改查最后真实 WorkScheduleService；冻结工具 golden/hashpin、12 API 源和算法未改。

## 1千 / 1万真实访问范围

SimpleSettingsAccessContractTest 对真实种入 1,000/10,000 无关通话的 SQLite 图，新增 widget 读/写/noop/缺失删除/删除，以及真实 AndroidRecordingStore+backend 设置读/录音写/AI写/noop。每项 fullStateLoads=0、completeCaptures 增量=0、核心 calls SQL=0；相关真实写一次 marker，noop 零 marker，外部审计读证明所有无关档案/通话事实仍相同。计数来自真实 SqlAccessProbe/Graph 而非耗时或任意假集合。

产物位于 `app/build/qa/layer-boundaries/{settings-access-1000,settings-access-10000,sql-settings-1000,sql-settings-10000}.json`，仅标签、表名和计数，无号码、SQL 参数或秘密。所有新增配置/widget 窗口均不发出核心 SQL。

## 最终门与冻结

环境：JAVA_HOME=`D:/Apps/zulu17.54.21-ca-jdk17.0.13-win_x64`，ANDROID_HOME=`D:/Apps/AndroidSDK`。

主完整门命令（不是多个命令拼接日志）：

```powershell
.\gradlew.bat :contracts:test :backend:test --rerun :ui:testDebugUnitTest --rerun :app:testDebugUnitTest --rerun :app:assembleDebug :ui:lintDebug :app:lintDebug :app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin --console=plain
```

exit=0；BUILD SUCCESSFUL in 1m23s；147 actionable tasks，5 executed / 142 UP-TO-DATE。三个 test 任务由任务级 --rerun 全新执行，XML 与冻结源码一致；contracts:test 无测试源码，诚实记 NO-SOURCE。其余编译/构建/lint 门为本次图中的成功 UP-TO-DATE（较早完整门已实际构建），不冒称全部 task 本轮重编。此前不带 --rerun 的完整门不作为本表新 XML 凭证，没有合并掩盖独立运行。

| 模块 | suites | tests | failures / errors / skipped |
|---|---:|---:|---|
| backend | 62 | 311 | 0 / 0 / 0 |
| ui | 70 | 298 | 0 / 0 / 0 |
| app | 60 | 287 | 0 / 0 / 0 |

UI/app lint Fatal/Error=0/0，Warning 分别 31/6；不将现有 warning 称为零告警。两个架构脚本通过（108 JVM sources），17 个 architecture 负例与 4 个 backend boundary 负例通过，未松 allowlist/pin。git diff --check 通过；新增未跟踪源码一并扫描，无尾空白。

- 主完整门日志：`build/qa/layer-boundaries/t10a-full-gate.log`，SHA256 `f7bd52a009818cc76a2bacc66154b45b303e2dad87c07da3c7fc993fbf20f629`。
- 架构及负例日志：`build/qa/layer-boundaries/t10a-architecture.log`，SHA256 `95c6f423bf635ffc29bae69ae1c65ce91f7ff13dcda51dc5fcf37d2f66f49671`。
- 原始计数/时间戳/命令与 source fingerprint：`build/qa/layer-boundaries/t10a-verification.json`。最终冻结的 48 个变动源码/测试路径（含删除及未跟踪文件）聚合 SHA256 `90fb7efdeae0b0b01e108fafda52a8e956adeab70abdffb0dfbeb8eee703907d`；按排序的相对路径+LF+原始文件字节（删除用 DELETED）+LF 计算。

### 规格审查唯一 P2 修复与独立补门

规格发现平台 AndroidRuntimeProbe 构造器仍有 installedWidgets UNKNOWN 默认。核实只有 BackendGraph 一个真实构造点，且显式注入 getter；删除这一行无消费者默认，不添加任何替代能力/兼容入口。无需为机械删除添加镜像行为测试；构造器及实际 Graph 路径由重新编译和既有 actualGraph/app 测试验证。

修复后独立执行（原始命令/日志与上方主门分开，未拼接）：

```powershell
.\gradlew.bat :contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug :app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin --console=plain
```

exit=0；BUILD SUCCESSFUL in 54s；147 tasks，17 executed / 130 UP-TO-DATE。app Debug/backendQa/Graph 重新编译、assemble、app 全 287 项测试与 lint 由这次生产签名变化触发执行。backend 311 项与 UI 298 项是上方主门 --rerun 的 XML，此补门诚实记 UP-TO-DATE；app 新 XML 为 60 suites / 287 tests，failures/errors/skipped=0/0/0。详细各模块时间戳见 verification.json 的 main 与 reviewFix 两组，不把主门时间戳冒作补门执行。两模块 lint Fatal/Error 仍为 0/0。

- 补门日志 `build/qa/layer-boundaries/t10a-review-fix-gate.log` SHA256 `e3a4db33445134fc6cc5f6c4fb290e3a7d4060bbc6b8b6612b24c286f73bdabf`。
- 补门后架构/108 JVM 与 17/4 负例再次通过，独立日志 `build/qa/layer-boundaries/t10a-review-fix-architecture.log` SHA256 `bb73eb33e316ac64b8105209aa53d0bfa6e466d6ced44f5dbdf3d8da33816f99`。
- 这是规格反馈唯一的源码修改；本段补门之后再次正式冻结，无其它顺手修改。最终 source fingerprint 已更新为上列 48 路径及最终 SHA。

RED 日志 SHA256：

- `t10a-config-red.log`：`2c1a93c1dec34547424ab098915d77a2aeb8e6e59af8c6902c4e096c1c0cef44`。
- `t10a-widget-red.log`：`8ae9d916eca40b55a19012740c750daff33bc9b42685a764ecd801a54f29eddc`。
- `t10a-ringtone-red.log`：`6ecc57b10021efdd5a496a0be10e528728bde53d3e5509c8456e480b518411dc`。
- `t10a-playback-red.log`：`b5073afca5bf11af6c65d3e4e7c349dedb4529bbaf1810a1b85a294a3b6ec5d1`。
- `t10a-caller-cancel-red.log`：`b4eb1c1e6c82e3412ef42ae2c715d7f8a3472ef8aa5c8b53cfd48a09a833cd55`。

## 剩余真实消费者及限制

- T10b：AndroidRecordingRuntime 的录音业务/单目标查找/普通 all 扫描、NumberDeletion/IdentityAdoption、RecordingWorkflow 的 File/AiClient、RecordingStore 归档登记/link/CAS/delete 的旧发布，仍各有真实生产消费者；本片没有包装成另一套配置入口。
- T10c：BackendContext 仅余真实总装配、bound/decision mirror 生命周期，以及 SettingsApi/UiEditsApi 组装；Graph 业务/能力 fallback 的最终显式组合按既定计划清除。
- T11：DataChange/observe 适配、publishScopes/changed 的剩余真实 archive/diagnostics/runtime 生产路径及所有消费者依赖收口、具名 runtime/job publisher。新铃声 tracker producer 已准确发布，本片未扩大 UI 架构修改，也不把仍在工作的旧 feed 冒称已删。
- T13：BackendStore/BackendState/StorageSession 的初始化、debug/fixture 真实消费者及机械目录整理；没有为本片测试保留已迁配置管理协议或无调用生产 overload。
- 未验证设备 A9、Release 发包和运行期物理 provider/job 原子性；本片无设备操作。后续 review 发现须修复时需重新解冻、验证并取新冻结证据，不能在本次门之后继续顺手改源码。
