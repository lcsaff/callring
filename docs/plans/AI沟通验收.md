# AI 沟通实现与验收证据

## epoch9 审查修正（当前待独立复验）

本轮仅修复已采纳 AIR-001–005，代码目标提交 c018724f5c9aa3eea0ee9300f95a2fd5ddecb264，起点为 c3a1ef9c4d4968b186fe3f9549dae0ee8c375624。对应 revision-118e66ec11a01f37a1d80c06e21d309dc5b4da59c566e3ced0b080b948ea2ea4。没有带入审查分支提交，也没有改写历史获批设计/计划。以下为修正者验证，不声明整体验收；AIR-001/002/004 仍交独立会话复验，AIR-003/005 提供针对性回归供核对。

<a id="air-001-fix"></a>
### AIR-001：首次连接原子保存

CredentialDialog 接收页面所选 slot 的安全基准和非敏感连接草稿，与本地 Keep/Replace/Clear 在 patchCombined 一次校验、一次提交。密钥只保存在弹窗 remember；不加入 CredentialDraft、聊天或 SavedState。取消不调用保存；失败不关闭弹窗，草稿和本地输入可重试；成功只重设已保存 slot 的基准，其他未保存字段保留。

复现：新增 FirstConnectionUiTest 在修正前实际失败，捕获到 URL/model 补丁为 Keep。修正后该类的分析、转写两个 Compose 用例均通过，覆盖空页面填地址/模型、取消零请求、保存失败保输入、重试组合请求、成功后已配置及不再脏。AiSettingsServiceTest.coldStartPageDraftCreatesEitherSlotAtomicallyAndPreservesOtherPage 通过实际 AiSettingsViewModel、正式 AiSettingsService/AndroidRecordingStore 分别创建两个 slot，核对真实密钥、另一页并发写不回退、未保存预算草稿保留、旧基准凭据写拒绝。跨模块 VM 通过反射调用，以免改变模块依赖；真实存储断言不靠成功文案。既有 Keep/非法 Clear/两页冲突和 CredentialDialogTest 重建不恢复明文继续通过。

<a id="air-002-fix"></a>
### AIR-002：告知与发送使用同一已显示连接

页面空闲/停止/失败时显示最新准备发送的 protocol、URL、model，本轮已发送目标只在 busy 时单列。目标尚未知时禁发。ConversationRoute 将本次 composition 的安全连接复制为普通不可变局部值，点击携带此值调用 sendTo；没有在点击时读取可变 VM 最新值，也不直接捕获委托 State 的 getter。sendTo 在入队前及 runTurn 开始再次比对目标，旧告知不得向新服务发全集；原同轮连接变化取消仍保留。

ConversationReviewUiTest 覆盖 STOPPED/IDLE/COMPLETED/FAILED 的新标签与未知目标禁发；修正前两个界面回归均为红项。RenderedDestinationTest 暂停 Compose 帧，令 VM 已收到新配置但屏幕仍旧，派发从旧界面取得的真实按钮语义回调，验证其仍携带旧连接；服务拒绝后输入保留，新目标显示后才携带新连接。测试曾揭示直接在 lambda 读取委托 State 仍会读到新值，最终以冻结局部值修复。ConversationServiceTest.sendRejectsUndisclosedUrlModelOrProtocolBeforeAnyRequest 验证三个字段分别变化时请求数0、告知匹配后请求数1。既有请求中连接变化/停止/新会话及历史清理回归通过。

<a id="air-003-fix"></a>
### AIR-003：工具文字不作为执行事实

所有含 toolCalls 的模型文字统一显示“AI 操作建议（未经验证，以操作回执为准）”，不按文字猜测成功，也不改变供两协议使用的完整历史。执行事实仍由 READ/COMMITTED/QUEUED/LAUNCHED/CANCELLED/DENIED/FAILED/UNKNOWN/SKIPPED 收据表示。

