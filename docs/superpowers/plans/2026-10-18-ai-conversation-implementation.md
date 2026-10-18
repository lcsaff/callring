# AI Conversation Implementation Plan

> **For agentic workers:** 计划获批后使用executing-plans逐项内联执行，禁止子Agent。

**Goal:** 交付 R1–R6/A1–A6 全部功能、72 项真实业务操作及构建/测试证据，保留旧配置、凭据和录音数据。

**Architecture:** 沿用获批全量21域快照＋固定工具＋短发布栅栏＋前台票据。UI 与 AI 共用补丁/验证/保存；会话仅驻进程，复用 analysis 连接，协议为非流式 OPENAI/ANTHROPIC。

**Tech Stack:** Kotlin/JVM、coroutines、Android SQLite/偏好/Keystore、Compose、ActivityResult、JUnit/Robolectric。

2026-10-18；ai-implementation/implementation_plan/epoch5；本轮只新增计划。

<a id="dependencies"></a>
## 1. 输入、路径及执行约束

DESIGN=`docs/superpowers/specs/2026-10-18-ai-conversation-implementation-design.md`，commit `b5547bb17761528a5c2b554a68eac1267cde7d6f`，blob `441bdcc39a2a15b905163424f672ab43c3411ed9`。批准 `approval-52a461608a55ca1e432184d6e1db0c599a0a9f1942810233f2b4dbef4b71bab3`，contentDigest `5447b3d1f86a6c73a217a77c499f12265ae3a0b9b183a25484d3795f09c7b90c`。

ARCH=`docs/superpowers/specs/2026-10-18-ai-conversation-architecture.md`，固定对象与批准见 DESIGN#dependencies。所有Q/D/O/V ID 沿用原对象锚点。报告 dependencyRefs 含 R1–R6 六项 requirement（digest 取 ARCH#requirements）、上述实现设计 approval 及架构 approval；文档 artifact 依赖用规范化 `{path,blob}` digest，不能只报 coverage。

路径前缀为确定的完整目录：B=`backend/src/main/kotlin/com/greatwall/callring`，A=`app/src/main/kotlin/com/greatwall/callring`，U=`ui/src/main/kotlin/com/greatwall/callring`；BT=`backend/src/test/kotlin/com/greatwall/callring`，AT=`app/src/test/kotlin/com/greatwall/callring`，UT=`ui/src/test/kotlin/com/greatwall/callring`。下列 B/foo 即该前缀加 `/foo`，测试同理。新文件按列明责任创建，不把所有代码塞入单文件。

允许 backend/src、app/src、ui/src、README.md、DESIGN、本计划、`docs/plans/AI沟通验收.md`、`docs/contracts/CallringUiApi.kt`。不改Gradle/工具/插件/身份。根API与合同同步，backend纯JVM/无中文展示，UI只依赖application.api。沿用当前命名分支。

每项按测试红→实现→测试绿→核差异提交，断言真实存储/API/通知；片段只示关键断言，完整类型/行为遵DESIGN。G为PowerShell命令：

```powershell
function G { & .\gradlew.bat @args }
```

<a id="structure"></a>
## 2. 文件责任图与依赖

|任务|产物责任|依赖|
|---|---|---|
|P01|API DTO/纯JVM类型、合同及测试夹具|无|
|P02|版本协调器与主库共用提交|P01|
|P03|录音/偏好/铃声/诊断全部生产写发布接入|P02|
|P04|原密文两页补丁/凭据/预算兼容|P03|
|P05|主库全量源/预检/审计与统计投影|P02|
|P06|录音/偏好/诊断/运行态/目录全量源|P03、P04|
|P07|21域完整快照、脱敏与容量门|P05、P06|
|P08|主库补丁命令/采纳/全部读写复用|P02、P04|
|P09|72项注册/解码/真实路由与覆盖矩阵|P07、P08|
|P10|前台票据与本地资源生命周期|P01、P02|
|P11|两协议完整编码/解析/取消HTTP|P07、P09|
|P12|会话/串行执行/账本/停止/目标变更|P09、P10、P11|
|P13|Android宿主/系统结果/本地表单共用|P04、P10|
|P14|四导航/两设置/对话UI/刷新与装配|P12、P13|
|P15|跨层故障与全操作集成核验|P14|
|P16|完整测试/构建/lint/设备边界/文档交付|P15|

