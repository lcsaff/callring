# AI 沟通当前完整成果最终审查

日期：2026-10-18（Asia/Shanghai）。任务 **ai-final-review / reviewer / epoch1**，审查类型 **initial**。结论 **passed，findings=[]**：对当前112文件完整成果按A1–A6、D01–D21、O01–O72核对后，未发现必须修复问题。本次补齐新增文件和当前完整成果审查，供主负责人验收；不重开或改写原审查/复验。

<a id="binding"></a>
## 绑定、范围与证据复用

- sourceTaskId=ai-implementation，sourceEpoch=9。
- baseCommit=b2c3af79570d03a2040d3c1e02405f56821bb160；targetCommit=91ea4d8b574712904e44872babc33eac98261829。
- sourceArtifactDigest=1706b4dd29bb6a8d2eae70b11fa19802bf8fa1af491d821eba081aed98a4f699。
- 当前contextDigest=4f54a7ce92f1dc834ff8c38b5dd4a3243f98f85535fb02af8e1528db00283eb9；已依次context、acknowledge、begin_round。真实turnId=01a14f3f-d9c0-7520-aa94-b6e5184eb24e；checkpointId=checkpoint-c132713441480e2bb2845182d08487b2b9c5d0c667070c45d3cfb60cf0ac4a7d。
- HEAD等于target；完整112项路径、mode/type/blob与任务清单及base..target差异一致，零遗漏/零不匹配。工作区git status为空。相对原审查目标c3a1ef9，当前增量为27文件：20项已有文件修改，7项新增；其中1项生产逻辑、6项测试文件。
- 审查依据为已批准架构blob 27888a84f68339d36c074ed587e04904d2df902a、实现设计441bdcc39a2a15b905163424f672ab43c3411ed9、实施计划3b7c9a3c901b8dc7b20a362488bc5c7c15c02759，以及用户Q18默认49152裁定；README与AI沟通验收的当前说明已相互核对，批准文档未修改。

下称 **E=C:/Users/GreatWall/AppData/Local/Temp/callring-ai-recheck**。本轮复用刚完成ai-recheck在相同target上亲自运行的证据，**没有重新执行测试套件、构建或设备命令**。重新读取XML计数、校验123份封存证据的SHA-256，全部一致；不是依据实现者完成声明判定。

|复用运行|实际证据与计数|
|---|---|
|完整基线|backend123、ui202、app135，合计460，failure/error/skipped均0；E/baseline-backend、baseline-ui、baseline-app|
|独立临时测试|backend3、ui10、app1，合计14，failure/error/skipped均0；E/final-independent-backend、final-independent-ui、final-independent-app|
|构建/边界/Lint|baseline.log退出0；边界PASS 48 JVM sources、API合同相同；APK及Lint原报告保留，0错误10警告|
|恢复正常输入|restored-baseline.log退出0，restored-counts.json为123/202/135；临时init未进入正常测试输入|

同一测试的恢复或重复运行不累加；72工具循环不计为72个JUnit。六个新增测试文件含8个JUnit，已经属于460基线；不再作为本轮新测试计数。

完整可逐项检查的审查覆盖索引：**E/final-review-coverage.json**，SHA-256 **2e0c1310cfcf78f51cf0afef8fc110b1b4e856c5120c568afe5499fc9fb4bb14**。含112文件完整blob清单/分组、21域来源、72工具名称/真实API/状态/非法schema结果与原XML路径，并核对注册表、执行分支、验收矩阵及72条唯一TOOL_PROOF一致。final-binding-proof.json记录本次绑定与旧证据校验。

<a id="whole-source"></a>
## 完整成果与契约的关联

|成果组|文件数|核对内容|
|---|---:|---|
|契约与告知|3|README、根API合同副本、验收证据；全文外发/无音频、49152、进程会话与未测边界一致|
|装配与发布|20|BackendGraph、BackendContext/CallringBackend、平台存储适配等；单coordinator、懒会话、短锁、原保存/代次与通知|
|全量与隐私|9|主库/录音预检、投影、目录、脱敏等；独立全量来源，不借分页拼全集；失败阻断|
|设置与验证|6|两页安全API/正式服务、UiEdits、共享身份采纳等；原验证、dirty合并及基准冲突|
|会话与工具|10|DTO/端口、完整协议、会话执行、预算与72路由；整批验证、真实状态和错误|
|前台与目标|4|票据/宿主/权威确认等；固定动作、一次消费、真实本地目标、旧版本拒绝|
|导航与草稿|22|四入口、两设置、沟通、既有编辑页；脏草稿保留，变更订阅与深链|
|验证文件|38|当前新增/修改测试与共享夹具；断言范围与真实XML对应，区分mock/存储/系统边界|
|合计|112|逐路径/blob详见覆盖索引；不以新增文件未被旧审查登记为已覆盖|