ConversationReviewUiTest.toolBatchTextRemainsExplicitlyUnverifiedAcrossEveryOutcome 验证等待、拒绝、取消、失败、提交完成和跳过时，模型的“录音已删除”均保留未验证标识，不冒充普通最终 AI 结果。ConversationStateUiTest 全阶段/全部收据与 ConversationServiceTest 工具后容量超限保提交、余项跳过继续通过。

<a id="air-004-fix"></a>
### AIR-004：权威具体目标与版本绑定

O20/O32/O53/O57/O63 在本地读取目标后，经原 CommandContext 核验生成带 ConfirmationTarget 的一次性票据；UI 不读取模型提供的名称作为确认依据。职位显示 ID/名称/级别；请假显示 ID/区间/备注；录音显示归档 ID/号码/时间；身份采纳显示旧姓名/单位与将替换的非空自述值；测试显示 slot/协议/实际URL/model/密钥配置状态。没有权威目标的确认按钮禁用。读取与展示、确认与执行之间任何版本变化都使旧确认失效，错误ID不发确认。

ConfirmationTargetTest 覆盖五项权威目标、错误ID、取消及版本变化零依赖执行。ConfirmationSummaryTest 用真实 ForegroundActionDialogs 逐项核对显示，模型伪标签不出现，取消回交 CANCELLED，缺证据时不能确认。ToolBusinessIntegrationTest.realConfirmationTargetsCannotReuseCancelledOrChangedApproval 使用正式SQLite/录音存储/AI设置：确认期间分别改真实职位名、请假备注、连接模型、录音 generation/分析，旧确认均失败，删除/采纳/付费测试未执行。原 all72ToolsInvokeProductionBusinessApisAndInvalidSchemaNeverMutates 的72项有效路径仍实际成功，保留原业务验证和保存流程。

<a id="air-005-fix"></a>
### AIR-005：目录安全视图

D13/RecordingSettingsView 增加 folderDisplayName 与 folderAccess。正式 AndroidRecordingRuntime 注入 SystemRecordingSource.describe，读取实际根 document 的显示名及持久READ授权状态；provider查询位于发布锁外。未配置为 NOT_REQUIRED，撤权为 DENIED，查询失败/缺失为 UNKNOWN，未知显示名为null；只接受叶子显示名，URI/完整路径不进入安全视图。D17的完整枚举及授权失败阻断保持。

RecordingFolderViewTest 使用真实 provider 查询、正式设置存储/服务及可控权限边界，覆盖未配置、正常授权空目录、切换另一空目录、撤权、null cursor、私有路径式显示名；两个空目录的显示视图不同。验证完整21域请求编码和工具安全投影没有 content:// 或私有路径。生产默认探测真实 persistedUriPermissions，测试中的注入授权不是实机授权证据。

<a id="epoch9-verification"></a>
### epoch9 完整验证结果

2026-10-18，最后产品修改后运行既定完整命令，退出码均0：

```powershell
python tools/check_backend_boundaries.py
.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --quiet
git diff --check
```

|模块|测试|失败/错误/跳过|
|---|---:|---|
|backend|123|0/0/0|
|ui|202|0/0/0|
|app|135|0/0/0|
|合计|460|0/0/0|

正常XML位置仍为 backend/build/test-results/test、ui/build/test-results/testDebugUnitTest、app/build/test-results/testDebugUnitTest。保留72条唯一TOOL_PROOF；新增的循环用例没有虚增为额外JUnit条数。debug APK 为47577390字节。Lint 0错误、10警告（类别与epoch8相同）。边界脚本 PASS 48 JVM sources，API合同相同。

真实小数据首轮：REAL_DEFAULT_PROOF bytes=29219 estimate=37261 tools=72 domains=21 callers=1 archives=1 http=1。D13新增字段后仍在49152默认下实际打开假HTTP一次，未裁剪全文或schema。未改变显式旧32768的保留规则、源/请求限额和服务端拒绝处理。

设备与付费服务边界没有变化：本任务已确认设备offline，不重复尝试或安装生产包；系统权限/SAF/tree/CSV/pin/overlay、真实付费模型和SIM/ROM录音仍未实测。最终验收与必需独立复验由主负责人安排。