按表顺序单会话执行，P16齐全证据后才报完成。

<a id="p01"></a>
### P01：公开契约与可控测试基座（A1–A6）

新建 B/application/api/{ConversationApi,AiSettingsApi,AiSnapshotApi}.kt、B/application/ports/ConversationPorts.kt；修改 B/application/api/{CallringUiApi,RecordingApi}.kt 与合同。测试新建 BT/conversation/{ConversationApiContractTest,ConversationFixtures}.kt；复用现有 CallringBackendTest 夹具但不改业务含义。

- [ ] 定义 PatchField Keep/Set、LocalSecretChange、两页 View/Base/Patch、RevisionVector、CommandContext、manifest、会话/回执/票据 DTO；测试失败场景为 unavailable 不得返回 Complete/Success。
- [ ] 运行 `G :backend:test --tests '*ConversationApiContractTest'`，记录接口/行为缺失的失败，不把依赖下载错误视作预期红灯。
- [ ] 给 CallringUiApi 加 conversation/aiSettings/snapshots、conversation.foreground；补 CONFLICT/MICROPHONE/三个新增 DataDomain，并更新所有编译期 when。默认不支持仅返回结构化 unavailable，纯DTO不含 Android/JSON 类型。
- [ ] 夹具提供可控 Clock、真实内存业务 Store、提交计数/调用日志、FakeConversationClient 队列、前台 lease、无网络 scheduler；核心断言 `assertTrue(unavailable is ApiResult.Failure)`，生成的全文须可插尾哨兵。
- [ ] 同测试通过，运行 `python tools/check_backend_boundaries.py`，同步根合同后提交 `feat: AI contracts`。失败保留改动，不用空成功占位。

<a id="p02"></a>
### P02：短提交与主库版本（A3/A4）

新建 B/application/BusinessRevisionCoordinator.kt；修改 B/application/{BackendContext,CallringBackend}.kt；测试 BT/conversation/BusinessRevisionCoordinatorTest.kt。

- [ ] 失败测试：旧 expectedVersions 拒绝且存储写计数0；事务抛错 revision 不增；成功才同时出收据和全域事件；多线程来电/import/command 形成递增版本。
- [ ] 运行 `G :backend:test --tests '*BusinessRevisionCoordinatorTest'` 见失败。
- [ ] 实现 incarnation/短 fence/版本向量；核心路径 `checkContext(); val saved = transaction(); advance(); recordReceipt(saved)` 处同一短临界区。显式传 CommandContext，不跨 suspend 用 ThreadLocal；将 mutate、recordIncoming、updateDecision、importSystemCalls 全接入。
- [ ] 使用 CountDownLatch 将网络准备悬停，另一线程原 recordIncoming 仍可完成；快照读取释放 store 锁后采版本。断言 `assertEquals(before, versionsAfterRollback)`、成功事件全域包含新增域。
- [ ] 定向测试及 `:backend:test` 通过后提交 `feat: publication revisions`。失败不放宽版本核验，不在 fence 内等待网络。

<a id="p03"></a>
### P03：生产适配器统一发布（A3/A4）

修改 A/bootstrap/{BackendGraph,LazyBackendStore}.kt，A/platform/{AndroidPreferences,AndroidAudioController,AndroidDiagnosticsStore}.kt，A/platform/ai/{AndroidRecordingStore,AndroidRecordingRuntime}.kt；测试 AT/platform/ai/RecordingPublicationTest.kt、AT/platform/PublicationAdaptersTest.kt。

- [ ] 失败测试覆盖 register/link/CAS/delete/配置、外观/绑定、铃声发布和诊断实际写入；后台 CAS 必须发失效，失败/无变化不增版本。
- [ ] 运行 `G :app:testDebugUnitTest --tests '*Publication*Test'`，确认行为失败。
- [ ] graph 注入同一 coordinator；改写写方法锁序为 fence→service/store→DB/偏好。删除 monitor 内反向发布回调；prepare/复制/媒体/调度锁外，最终检查目标 generation 后 publish。
- [ ] 竞争测试用 barriers 断言导入准备期间 restore/delete 不被旧结果覆盖，偏好 commit 失败恢复旧内存，来电不等 AI；断言 `assertEquals(0, stalePublishCount)`，先提交后调度失败仍保 COMMITTED。
- [ ] 原 AndroidRecordingStore/AudioController 相关回归加新测试通过后提交 `feat: adapter publication`；锁序失败保成果并先定位反入点，不能禁后台写绕过。