读取并核对当前修正增量与上游接口关联：AiRoutes冻结可见目标；CredentialDraft只含非敏感连接patch；CredentialDialog一次patchCombined保存；credentialSaved只重基准已保存slot；ConversationScreen用toolCalls标明未验证；ConfirmationTargets从bound API取本地目标；ToolExecutor/ForegroundActionService提交前复核；SystemRecordingSource真实根目录描述经RecordingSettingsView/SafeProjection进入D13。没有引入新的模型URI/任意Intent/SQL/代码入口或另外的业务保存副本。

<a id="new-files"></a>
## 新增生产文件和六个新增测试文件

|文件（相对仓库）|实际逻辑/断言、边界及独立证据|
|---|---|
|backend/src/main/kotlin/com/greatwall/callring/application/conversation/ConfirmationTargets.kt|生产新增。第8行按index20/32/53/57/63调用真实settings/aiSettings/recordings/callers读取。职位/请假/归档不存在返回NOT_FOUND，不发伪目标；采纳只展示非空自述及旧值；测试连接含真实slot/协议/URL/model/凭据状态。调用端ToolExecutor:48–53读取后和确认后核command；宿主只展示ConfirmationTarget，不信模型名称。独立五类UI/API测试已核落点、取消、真实目标变化与迟到结果。|
|app/src/test/kotlin/com/greatwall/callring/platform/ai/RecordingFolderViewTest.kt|1项。正式store/service加可控ContentProvider，覆盖未配、两个空目录、撤权、null cursor与路径式名称；检查安全请求/结果。授权是注入边界，不能当真机persisted授权；其他20域为合成数据。独立RecheckDirectoryTest补SecurityException/provider异常、URI与Windows路径；真实全装配另由CompleteSnapshotAssemblyTest。|
|backend/src/test/kotlin/com/greatwall/callring/ConfirmationTargetTest.kt|1项循环五操作。真实backend设置/档案配可控recording/AI端口；检查真实摘要、错误ID、取消、版本变化后零依赖效果。该文件用score变更检验全向量失效，并非单独证明每类实体变更；正式SQLite的ToolBusinessIntegrationTest和独立五类确认分别补齐实际职位/请假/model/归档变更。|
|ui/src/test/kotlin/com/greatwall/callring/ui/app/ConfirmationSummaryTest.kt|1项。真实ForegroundActionDialogs展示五类本地摘要；模型伪label不出现，取消回CANCELLED，缺证据禁确认。手工票据只证明展示；真实票据/业务版本链已由上述集成及独立测试覆盖。|
|ui/src/test/kotlin/com/greatwall/callring/ui/app/ConversationReviewUiTest.kt|2项。IDLE/STOPPED/COMPLETED/FAILED展示下一发送新目标；空目标禁发；含工具文字在等待、拒绝、取消、失败、提交、跳过都标明未经验证。状态为受控输入，不代替服务执行；独立RecheckSendFenceTest验证真实服务部分提交/取消，RecheckProductionUiTest再核混合回执。|
|ui/src/test/kotlin/com/greatwall/callring/ui/app/FirstConnectionUiTest.kt|2项分别analysis/transcription，走实际AiSettingsRoute。空页面填URL/model，取消零保存，失败保输入，重试同时提交patch和key，成功后keyConfigured且不再dirty。保存端是受控API，故不能单凭此称落盘成功；正式AiSettingsServiceTest以及独立两项实际route+AndroidRecordingStore测试已补齐。|
|ui/src/test/kotlin/com/greatwall/callring/ui/app/RenderedDestinationTest.kt|1项。暂停Compose帧，让VM已读取新配置但屏幕仍旧；派发旧composition真实按钮回调，断言携带旧已告知连接，拒绝后新帧才发送新值。sendTo记录器只证明UI边界；真实服务sendTo/runTurn两次比较、捕获窗口及协议/URL/model活动变更取消由独立RecheckSendFenceTest覆盖。|

六新增测试文件没有通过放宽业务验证、删快照/schema或替换生产保存路径规避失败。新增测试及同时修改的既有测试族的边界均与实现代码一致；没有将伪provider/内存backend或本地Cipher测试写成真机验证。

<a id="acceptance"></a>
## A1–A6 当前完整验收核对