---

## epoch8 实现与验证（历史保留）

2026-10-18，execution epoch8；代码目标提交 6578ff36da04c49170cc68218d896e96206fd5e9。实现已交付，最终验收由主负责人独立复验；自动测试结果不代替实机或真实服务验收。

## 交付与批准边界

R1–R6 已接入生产业务图：独立录音/AI设置、四导航与应用级沟通、21域完整快照、72项原业务接口操作、容量控制、一次性前台系统动作。沿用已批准架构、设计和实施计划；设计 blob 仍为 441bdcc39a2a15b905163424f672ab43c3411ed9，计划 blob 仍为 3b7c9a3c901b8dc7b20a362488bc5c7c15c02759。本轮没有改写批准对象。

此前成果保留在 049dc17、b15a145、ffc39bf。本轮补齐生产根装配、UI/宿主、录音上下文、共用字段合并、所有工具真实业务复验与回归。UI生产只依赖 application.api；backend 保持纯JVM，未加入中文字符串常量。根API与 docs/contracts/CallringUiApi.kt 相同。

<a id="q18-budget"></a>
## Q18 默认预算裁定与复验

负责人明确批准缺省 context 从32768调整为49152，输出仍4096、范围仍8192–1048576；不删字段/全文/schema/工具。原设计对象保留原文，偏离在本节及README记录。旧配置没有预算字段时用49152；已经显式保存32768等预算的配置保持原值（AiSettingsServiceTest 的 draftConnectionTestUsesUnsavedFieldsAndOldFixturesReceiveNewDefaultWithoutOverwritingExplicitBudget 用例内断言）。

原 epoch7 实际小数据请求29128字节、保守估算37161，超过32768，首轮HTTP被阻止。最终同类生产装配数据包含1档案、1独立归档、短转写尾哨兵、完整21域、全部72工具：

REAL_DEFAULT_PROOF bytes=29158 estimate=37194 tools=72 domains=21 callers=1 archives=1 http=1

假 HttpURLConnection 只有预算通过后才发送，HTTP计数实际为1；完整快照、尾哨兵和无密钥断言先通过。最终字节略有变化来自完整字段/manifest增加，不是裁剪。49152只是客户端限制；真实模型仍可能拒绝，客户端将明确停止且不自动重试。证据：CompleteSnapshotAssemblyTest、ConversationProtocolTest、ContextBudgetTest；XML见下节。

<a id="verification"></a>
## 最终命令与结果

最终结果于2026-10-18 20:38（Asia/Shanghai）核对；测试时间见XML，缓存命中项目沿用同一源码的有效报告。最后代码修改后完整命令退出码0：

```powershell
python tools/check_backend_boundaries.py
.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --quiet
git diff --cached --check
```

|模块|测试|失败|错误|跳过|
|---|---:|---:|---:|---:|
|backend|121|0|0|0|
|ui|196|0|0|0|
|app|132|0|0|0|
|合计|449|0|0|0|

APK：app/build/outputs/apk/debug/app-debug.apk（46978646字节）。Lint 0错误、10警告：ApplySharedPref×2、DataExtractionRules、NewerVersionAvailable×3、OldTargetApi、SpecifyJobSchedulerIdRange、UnnecessaryRequiredFeature、UnusedAttribute。未将警告写成零警告。

报告：backend/build/test-results/test、ui/build/test-results/testDebugUnitTest、app/build/test-results/testDebugUnitTest；app/build/reports/lint-results-debug.xml 及 html。构建输出不入Git。边界脚本实际输出 PASS: 47 JVM sources; no Android/UI/presentation dependencies; v1 contract matches。

## A1–A6 与 V01–V09 对应

以下类名可在 backend/src/test、app/src/test、ui/src/test 中检索；全部属于上述完整运行。单个参数循环测试的工具数另计72，未把它虚增为72个JUnit测试。