<a id="p04"></a>
### P04：录音与AI配置补丁（A1/A4/A5）

新建 A/platform/ai/AiSettingsService.kt；修改 AndroidRecordingStore（含RecordingJson）/AndroidRecordingRuntime及 B/application/api/AiSettingsApi.kt；测试 AT/platform/ai/{AiSettingsServiceTest,AndroidRecordingStoreTest}.kt。

- [ ] 旧 fixture 含两 key，分别打开两页，交错保存不回退另一页；同 dirty 字段外改需冲突。分别测试 Keep/Replace/Clear、自动分析双连接/转写OPENAI、预算边界、复制及解密/commit失败。
- [ ] 运行 `G :app:testDebugUnitTest --tests '*AiSettingsServiceTest' --tests '*AndroidRecordingStoreTest'` 见红。
- [ ] 原 namespace/config/Keystore alias 内，锁内读最新→逐 dirty 比基准→合并→完整 validate→加密commit→双字段组版本。预算为可选 versioned 子对象，缺 config 才默认；旧完整 API 适配同执行器。
- [ ] 断言解密后的实际字段：`assertEquals(oldTranscriptionKey, stored.transcription.apiKey)`，脏冲突/失败 ciphertext 仍旧；slot copy 在本地预览、目标转写强制OPENAI，测试工具只用已保存slot。
- [ ] 两测试族通过提交 `feat: scoped AI settings`。失败保密文，不用默认覆盖、不自动清其它字段。

<a id="p05"></a>
### P05：主库全集、审计、权威统计（A3/A5）

修改 B/application/ports/BackendPorts.kt、A/data/SqliteBackendStore.kt、A/bootstrap/LazyBackendStore.kt；新建 B/application/conversation/CoreSnapshotProjection.kt；复用 B/application/{CallerProjection,ReportCalculator}.kt；测试 AT/data/CoreSnapshotSourceTest.kt、BT/conversation/CoreSnapshotProjectionTest.kt。

- [ ] 数据准备>200号码/通话，非规则号、hidden/合并/旧值、全历史schedule/跨年calendar/leaves/audit与尾哨兵；全部不得受 UI 分页/日期影响。另测空集、min/max溢出边界及统计停用区间。
- [ ] 运行 `G :app:testDebugUnitTest --tests '*CoreSnapshotSourceTest'`、`:backend:test --tests '*CoreSnapshotProjectionTest'` 见红。
- [ ] 新全量端口在同 SQL 读事务先 COUNT/文本字节预检，再 load state/完整 calendar_audit；深复制只读DTO，bad row失败。Calculator 复用 visible/count 规则，同采样 now 投影全时段/rolling/quality/nextBoundary。
- [ ] 核心断言 `assertEquals(251, snapshot.callers.size)`、`assertTrue(snapshot.audit.last().raw.endsWith(tail))`；source 超限必须在 state load 前拒绝，记录 load 次数0，既有来电read不附加全集流程。
- [ ] 两模块定向测试通过提交 `feat: complete core snapshot`；预检/统计不通过不退回页面拼装或截断。

<a id="p06"></a>
### P06：录音与平台全集（A3/A6）

修改 A/platform/ai/{AndroidRecordingStore,AndroidRecordingRuntime,SystemRecordingSource}.kt，A/platform/{AndroidPreferences,AndroidDiagnosticsStore,AndroidRuntimeProbe}.kt、BackendPorts.kt；测试 AT/platform/ai/{RecordingSnapshotSourceTest,SystemRecordingSourceTest}.kt、AT/platform/PlatformSnapshotSourceTest.kt。

- [ ] 失败测试：主库零通话时负ID events/links/archives/删除标记仍全；诊断第19条及legacy尾、所有绑定含残留、运行态麦克风UNKNOWN/deny不假grant。
- [ ] 运行 `G :app:testDebugUnitTest --tests '*SnapshotSourceTest' --tests '*SystemRecordingSourceTest'` 见红。
- [ ] 录音独立库同事务预检后枚举严格payload，保完整转写/分析/代次；偏好listBindings＋宿主installed；新增captureStoredText，取消take(18)/mapNotNull吞坏行；配置读坏阻断。
- [ ] 目录query测试500/501节点、深度4/5、null cursor/循环/撤权/缺元数据、稳定两遍及持续三次变化；核心断言 `assertTrue(overLimit is ApiResult.Failure)`，恰好边界无后续成功，含非音频节点计数；URI只进本地句柄资源表。
- [ ] 定向测试及既有录音源回归通过提交 `feat: complete platform snapshot`；已配置无权不能回空，实际不存在的源不伪造。