|验收|源码结论与关联证据|
|---|---|
|A1 / R1|SettingsPage.RECORDING与AI分设，旧namespace/Keystore alias/config保持；RecordingJson缺预算读49152、显式32768保留。AiSettingsService与AndroidRecordingStore短锁内字段合并、完整validate、密钥版本检查。基线AiSettingsServiceTest/AndroidRecordingStoreTest/DraftMergeTest，以及独立首配两slot/失败/取消/并发/密钥出站证据通过。|
|A2 / R2|CallringApp同tabs给窄/宽四入口，ConversationRoute、ConversationScreen显示发送/阶段/错误/实际结果；busy禁发、stop/newSession/旋转与进程边界明确。ConversationNavigationTest、ConversationStateUiTest、服务/协议与可见目标/未验证文字独立测试通过。|
|A3 / R3|D01–D21全量装配、同SQL事务审计、独立录音全文、目录/诊断/绑定与运行probe均接生产源；缺域/坏源报错，完整类型强制21唯一域。SafeProjection明确排资源URI/音频私有路径/密钥对象，SecretRedactor处理两key和编码形式。源测试251档案/尾哨兵、目录边界、损坏失败、正式全装配与独立目录/密钥证据通过。|
|A4 / R4|72schema、ToolExecutor分支、验收矩阵和实际TOOL_PROOF逐项一致；保存调用现业务API及原验证，AI绑定读版本，UI编辑按dirty字段合并，状态取本地回执。PublicationAdaptersTest/RecordingPublicationTest/DraftMergeTest/RecordingCommandContextTest/ForegroundLockOrderTest通过；旧确认拒绝及实际回执独立验证通过。|
|A5 / R5|ContextBudget在完整协议编码后计UTF8体、1024开销、output及安全余量；源端8MiB/20000行预检、请求/响应上限、工具后重新完整读取与预算均保留；不裁剪会话/schema。用户Q18缺省49152一致；普通400与context拒绝区分，不自动重试。实际假HTTP首轮与超限/部分提交测试通过。|
|A6 / R6|ForegroundActionHost在前台使用固定Android合约/Intent；真实probe判权限，null取消；requestId/host lease/代次/10分钟期限核验并一次消费。句柄重查目标与元数据；具体删除/采纳/测试核权威目标。票据、旋转、迟到/重复、资源变更、锁序及独立五确认通过；实机授权仍未测。|

<a id="domains"></a>
## D01–D21 逐域来源

|域|当前生产来源与完整性核对|
|---|---|
|D01|CoreSnapshotProjection全部profiles与calls号码并集，含非规则号码；251档案源测试|
|D02|全positions/options、active/level/我的职位；不受页面筛选|
|D03|全部RawCallRecord含hidden/link/account/原现与legacy字段|
|D04|复用visible/detail去重明细，与D03原始事实区分|
|D05|全部statisticsIntervals及每号码全时段/rolling统计；复用score/reportTotals，极值时间饱和测试|
|D06|当前及历史schedules、工作状态/nextBoundary，同一采样时刻|
|D07|全calendarYears/holidays/makeupDates，不限当前年；不伪造未存原JSON|
|D08|全overrides及windows|
|D09|全leaves/备注/按同一采样时刻的状态和版本|
|D10|score/quiet/lastQuietMode/statisticsEnabled/overlayEnabled|
|D11|正式PreferenceStore外观|
|D12|正式AudioController设置及runtime实测playback，无音频私有路径|
|D13|正式AiSettingsService录音视图，真实根显示名与folderAccess；失败状态明确、安全名称过滤|
|D14|两slot protocol/URL/model/keyConfigured与预算，解密坏失败|
|D15|独立录音库全events/links/deleted，负ID及统计停用仍保留|
|D16|未删全归档、完整transcript/analysis/错误/重试/代次/模型，尾哨兵保留|
|D17|授权目录两次稳定全枚举及短期句柄；500/501、层数、循环/变化/撤权/查询失败明确处理|
|D18|全部持久widget bindings与真实installed IDs/访问状态，残留与安装区分|
|D19|全Capability含MICROPHONE、声音/通话/播放/pin能力及录音播放状态|
|D20|captureStoredText读取现存全部diagnostics_v2和legacy，不沿UI take(18)，坏行失败|
|D21|主库captureFull同读事务读取全部calendar_audit，不把pendingAudits当历史|

CoreSnapshotProjection、CompleteSnapshotAssembly、AndroidRecordingStore三处来源的21唯一ID与AiBusinessSnapshot构造约束已核对；跨源前后revision不同丢整份最多3次，目录稳定遍历不声称外部原子。具体每域源码和XML路径已逐项列入覆盖索引。

<a id="tools"></a>
## O01–O72 全操作核对

覆盖索引的tools数组含**每个O编号**的name、生产API落点、真实结果状态、额外字段非法=FAILED和原XML证据；72项没有合并省略。注册表严格schema、执行器1..72和文档72行完全对齐。本次再逐组检查其原验证/边界：