|验收|实现与实际自动证据|未实测限制|
|---|---|---|
|A1 / V01,V04,V09|AiSettingsServiceTest：两页交错、dirty冲突、非法清密钥不落盘、草稿测试不保存、旧预算；AndroidRecordingStoreTest 保留原设置与归档；CredentialDialogTest 重建不恢复明文；ConversationNavigationTest 两个设置入口；DraftMergeTest 独立字段合并/同字段拒绝|真机Keystore硬件行为、真实服务连接测试|
|A2 / V05,V06,V09|ConversationServiceTest：busy不排队、stop、新会话、串行、重复同参缓存/异参拒绝；ConversationProtocolTest 两协议配对/截断/非法整批；ConversationStateUiTest 全11阶段、全部9种工具结果；宽窄四导航|真实模型工具能力、进程被ROM强杀行为|
|A3 / V02,V08|CoreSnapshotSourceTest 实际SQLite251档案及审计尾、8MiB预检不交付部分状态；RecordingSnapshotSourceTest 独立事件/负ID、全文尾、私有路径不出站、全部诊断及坏行失败；CoreSnapshotProjectionTest 极值时间戳统计；CompleteSnapshotAssemblyTest 真实21域；DirectoryEnumeratorTest 500/501、变更、循环、4/5层及空第五层|具体系统录音provider/真实授权目录稳定性|
|A4 / V03,V04,V05|下表72项全部调用真实业务API、SQLite/偏好/录音存储；每项非法额外字段失败且版本不变。SharedBusinessCommandsTest 只采纳非空自述姓名/单位，保留职位/白名单；RecordingCommandContextTest 延迟导入遇删除/旧版本重试；RecordingPublicationTest/PublicationAdaptersTest 提交通知；DraftMergeTest 字段冲突；ForegroundLockOrderTest 两线程反向锁回归；ConversationServiceTest 网络挂起仍能来电写入、旧回复禁止提交|真实来电SIM与设备后台调度|
|A5 / V06,V08|ContextBudgetTest 完整UTF8估算/硬字节限制；真实默认首轮HTTP=1；ConversationServiceTest 工具后容量增长保留已提交收据并跳过余项；协议测试区分400上下文错误/一般400、413、认证、重定向、1MiB回复；SecretRedactorTest raw/百分号/Unicode混合及补充字符；短key保持协议标识；分析目标变化取消并清历史|具体服务端真实容量和错误格式|
|A6 / V07,V09|ForegroundActionServiceTest 无宿主/过期/失效/拒绝/重复迟到；ForegroundResourceRegistryTest 文件变化/移除/错误目标/会话清理；ForegroundActionHostTest 旋转只领取一次、取消结果及迟到拒绝；真实固定ActivityResult/权限入口已装配，工具测试用测试宿主返回结果；MICROPHONE保存检查、pin仅LAUNCHED、overlay仅启动|真机授权/角色/文件选择/CSV/tree/pin/overlay与ROM表现|

## 完整性、并发与安全要点

- 快照列出D01–D21、状态、数量、版本、采集时刻、来源范围和脱敏摘要。主库与审计同一读事务，录音独立库同一读事务，跨源版本前后相等才交付，最多3次；读取错误不伪装空集合。主库在事务内复制自有数据，在事务外计算统计/JSON。
- 主库、录音、两设置组、偏好/组件、铃声与诊断共用版本协调器；prepare/复制/媒体准备在发布锁外，最终校验版本/代次后短提交。ForegroundLockOrderTest 曾复现前台锁与发布锁的反向等待，修复为volatile宿主租约读取；前台票据仍一次消费。
- 原有页面与工具调用共用原校验、存储和变更通知。UI编辑提交初始基准与草稿，未编辑字段保留最新值；外部修改同一字段返回CONFLICT并保留草稿。显式null清空外观种子不被误当作Keep。
- 网络前先验证整个工具批次，再串行执行；一批≤8、每轮≤16次请求/32项操作。未知/失败不自动重放。工具后重新完整采样与预算检查；停止/新会话不会回滚已提交或已排队操作。
- 密钥仅在原密文、本地输入、短寿命连接对象和认证头中使用。显式安全投影排除音频/DocumentRef私有路径；raw、百分号、Unicode形式在数据叶子脱敏，不修改schema/type/name/id等协议字段。网关原文不进入错误。短期资源句柄使用前重查文件元数据/授权源、目标和期限。
- Android前台宿主只启动固定合约/Intent；模型不能指定任意URI/Intent或伪造SYSTEM录音身份。系统文件null为取消，权限实际probe后判定；pin请求接受只报告LAUNCHED，installed IDs另行读取。密钥表单只用remember，未写SavedState。
- 会话只在应用进程内存中，旋转不重复启动系统票据；进程新建新会话。来电路径不会为创建业务图而启动AI请求。