<a id="p07"></a>
### P07：完整快照、脱敏与预算（A3/A5）

新建 B/application/conversation/{AiSnapshotService,SecretRedactor,ContextBudget,SafeJsonValue}.kt；A/platform/ai/ConversationRequestEncoder.kt；测试 BT/conversation/{AiSnapshotServiceTest,SecretRedactorTest,ContextBudgetTest}.kt、AT/platform/ai/ConversationRequestEncoderTest.kt。

- [ ] 失败测试逐域manifest，EMPTY/未配/不支持/FAILED不能混同；v0/v1变化只允许整份重试3次；两slot哨兵在正文/工具/诊断/编码变体/短key，完整正文尾保留。
- [ ] 运行 `G :backend:test --tests '*AiSnapshotServiceTest' --tests '*SecretRedactorTest' --tests '*ContextBudgetTest'` 见红。
- [ ] 实现21域白名单/Complete构造限制；自由文本值脱敏后重编码，不改schema/枚举/字段名；safe请求编码端口算UTF8完整体，checked Long加1024协议余量、输出预留、max(1024,ceil10%输入)。
- [ ] 测20000/20001行、源累计≥8MiB、消息32KiB、请求8MiB/响应1MiB、JSON转义膨胀、历史/schema/tool/预留及溢出；`assertEquals(0, httpCalls)`于超限/失败域。换key清历史匹配文本/待发体，不记录原错误正文。
- [ ] backend与encoder定向测试通过提交 `feat: full context budgets`；失败不删历史/工具schema或任意字段换取通过。

<a id="p08"></a>
### P08：共用业务补丁及采纳（A1/A4）

新建 B/application/SharedBusinessCommands.kt；修改 B/application/{CallersService,SettingsService,WidgetBindingsService,RingtoneService}.kt、api契约；修改 AndroidRecordingRuntime 的真实媒体命令context入口；测试 BT/conversation/SharedBusinessCommandsTest.kt、AT/platform/ai/RecordingCommandContextTest.kt。

- [ ] 失败测试覆盖资料局部保存保职位/白名单、quiet记忆模式、职位IN_USE、schedule生效/leave审计/score/appearance校验；采纳只取非空自述字段，archive generation变拒绝；所有写失败不通知。
- [ ] 运行 `G :backend:test --tests '*SharedBusinessCommandsTest'` 与 `:app:testDebugUnitTest --tests '*RecordingCommandContextTest'` 见红。
- [ ] 从原服务抽取同事务类型命令，UI原方法与新patch只适配输入，AI传本地context；不得工具get后整份save。新增adoptSelfReportedIdentity原子核录音代次/主库读依赖再保留其余字段。
- [ ] 验证原验证错误/requiredCapabilities原样返回及UI↔AI交错写；断言 `assertEquals(positionBefore, after.positionId)`、`assertEquals(0, writesAfterConflict)`；媒体publish前重核取消和目标。
- [ ] 原 CallringBackendTest 及新测试通过提交 `refactor: shared business commands`；不删原验证来适配模型，也不新增业务不支持的删除。

<a id="p09"></a>
### P09：全72操作工具路由（A3/A4/A6）

新建 B/application/conversation/{ToolRegistry,ToolArguments,ToolExecutor,ToolReceipts}.kt；测试 BT/conversation/{ToolRegistryTest,ToolOperationCoverageTest}.kt；新建 `docs/plans/AI沟通验收.md` 的操作矩阵。