|范围|当前真实落点及关键约束|
|---|---|
|O01–O10|root/home/callers/reports读、profile补丁/白名单/CSV；号码/分页/职位原校验，CSV真实文档与防公式注入|
|O11–O17|summary/statistics/overlay/quiet原服务；真实权限、quiet上次模式和weekly校验|
|O18–O24|职位/我的职位/work读写reset；原IN_USE/时区/版本，O20真实目标确认|
|O25–O32|calendar同步/导入、override、leave；网络/文件锁外，原1MiB/日期/重算/审计，O32真实目标确认|
|O33–O37|score与appearance原验证；显式null与未编辑字段区分，UiEdits基准合并|
|O38–O43|ringtone设置/导入/restore/preview/stop；正式音频适配器20MiB/可播放/代次，预览仅LAUNCHED|
|O44–O49|runtime/probe/diagnostics/sync和widget binding；同步仅QUEUED、真实保存与能力检查|
|O50–O54|完整归档、安全settings、组合patch、已保存slot固定测试和getArchive；无key/URI参数，O53真实服务目标确认|
|O55–O61|真实录音身份解析、系统句柄或SAF导入/retry/delete/play/stop/tree/list；256MiB、generation/ownedFile/授权，O57确认|
|O62–O66|全部绑定、只非空自述采纳、安全AI设置/patch与本地凭据；保职位/白名单，O63确认，敏感输入不进入模型schema|
|O67–O72|权限/app设置/pin/overlay/真实CallDetail preview/本地slot复制；真实结果、固定动作，复制内部处理key并强制转写OPENAI|

all72ToolsInvokeProductionBusinessApisAndInvalidSchemaNeverMutates使用正式backend/SQLite/偏好/录音/媒体适配器，逐项有效输入实际调用并核关键落盘，逐项非法额外字段失败且版本不变。它不等于穷尽每一种业务非法值；原业务测试、单独并发/媒体/权限/采纳/确认测试补充原验证边界。没有把spy收到方法名当作全部集成成功，亦没有把READ/QUEUED/LAUNCHED冒充完成。

<a id="protocol-concurrency"></a>
## 协议、容量、密钥与并发复核

- 两协议均先完整有界HTTP/StrictJson、单响应停止原因与整批schema/ID校验后才执行；OpenAI工具结果配对，Anthropic保留text/tool_use并先tool_result。不支持的停止原因/截断/旧function_call/伪工具失败；无自动改协议/模型或重试。接口限制与README相符。
- REAL_DEFAULT_PROOF bytes=29219 estimate=37261 tools=72 domains=21 callers=1 archives=1 http=1：来自本次相同target的真实业务图/SQLite/录音存储与假HttpURLConnection，预算通过才实际发送；不是仅DTO体积推算。ContextBudget/源预检/400上下文与一般错误/413/1MiB响应等原报告与XML仍有效。
- 安全投影不序列化AiConnection/DocumentRef/私有音频路径。业务字符串叶子脱敏两slot key，保固定schema/type/name/id，不全局替换JSON；raw/百分号/Unicode/混合与补充字符证据在SecretRedactorTest/ConversationProtocolTest。换分析目标/key会取消当前链、清匹配历史；下一请求携带已显示目标，独立测试核协议/URL/model及Compose未重组窗口。
- 发布锁序仍为coordinator fence→service/store→DB/偏好，网络/媒体准备/目录provider锁外。正式保存核command/dirty基准，存储发布后入账/通知；目标generation变化不能复活旧导入。ForegroundLockOrderTest与RecordingCommandContextTest、独立捕获/确认窗口测试均为同target通过证据。可保守拒绝并发计划，没有为通过而跳过版本校验。
- 带工具文字始终标为未经验证；失败、取消、未知和部分提交看实际receipt。ConversationService完整保留配对与已提交事实，不因随后容量/调度/停止回滚或伪造成功。原五项AIR的修正已在ai-recheck独立passed，本报告沿用其证据，不更改其原始verdict。

<a id="history-boundaries"></a>
## 历史与剩余环境边界

原ai-review报告在git对象5925965774c170f7e8eab809effb5319111f3012:docs/plans/AI沟通审查.md，原changes_required保持。AIR-001–005独立复验报告为 **E/AI沟通复验.md**，SHA-256 **161078fc07aa09d0e1deccf549155d70b9b63b627bc9050ad5b7a20a9666d379**；原reviewId/recheck五项检查不重写。本次是当前112项成果新的initial全面审查，不是第二次recheck。

设备原状态offline，真实权限/角色、SAF/tree持久授权/CSV、pin/overlay、Keystore、SIM双方录音、ROM后台/蓝牙/免提/息屏/重启仍未实测；真实付费模型的工具兼容性、费用、容量/错误格式也未请求验证。保持已有边界，本轮未重新连接设备或安装生产包。自动证据不能据此宣称所有设备/服务验收通过。

本轮产品/批准文档/Git工作区均未修改，无子Agent、无提交。完整报告与覆盖索引位于TEMP，由主负责人决定验收并归档；结束真实轮次后封存现有HEAD。当前无新增必须修复意见。