<a id="operations"></a>
## O01–O72 实际接口矩阵

来源：app/src/test/kotlin/com/greatwall/callring/platform/ai/ToolBusinessIntegrationTest.kt，方法 all72ToolsInvokeProductionBusinessApisAndInvalidSchemaNeverMutates；XML的TOOL_PROOF共72行。使用正式CallringBackend、SQLite、AndroidPreferences、AndroidDocumentStore、AndroidAudioController、AndroidRecordingRuntime、AiSettingsService；媒体/权限/AI边界为确定性Robolectric夹具，系统前台返回由测试宿主模拟，无付费调用。O37复用UiEditsApi.appearance后仍使用原外观验证与发布器。

每行有效参数返回下列真实状态，并由spy确认对应正式API调用；所有行额外非法字段均FAILED且协调器版本不变。另有实际档案字段、CSV内容、导入音频、排队、删除标记、采纳和本地复制存储断言。READ=读取，COMMITTED=业务提交，QUEUED=仅排队，LAUNCHED=仅启动。

|ID|工具|有效结果|共用API|
|---|---|---|---|
|O01|root_checkNumber|READ|root.checkNumber|
|O02|home_load|READ|home.load|
|O03|callers_search|READ|callers.search|
|O04|callers_getEditor|READ|callers.getEditor|
|O05|callers_getOverview|READ|callers.getOverview|
|O06|callers_getLatestCall|READ|callers.getLatestCall|
|O07|callers_saveProfile|COMMITTED|callers.saveProfile|
|O08|callers_setWhitelisted|COMMITTED|callers.setWhitelisted|
|O09|reports_load|READ|reports.load|
|O10|reports_exportCsv|COMMITTED|reports.exportCsv|
|O11|settings_getSummary|READ|settings.getSummary|
|O12|settings_setStatisticsEnabled|COMMITTED|settings.setStatisticsEnabled|
|O13|settings_setOverlayEnabled|COMMITTED|settings.setOverlayEnabled|
|O14|settings_getQuietHours|READ|settings.getQuietHours|
|O15|settings_saveQuietHours|COMMITTED|settings.saveQuietHours|
|O16|settings_setQuietMode|COMMITTED|settings.setQuietMode|
|O17|settings_setQuietEnabled|COMMITTED|settings.setQuietEnabled|
|O18|settings_getPositions|READ|settings.getPositions|
|O19|settings_savePosition|COMMITTED|settings.savePosition|
|O20|settings_deletePosition|COMMITTED|settings.deletePosition|
|O21|settings_setMyPosition|COMMITTED|settings.setMyPosition|
|O22|settings_getWorkSchedule|READ|settings.getWorkSchedule|
|O23|settings_saveWorkSchedule|COMMITTED|settings.saveWorkSchedule|
|O24|settings_resetWorkSchedule|COMMITTED|settings.resetWorkSchedule|
|O25|settings_getCalendar|READ|settings.getCalendar|
|O26|settings_synchronizeCalendar|COMMITTED|settings.synchronizeCalendar|
|O27|settings_importCalendar|COMMITTED|settings.importCalendar|
|O28|settings_saveDateOverride|COMMITTED|settings.saveDateOverride|
|O29|settings_deleteDateOverride|COMMITTED|settings.deleteDateOverride|
|O30|settings_getLeaves|READ|settings.getLeaves|
|O31|settings_saveLeave|COMMITTED|settings.saveLeave|
|O32|settings_deleteLeave|COMMITTED|settings.deleteLeave|
|O33|settings_getScoreSettings|READ|settings.getScoreSettings|
|O34|settings_saveScoreSettings|COMMITTED|settings.saveScoreSettings|
|O35|settings_resetScoreSettings|COMMITTED|settings.resetScoreSettings|
|O36|settings_getAppearance|READ|settings.getAppearance|
|O37|settings_saveAppearance|COMMITTED|edits.appearance|
|O38|ringtone_getSettings|READ|ringtone.getSettings|
|O39|ringtone_setEnabled|COMMITTED|ringtone.setEnabled|
|O40|ringtone_importAudio|COMMITTED|ringtone.importAudio|
|O41|ringtone_restoreBuiltIn|COMMITTED|ringtone.restoreBuiltIn|
|O42|ringtone_startPreview|LAUNCHED|ringtone.startPreview|
|O43|ringtone_stopPlayback|LAUNCHED|ringtone.stopPlayback|
|O44|runtime_getStatus|READ|runtime.getStatus|
|O45|runtime_getDiagnostics|READ|runtime.getDiagnostics|
|O46|runtime_requestCallLogSync|QUEUED|runtime.requestCallLogSync|
|O47|widgets_getBinding|READ|widgets.getBinding|
|O48|widgets_saveBinding|COMMITTED|widgets.saveBinding|
|O49|widgets_removeBinding|COMMITTED|widgets.removeBinding|
|O50|recordings_listArchives|READ|recordings.listArchives|
|O51|recordings_getSettings|READ|recordings.getSettings|
|O52|recordings_saveSettings|COMMITTED|ai.patchCombined|
|O53|recordings_testConnection|READ|ai.testSlot|
|O54|recordings_getArchive|READ|recordings.getArchive|
|O55|recordings_importRecording|COMMITTED|recordings.importRecording|
|O56|recordings_retry|QUEUED|recordings.retry|
|O57|recordings_delete|COMMITTED|recordings.delete|
|O58|recordings_play|COMMITTED|recordings.play|
|O59|recordings_stop|COMMITTED|recordings.stop|
|O60|recordings_setSystemFolder|COMMITTED|recordings.setSystemFolder|
|O61|recordings_systemRecordings|READ|recordings.systemRecordings|
|O62|widgets_listBindings|READ|widgets.listBindings|
|O63|recordings_adoptIdentity|COMMITTED|root.adoptIdentity|
|O64|ai_getSettings|READ|ai.getAiView|
|O65|ai_patchSettings|COMMITTED|ai.patchAi|
|O66|ai_editCredential|COMMITTED|ai.patchAi|
|O67|system_requestCapability|COMMITTED|runtime.getStatus|
|O68|system_openApplicationSettings|LAUNCHED|runtime.getStatus|
|O69|system_pinWidget|LAUNCHED|widgets.listBindings|
|O70|system_testOverlay|LAUNCHED|foreground.TEST_OVERLAY|
|O71|system_previewCall|LAUNCHED|callers.getCallDetail|
|O72|ai_copyConnection|COMMITTED|ai.copyConnection|

## 实机与外部服务边界

设备192.168.10.149:5555在本任务实际检查为offline；没有覆盖安装生产包、自动解锁、清数据或真实付费API请求。权限/角色返回、SAF导入与持久tree授权、CSV系统写出、桌面小组件安装和overlay实际显示，仍须在线设备人工复验；自动测试中的模拟结果不能证明这些真机行为。

真实SIM双方录音、听筒/免提/蓝牙、30分钟息屏、重启恢复、厂商省电与后台限制仍未实测。现有来电/录音限制继续适用。代码构建和449项自动测试已通过，不据此宣称真实通话录音或所有模型/ROM验收通过。