- [ ] 参数化测试为O01–O72每项登记有效输入/原API调用/真实结果/无效输入；读工具也须调用API，不能仅查快照答复。固定schema拒额外字段/伪URI，Long/ISO日期/ZoneId严格解析。
- [ ] 运行 `G :backend:test --tests '*ToolRegistryTest' --tests '*ToolOperationCoverageTest'` 见红。
- [ ] 固定描述表同源产生schema/decoder/handler索引，工具名遵ARCH#write-matrix。O01–O49接原API/共用补丁；O50–O63接安全录音/句柄/采纳；O64–O72接AI/前台；不留unimplemented或“默认成功”分支。
- [ ] 覆盖断言 `assertEquals((1..72).map { "O%02d".format(it) }.toSet(), registry.ids)`，每项正反例查实际API调用和通知；COMMITTED须有commitEvidence，retry/sync=QUEUED，系统launch不得冒充完成；CSV/音频/归档遵原大小/来源/校验。
- [ ] 矩阵每行填schema/handler/API/类别/正反测试与结果证据，测试通过提交 `feat: all 72 business tools`。缺任何一项不将本任务报完成。

<a id="p10"></a>
### P10：一次性前台票据（A6）

新建 B/application/conversation/{ForegroundActionService,ForegroundResourceRegistry}.kt；测试 BT/conversation/{ForegroundActionServiceTest,ForegroundResourceRegistryTest}.kt；API类型沿P01。

- [ ] 失败测试四ID/generation/版本/目标/摘要/host绑定及10分钟过期；重复beginLaunch与重复/迟到结果，stop/newSession后结果、无宿主与目标换代不可消费。
- [ ] 运行 `G :backend:test --tests '*Foreground*Test'` 见红。
- [ ] 实现状态机、RESUMED lease、原子领取/一次消费；LAUNCHED旋转只重接不重发。HostResult不可从工具参数构造，随机句柄绑定本地URI/来源/目标/期限，不把URI发给模型。
- [ ] 可控Clock断言 `assertEquals(1, launchCount)`、`assertEquals(0, commitsFromLateResult)`，null取消/deny/unknown/unavailable独立；句柄过期/伪造/目标变更拒绝，清理scratch，已提交业务不回滚。
- [ ] 全测试通过提交 `feat: foreground tickets`；失败不得拿当前票据接收旧回调或自动再次弹窗。

<a id="p11"></a>
### P11：两协议完整请求/回复（A2/A5）

新建 A/platform/ai/{CompatibleConversationClient,OpenAiConversationCodec,AnthropicConversationCodec}.kt；修改 StrictJson 仅在确需共用校验时；测试 AT/platform/ai/{CompatibleConversationClientTest,ConversationProtocolTest}.kt。

- [ ] 假HttpURLConnection捕获完整请求，分别返回Final/ToolBatch及拒绝/截断/非法IDs/旧function_call/未知停止原因/超大body；不调用已有analyze分块逻辑或真实API。
- [ ] 运行 `G :app:testDebugUnitTest --tests '*ConversationProtocolTest' --tests '*CompatibleConversationClientTest'` 见红。
- [ ] OPENAI单choice/assistant.tool_calls/role=tool配对，Anthropic独立system/text+tool_use/下一user先全tool_result；完整有界StrictJson解析后只返Final/ToolBatch，客户端不执行工具。
- [ ] 测HTTPS/禁重定向/userinfo-query-fragment、15/90秒、disconnect取消、1MiB有界读、401/403/429/5xx/400与context拒绝/413区分。断言 `assertEquals(0, executedToolsForMalformedBatch)`；错误值/日志无原正文/两slot哨兵。
- [ ] 原 CompatibleAiClientTest 回归及新测试通过提交 `feat: conversation protocols`；不自动重试/换模型/换endpoint。

<a id="p12"></a>
### P12：应用会话与执行账本（A2/A4/A5/A6）

新建 B/application/conversation/{ConversationService,ConversationLedger}.kt；测试 BT/conversation/{ConversationServiceTest,ConversationLifecycleTest}.kt。

- [ ] 假客户端脚本发多工具批次/重复ID/异参/失败/UNKNOWN，测试busy拒绝、8工具/16请求/32工具限制、整批非法零执行、停止前后提交及旧generation。
- [ ] 运行 `G :backend:test --tests '*ConversationServiceTest' --tests '*ConversationLifecycleTest'` 见红。
- [ ] send接受后进入StateFlow状态机；每请求全量/脱敏/预算，批先整验后逐项前台+版本核验/IN_FLIGHT/执行/回执入账/重捕获预算；失败余项skipped，完整配对后才可再请求；UNKNOWN不重放。
- [ ] `assertEquals(1, actualWritesForDuplicateSameArgs)`，工具后超限保旧回执且后续写0；stop断HTTP/失效票据，已提交不丢。analysis目标/key变保存后终止链，下一发送重新告知；后台新增写必须等RESUMED再核版本。
- [ ] 定向及backend全测试通过提交 `feat: conversation execution`；对账失败要求新会话，不能删历史补协议或根据模型“成功”造回执。

<a id="p13"></a>
### P13：系统宿主、选择与本地凭据（A1/A6）

新建 U/ui/app/ForegroundActionHost.kt、U/ui/feature/ai/CredentialDialog.kt；修改 U/ui/app/{CallringActivity,DocumentPickers}.kt、U/ui/format/UiLabels.kt；平台 probe/文件授权入口按P06；测试 UT/ui/app/ForegroundActionHostTest.kt、UT/ui/feature/ai/CredentialDialogTest.kt。

- [ ] Robolectric/Compose 测真实ActivityResult注册key与requestId关联、onResume/onStop lease、旋转不重弹、旧回调/死亡无票据只清资源、null取消、无系统入口。
- [ ] 运行 `G :ui:testDebugUnitTest --tests '*ForegroundActionHostTest' --tests '*CredentialDialogTest'` 见红。
- [ ] 固定权限/角色/app设置/音频/日历/tree/CSV/pin/overlay/preview动作，原UI入口复用同执行器；系统返回重probe核真实结果。内容URI/持久READ/来源和元数据由本地核验，目标数据由业务API读取。
- [ ] 凭据Keep/Replace/Clear/slot copy/测试告知均本地表单，取消不保存，回模型仅配置状态；断言输入不进SavedState/日志。pin=true仅LAUNCHED，实际widget ID才安装，overlay只证启动或实显；MICROPHONE全链拒绝不改采集模式。
- [ ] 新测试及原DocumentPicker/Activity相关测试通过提交 `feat: system action host`；无前台不能静默执行，拒绝不改grant。

<a id="p14"></a>
### P14：四导航、两设置、沟通界面与graph（A1–A6）

新建 U/ui/feature/conversation/{ConversationViewModel,ConversationScreen}.kt、U/ui/feature/ai/{AiSettingsViewModel,AiSettingsScreen}.kt；修改 U/ui/app/{UiDestination,CallringApp,SettingsRoute}.kt、U/ui/feature/settings/SettingsOverviewScreen.kt、U/ui/feature/recording/{RecordingViewModels,RecordingScreens}.kt、A/bootstrap/BackendGraph.kt。测试 UT/ui/feature/conversation/ConversationScreenTest.kt、UT/ui/feature/ai/AiSettingsScreenTest.kt、UT/ui/app/UiDestinationTest.kt 及既有HomeStructure/Recording测试。

- [ ] 先测宽窄四入口、设置分别进两页/旧RECORDING深链；脏草稿保留/冲突、会话所有状态/停止/新会话/容量/真实回执、旋转切页和进程新会话。
- [ ] 运行 `G :ui:testDebugUnitTest --tests '*ConversationScreenTest' --tests '*AiSettingsScreenTest' --tests '*UiDestinationTest'` 见红。
- [ ] tabs同源增加Conversation图标/标签；AI页连接/凭据/复制/预算，录音页仅录音字段；VM保存页patch、订阅全域失效，archive采纳改共用命令；不将会话/全集/凭据放SavedState。
- [ ] graph懒装配同一coordinator与所有源/客户端/前台，close取消请求/失效票据；来电构造不启动AI。UI显示host/model/全文无音频告知、操作真实状态，busy禁发，非完成不显示成功。断言四个tab标签及失败真实回执存在。
- [ ] ui全测试和 `G :app:assembleDebug` 通过提交 `feat: conversation and AI settings`；不以能聊天替代工具/宿主能力。

<a id="p15"></a>
### P15：跨层故障、数据完整性与72操作复验（A1–A6）

新建 AT/platform/ai/ConversationIntegrationTest.kt、UT/ui/feature/conversation/ConversationRefreshTest.kt；扩展P02–P14相应测试与 `docs/plans/AI沟通验收.md`。

- [ ] 录入21域真实样本与全文尾/两key哨兵；将72项路由正反例接真实内存/SQL/录音/平台适配器，不能只断言mock收到同名调用。对系统媒体使用可控真文件/伪resolver，不付费。
- [ ] 运行 `G :app:testDebugUnitTest --tests '*ConversationIntegrationTest'` 与 `:ui:testDebugUnitTest --tests '*ConversationRefreshTest'`，缺的跨层行为先红再修。
- [ ] 故障注入read/解密/commit/CAS/调度/网络截断/容量/权限；barriers触发UI↔AI、后台更新、导入中restore-delete、stop与提交交错；检查真实实体、版本、通知、已提交/未执行清单。
- [ ] `assertFalse(outboundData.contains(secretSentinel))`（认证头单独允许），全文尾保留，慢订阅全域刷新、草稿不丢；unknown不自动重放、无前台写0、迟到/重复结果写0。将每项实际测试名与报告路径写入矩阵。
- [ ] 新测试与涉及回归通过提交 `test: verify complete AI business integration`；任何红项保改动修复，不删数据/放宽契约。契约冲突按QID给主负责人最小复现与建议。

<a id="p16"></a>
### P16：最终验证、文档与提交（A1–A6）

修改 README.md、`docs/plans/AI沟通验收.md`；产品只允许为真实验证失败作范围内修复；不改获批设计正文造成依赖漂移。

- [ ] 运行并保存真实退出码/报告：`python tools/check_backend_boundaries.py`；`G :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`。预期全部exit0且XML无failure/error；空测/任务未运行须说明，不称通过。
- [ ] 检查 `backend/build/test-results/test`、`ui/build/test-results/testDebugUnitTest`、`app/build/test-results/testDebugUnitTest`、`app/build/reports/lint-results-debug.html`；A1–A6逐项定位V01–V09与O01–O72实际证据，记录测试总数、失败数、时间及目标commit，不能只有“BUILD SUCCESSFUL”。
- [ ] 有已连接设备则仅build/install backendQa，先 `adb devices -l` 核实；设备测试代码在app/src/debug、app/src/backendQa、ui/src/androidTest现有隔离结构。具体任务先查询 `G :app:tasks --all`/`:ui:tasks --all`，只选实际存在且目标隔离包的任务；无法保证隔离就不安装并列未测。禁止覆盖生产包/清生产数据。
- [ ] 设备逐项查权限grant/deny、角色、SAF音频/日历/CSV/tree、旋转/后台/重复结果、pin/overlay；真实付费模型、SIM/ROM后台及录音双方效果不默认测试。README补四导航/两设置、全文外发/无音频、内存会话、容量限制与兼容模型边界，未实测项独立列清。
- [ ] `git diff --check` 与范围核对后提交 `docs: record AI conversation acceptance evidence`。以body-file写消息，六项真实trailers同一连续最终块；`git interpret-trailers --parse`确认。session_report填真实目标commit/验证/未测边界，summary≤200字符，结束轮次等待主负责人核验。

<a id="verification"></a>
## 3. 验收对应与失败纪律

|验收|实施项|验收证据|
|---|---|---|
|A1|P01/P04/P08/P13/P14|V01、V04、V09；旧密文与两页交错/冲突/本地凭据|
|A2|P11/P12/P14|V05、V06、V09；四入口、各状态/真实失败/停止|
|A3|P02/P03/P05/P06/P07/P15|V02、V08；21域manifest/尾哨兵/失败域/无密钥|
|A4|P02/P03/P08/P09/P12/P15|V03、V04、V05；72项真实存储/API与通知矩阵|
|A5|P04/P07/P11/P12/P15|V06、V09；全编码预算、超限零执行、保提交|
|A6|P06/P10/P13/P14/P15|V07、V09；前台真实授权、取消、迟到/重复/旋转|

失败保成果，不catch异常报成功、不用桩替换生产；环境故障与产品失败分列。定位修复不擅自扩大依赖/格式/协议，完整套件通过后仅因新改动/失败再复测。

<a id="checkpoints"></a>
## 4. 检查点与阶段出口

每提交标题后空一行，六个trailers连续置末块：Codex-Thread、Codex-Turn、Task-Id、Checkpoint-Id、Role、Codex-Epoch，值取execution的begin_round回执；`git commit --file <body-file>`后解析核六项。组件提交前验证/核范围，引用真实SHA/blob。

报告kind=plan、stage=implementation_plan，登记P01–P16并绑定六需求/设计及架构批准/文档artifact。无需终端用户逐项确认。
