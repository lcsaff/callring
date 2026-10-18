# AI 沟通实现设计

日期：2026-10-18。任务：ai-implementation，epoch 2，阶段 implementation_design；本文是设计提交，尚未实现产品。设计获批后另交实施计划，计划获批才进入 execution。

<a id="dependencies"></a>
## 1. 唯一契约与覆盖

架构：`docs/superpowers/specs/2026-10-18-ai-conversation-architecture.md`，commit `c6c4b2ae319329d00144395d43a01d8a27112769`，blob `27888a84f68339d36c074ed587e04904d2df902a`；批准 `approval-57e9c78702c8554387d329631184ffc04f294aa7382142d9e970ab06712f9619`，contentDigest `60f94c961b5c267186b01374a14ce2b89015bd1886d0cc71c69e13aa7ea6091f`。下文 ARCH#锚点均指该对象；D01–D21、O01–O72、V01–V09 和 Q01–Q22 沿用原 ID，不复制上游账本。实现讨论 revision 1，digest `cfddecf967a5edcd5b46038b7d6f422a0e0e28b4e84dcbd2a85f6748a5f08758`，未决项为空；本设计无架构偏离。

路径缩写：B=`backend/src/main/kotlin/com/greatwall/callring`，A=`app/src/main/kotlin/com/greatwall/callring`，U=`ui/src/main/kotlin/com/greatwall/callring`。所有新 UI 只依赖 application.api；backend 无 Android/JSON 库/中文展示文案依赖。用户文案留 U，平台序列化/HTTP 留 A。

|需求/验收|本文契约|核验族|
|---|---|---|
|R1/A1|settings、compatibility、publication、ui|V01/04/09|
|R2/A2|components、conversation、protocol、ui|V05/06/09|
|R3/A3|snapshot、redaction、ports|V02/08|
|R4/A4|commands、operations、publication|V03/04/05|
|R5/A5|capacity、protocol、errors|V06/09|
|R6/A6|foreground、conversation、ui|V07/09|

<a id="components"></a>
## 2. 组件与装配

|位置/组件|责任及依赖|
|---|---|
|B/application/api/ConversationApi.kt|发送/停止/新会话、安全状态/消息/回执、前台桥接 API 类型；无平台对象|
|B/application/api/AiSettingsApi.kt|两页安全视图、字段补丁、页基准、仅本地凭据命令、预算|
|B/application/api/AiSnapshotApi.kt|完整快照 DTO、21 域 manifest、版本、错误与脱敏摘要|
|B/application/conversation/ConversationService|应用 scope、单会话、串行轮次、完整历史、账本、连接告知；依赖下列服务/端口|
|B/application/conversation/AiSnapshotService|跨源稳定采样、全量业务投影、容量累计；复用 CallerProjection、ReportCalculator、WorkClassifier|
|B/application/conversation/ToolRegistry、ToolExecutor|72 项固定描述、类型校验、共用命令路由、安全回执；不访问 SQL/Android|
|B/application/SharedBusinessCommands|补丁合并、现有业务验证/保存、目标代次校验；原 API 及 AI 均调用|
|B/application/BusinessRevisionCoordinator|短 PublicationFence、incarnation/版本向量、命令授权/提交收据及全域失效|
|B/application/conversation/ForegroundActionService|一次消费票据、host lease、取消/过期/代次核验、短期文件句柄|
|B/application/ports/ConversationPorts.kt|源捕获/凭据访问/协议请求/编码预算/取消端口，暴露纯 JVM 类型|
|A/platform/ai/AiSettingsService|原 RecordingStore 密文内的页补丁及本地凭据更新；不可导出密钥视图|
|A/platform/ai/CompatibleConversationClient|OPENAI/ANTHROPIC 编解码、严格 JSON、有界 HTTP；只返回完整回复、不执行工具|
|U/ui/feature/conversation、ai|沟通与 AI 设置页面/VM；录音设置 VM 迁移为只管本页|
|U/ui/app/ForegroundActionHost|生命周期、ActivityResult、固定权限/角色入口和本地确认/凭据表单；结果回交桥接|

`CallringUiApi` 增 conversation、aiSettings、snapshots；conversation 暴露 foreground 子 API。新增方法在生产及 `docs/contracts/CallringUiApi.kt` 同步。既有 `recordings` 和其它 API 保留兼容。测试替身不支持新能力时返回明确 unavailable，不能以默认空快照/成功代替。接口 DTO 可拆文件，但根声明副本必须一致。

`BackendGraph` 建唯一 coordinator，注入 CallringBackend、录音/偏好/铃声/诊断适配器；懒建 ConversationService/快照端口/客户端，首次打开沟通或发送才初始化，不在来电路径构造全集或联网。graph.api 聚合既有 backend 与 recording、新服务；close 先停止会话、取消 HTTP、失效票据，再按现有资源生命周期关闭。记录独立录音的原 incoming/links/CAS 流程保留。

<a id="settings"></a>
## 3. 拆页、字段补丁与凭据

`RecordingView` 仅 recordingEnabled、automaticAnalysis、captureMode、目录显示/授权状态及页版本；`AiView` 含两 slot 的 protocol/baseUrl/model/keyConfigured、预算及页版本。模型投影的 URL 是安全文本；实际连接仍仅在凭据层取。UI 如需编辑现有凭据，凭据组件可读取本地输入状态，绝不交给会话快照/工具 DTO。

`PatchField<T>` 为 Keep/Set；字段缺失为 Keep，允许清空的字段用 Set(null)，weekly/列表整字段替换。本地凭据 `LocalSecretChange` 为 Keep/Replace/Clear，不进入工具 schema。每页草稿保存安全基准、dirty 字段与该页 revision；凭据输入只在 VM 内存，退出或成功后清理。

`patchRecording(base, patch)` / `patchAi(base, patch, localSecrets)` 流程：准备输入→PublicationFence→原设置锁→读取最新密文→对 dirty 字段比较基准→合并最新另一页/未改字段→原完整 RecordingSettings.validate＋预算验证＋必要能力检查→加密并 commit→增加相应字段组版本、记录真实收据→锁外通知/调度。脏字段被别人改则 CONFLICT，保草稿并列冲突字段；另一页改变不导致本页回退。旧完整 saveSettings 仍经同一验证/提交执行器，已迁移 UI 不再使用整份保存。

仅缺 config 采用默认；解密/解析失败阻断保存。commit 失败恢复偏好内存旧值并保旧密文。自动分析沿用双连接合法性，转写固定 OPENAI；Clear 不自动关闭其它字段。用户需显式提交一致的关闭/清空组合。目录只能通过 setSystemFolder 系统动作，不接模型 URI。

两向 copyConnection 使用本地源/目标 slot 版本、本地预览及保存；内部复制凭据，向转写复制强制 OPENAI，模型仅收到目标 keyConfigured。界面可测试未保存草稿，工具 testConnection 只测已保存 slot；共用连接校验及固定测试。连接测试需既有告知，不默认用真实业务全集测试。

<a id="publication"></a>
## 4. 提交、一致性与刷新

`RevisionVector` = 随机进程 incarnation + core/recording/recordingConfig/aiConfig/preferences/ringtone/diagnostics 单调 Long。版本不以时间替代；成功持久提交才增，失败回滚不增。播放/运行状态每次实测附采样时间，不伪装持久版本；成功播放等操作仍按既定失效策略通知。

统一锁序 PublicationFence→service/store monitor→DB/偏好。现有 `@Synchronized` 写方法改为先入 fence 再锁存储，禁止从已持 store 锁的监听/回调反向取得 fence。源纯读锁释放后才读取 coordinator 版本。网络、目录遍历、全文序列化、媒体 prepare/拷贝、等待系统界面全部锁外；通知流消费和 JobScheduler/Binder 锁外。

`CommandContext` 的 operationId、origin、expectedVersions、sessionGeneration 均本地生成，不从模型解析。显式参数传递到共用命令和提交器，不用跨挂起 ThreadLocal。旧 UI 完整命令走 local context；新 UI 的编辑保存走页/实体基准补丁。AI 默认绑定全部静态域读依赖，保守冲突可接受，防丢更新优先。

短提交临界区完成：核会话仍活跃/版本/目标 generation→原业务验证和事务/原子替换→递增版本→生成 commitEvidence/账本条目/失效事件。长操作拆为 prepare、publish、afterCommit；publish 前重核取消/目标/版本，失败清 scratch 保旧。提交后调度失败不能改称未保存；返回已提交事实及后续失败状态。不可取消提交仍先记账再停止。

生产接入清单：BackendContext.mutate/recordIncoming/updateDecision/importSystemCalls；录音 registerIncoming/linkStatistics/compareAndSet/delete/saveSettings 与后台恢复；AndroidPreferences 外观/绑定；AndroidAudioController 设置/导入/restore；诊断真正写入；系统目录授权保存。适配器对重复无变化写不增版本，事务失败不发变更。录音 CAS 是最终发布点，网络转写期间不持 fence。

首版成功变更发布全域失效，新增 RECORDING_SETTINGS/AI_SETTINGS/RECORDING_ARCHIVES。复用累计 StateFlow，保证慢订阅不会漏域。VM 无脏值则加载最新；有脏值保留并显示“保存值已变化”，保存再核冲突。录音归档列表/详情订阅 ChangeFeed，后台状态可刷新；onResume 重 probe。来电仍仅做原本短持久写，不等待 AI 网络/全集。

<a id="ports"></a>
## 5. 全量源端口

BackendStore 新增专用完整捕获：同 SQL 读事务先累计 COUNT/文本字节长度，再加载 state 与所有 calendar_audit；不更改普通来电读接口的行为。`LazyBackendStore` 转发该方法，假 Store 可实现确定性捕获。主库快照返回深复制的只读 DTO，BackendState 不逃逸；audit 的 raw 属数据，脱敏后发送，不把 pendingAudits 当历史。

录音捕获在独立库同读事务预检 events、statistics_links、archives、deleted_archives，读取完整 payload 严格解析；配置解密另走原设置锁，配置失败阻断。PreferenceStore 增 listBindings；RuntimeProbe 增全量 diagnostics 文本能力及 widget installed/support 信息；平台权限逐项 probe 含 MICROPHONE。未知返回 UNKNOWN，只有已证不支持才 UNAVAILABLE。

AndroidDiagnosticsStore.captureStoredText 遍历所有现存 diagnostics_v2 与 legacy 项，保现有保留策略，不能 take(18)；坏存量项报域失败，不 mapNotNull 静默丢弃。主库/录音库均源端限制后才构造全文，超限不 catch OOM 或改为部分成功。

<a id="snapshot"></a>
## 6. 21 域快照与完整性

每次捕获短 fence 采 v0→各源事务复制→运行态 probe/目录稳定枚举→短 fence 采 v1；向量不同丢整份，最多 3 次。采样钟固定一次传给投影；失败标域名/错误键，不把旧域拼到新域。Complete 构造器仅在全部域满足契约后可用，snapshotId 与 manifest 摘要由本地生成。

|域|实现来源/边界|
|---|---|
|D01–D04|主库全部号码/职位/RawCallRecord；含非规则号、hidden、账户、原现事实/旧值、关联；detail 投影复用原实现|
|D05|statisticsIntervals＋按号码 visible/count 全时段和 rolling；复用 Calculator，空 totals=0/score=null，min/max 饱和边界|
|D06–D10|主库全部 schedules/calendar/holidays/makeup/overrides/leaves/policy；含历史和跨年，真实日期名称、nextBoundary，非当前页面范围|
|D11–D14|偏好/铃声及播放实测、录音页安全视图、AI 两 slot 安全视图/预算；配置解密坏不假未配置|
|D15–D16|录音独立 events/links/负 ID/删除标记及未删归档全文，保转写/分析全部字段/错误/attempts/generation/model/来源|
|D17|已授权目录全音频元数据/本地随机句柄；未配置 NOT_CONFIGURED；超限/撤权/提供器错误 FAILED|
|D18–D19|所有持久绑定＋installed ID/支持状态；全部权限/角色/声音/通话/播放/pin 能力实测|
|D20–D21|现存全部诊断/legacy＋完整 calendar_audit；不补造淘汰内容或未存原 JSON|

manifest 各域包含 status/itemCount/sourceVersion/collectedAt/sourceScope/digest；EMPTY 仅权威查询真零项。FAILED 导致整体 Failure 并列缺失域；NOT_CONFIGURED 限合法可选未配状态，UNAVAILABLE 必须有支持探测依据。返回结构注明跨源稳定观测与系统目录观察范围，不声称外部全局原子。

系统目录遍历同时计节点/深度，保留 visited ID 集防循环；超过深度4或第500节点后确有剩余即 enumeration_limit。null Cursor、撤权、坏元数据/提供器异常不当空。两次完整枚举比 ID/显示名/modified/size，最多3次稳定尝试，持续变化明确失败；缺时间/大小保 unknown。遍历含非音频节点计数，不能先过滤绕过限制。

<a id="commands"></a>
## 7. 共用命令与注册表

固定 ToolDefinition 由 id/name/参数字段表/必填约束/读写类别/handlerKey 构成，统一产 schema、解码器及覆盖清单。JSON AST 为纯 JVM 类型；Android 负责 StrictJson→AST，不能任意反射序列化对象。schema 禁额外字段；Long 精确解析、日期/时区严格，禁止 Number→Double 丢精度；PatchField 缺失保留，null 仅可空字段接受。

现有 SettingsService/CallersService/WidgetBindingsService 的保存验证抽为 SharedBusinessCommands 的类型命令，原 API 和新增 patch 方法只适配输入。单个字段补丁在原 read-modify-validate-commit 事务合并，不在工具层 get 后整份 save。读 handler 必须调用实际 API，快照存在不省略。平台媒体与录音继续用原适配器，提交前 expected context 接入同 coordinator。

操作覆盖清单在 `docs/plans/AI沟通验收.md` 按 O01–O72 每行登记 schema/handler/共用 API/类别/正反测试/证据；注册表测试要求 ID 正好为该集合且无重复、无空 handler。以下为本设计路由分组，参数/确认细节不替代 ARCH#write-matrix：

<a id="operations"></a>
|操作 ID|真实执行落点及新增工作|
|---|---|
|O01–O06|checkNumber、home、callers 现读 API；O03 保指定分页边界，不作为全量源|
|O07–O08|saveProfile 补丁执行器、setWhitelisted；职位校验及局部字段保留|
|O09–O10|reports.load/exportCsv；CSV 目标由系统创建，保原防公式注入和 recordCount|
|O11–O17|settings 摘要/统计/overlay/quiet，quiet 模式记忆沿原 validate/update；能力不足真实失败|
|O18–O24|职位 CRUD/我的职位、work schedule 保存/reset；deletePosition 确认及 IN_USE，沿原版本/生效区间|
|O25–O32|calendar 读/同步/文件导入/override、leave 读写删除；网络锁外、1MiB JSON、原重算与审计、删除确认|
|O33–O37|score 读写/reset、appearance 读写；补丁经原验证，偏好 commit 失败回滚|
|O38–O43|ringtone 六 API；音频20MiB/可播放校验/原子替换、预览能力与 idle 检查|
|O44–O49|runtime 三 API、widgets 三 API；同步回 QUEUED/ALREADY_QUEUED，绑定校验沿原规则|
|O50–O54|recordings.listArchives/getSettings/getArchive；saveSettings 工具解码为两页非敏感补丁；testConnection 已保存 slot＋前台告知|
|O55–O61|导入/retry/delete/play/stop/setSystemFolder/systemRecordings；256MiB、真实目标身份/代次、删除确认；O61 句柄可供 O55 选已签系统项|
|O62–O63|新增全绑定读取；adoptSelfReportedIdentity(callId, expectedArchiveGeneration) 在共用命令内只合并非空自述姓名/单位|
|O64–O66|AI 安全设置读取/非敏感补丁、本地凭据表单动作；修改 analysis 目标使本轮终止|
|O67–O71|共用前台权限/应用设置/pin widget/overlay test/真实 CallDetail preview，固定动作与参数，不允许模型 Intent|
|O72|本地复制连接预览保存，内部复制 key，绑定两 slot 版本，目标转写强制 OPENAI|

ToolReceipt 保 operationId/toolCallId/status/changedDomains/observedAt、安全 value/error、可选 commitEvidence；保留 ApiError 的 code/fieldErrors/requiredCapabilities。READ、COMMITTED、QUEUED、LAUNCHED、CANCELLED、DENIED、FAILED、UNKNOWN 分开；跳过项显式 skipped。执行状态来自真实 API/存储提交，不从模型文字推断成功。

<a id="conversation"></a>
## 8. 会话、轮次、取消与执行

`send(expectedSessionId,text)` 原子核 session/busy/输入长度后返回 TurnAccepted；不排队第二条。状态按 ARCH#state：CAPTURING→BUDGETING→REQUESTING→VALIDATING→EXECUTING/WAITING_FOREGROUND→重新捕获，至 COMPLETED/FAILED/STOPPED。状态保本轮已提交与未执行回执，模型文字不得覆盖。`observeState` 是 StateFlow；UI 显示最终执行事实。

每轮保存 local turnId/generation、requestSequence、读依赖版本、协议历史及账本。副作用前登记 IN_FLIGHT；幂等键(sessionId,turnId,providerCallId)＋参数摘要，同参返回缓存，异参拒绝。工具批次先整体校验：最多8个、name/args/ID/协议结束原因均合法才开始；轮次最多16次请求/32工具，超过整批零执行。批内不猜前项生成的新 ID。

每项串行：核 session/前台/版本→执行→真实回执入账→重新捕获全集并预算→才下一项。失败补齐余项 skipped，配齐协议工具结果才可再次请求；不能配齐历史则要求新会话，不能删除旧消息“修好”协议。外部变化不执行旧命令，报 conflict 并重捕获后重算。UNKNOWN 非幂等结果不自动重放，无网络自动重试。

`stop` 幂等增加 generation、取消客户端、失效票据和后续命令；已发布提交先记录再 STOPPED。新会话先停止旧轮并保可见的已提交摘要，再清会话全文开始新 ID；业务不回滚。analysis protocol/URL/model/key 更改成功后保存本地回执并终止当前链，清旧密钥匹配历史与待发体；下一发送告知新目标。transcription 变化仅按向量重算。切页/旋转不销毁会话，进程死亡不恢复聊天或执行。

后台可读完 HTTP，但新增业务写及平台动作必须有 RESUMED host lease；无前台则等待，回前台重新核版本。停止可使等待退出；不保 Activity 引用。

<a id="foreground"></a>
## 9. 前台动作与资源

API：observePending、attachHost/detachHost、beginLaunch(requestId,hostToken)、deliver(requestId,HostResult)。hostToken 仅本地随机租约，beginLaunch 同时核票据/会话/轮次/generation/目标摘要/读版本/10分钟有效期并原子领取。CREATED→WAITING_FOREGROUND→CLAIMED→LAUNCHED→RESULT_RECEIVED→VERIFIED→CONSUMED，取消/拒绝/过期/失效/不支持为终态；任一票据最多消费一次。

ForegroundActionHost 在 Activity RESUMED 时 attach，onStop detach；旋转重建按稳定 ActivityResult registry key 注册并重接已有 requestId。LAUNCHED 不再次弹；回调必须匹配旧 requestId，即使当前有新票据也不改绑。进程重建无活票据，旧回调仅清资源。系统取消/null、ActivityNotFound、grant=false 分别回明确状态；返回系统设置不等于授权，由 runtime probe 判定。

动作类型仅包括 capability/应用设置/固定预览/overlay/pin、音频/日历/tree/CSV，以及既有删除/采纳/测试确认、本地凭据/复制表单。用户界面入口复用相同 launcher/本地表单执行器，保留既有交互。HostResult 只作为本地证据，不能被模型创建；提交器仍重新校验。

文件返回先查 content scheme、来源 contract、真实授权/元数据，再注册随机句柄绑定 action/目标/代次/期限；URI 保存在本地资源表，不进快照/工具参数。系统目录持久 READ 后再原存储保存，撤权明确失败。使用句柄前再次查 grant/文件/版本，过期或目标换代禁止导入，失败清 scratch。O55 的 number/startedAt/source 从录音身份端口解析；模型只传 callId/句柄，不能伪 SYSTEM 来源。

麦克风进入 Capability 全链，保存采集模式时真实检查。pinWidget 的 requestPinAppWidget=true 仅 LAUNCHED，安装需宿主 ID 回证；overlay test 区分启动与实显，previewCall 从业务读取 CallDetail。没有入口回 unavailable，无宿主等待前台。凭据输入只在本地 VM，票据及 ToolReceipt 只记录 slot/配置状态，禁止值进入 transcript/log/SavedState。

<a id="capacity"></a>
## 10. 全量预算与界限

预算配置默认 context=32768、输出预留4096，范围依 ARCH#capacity；修改走同补丁验证。每请求先构造白名单结构→脱敏→按目标协议完整编码（当前全集只替换当前 context，不累计旧快照）→计算 UTF8 输入字节数；加1024协议余量、输出预留及 max(1024,ceil(输入估算×10%))。checked Long 溢出即超限。该估算保守且不宣称适配任何 tokenizer。

会话历史不裁，system/工具描述与所有 schema/用户及模型文字/全部工具结果均纳入。32KiB单用户消息、8MiB请求、1MiB响应；源端所有域累计≥8MiB或>20000行丢整份，主库在完整 state load 前 SQL 预检。source JSON 转义膨胀后仍需最终预算，不以源长度代替请求。响应有界读取到 EOF 前不解析执行。

超限零请求/零后续工具；已执行项保留回执、未执行 skipped。提示当前实际估算/限制、可新会话减历史；全集本身过大说明首版无法处理，不用摘要/截尾继续。服务端有界错误码识别 context 拒绝或413，终止本轮；普通400归协议/请求错误。UI 给出明确容量及保留提交状态。

<a id="protocol"></a>
## 11. 两协议与安全网络

新 ConversationClient 不调用既有 analyze 的分块/合并路径；保持独立请求历史，网络 helper 可复用现有 HTTPS 校验/取消/安全错误，但不顺带重写录音分析协议。后端端口输入为完整请求模型，输出仅 Final 或 ToolBatch/结构化 Failure。

OPENAI 使用 chat/completions：单 choice，assistant.tool_calls/role=tool/tool_call_id 完整配对；finish_reason=stop 且无工具为 Final，tool_calls 且非空合法批次为 ToolBatch，支持时 parallel_tool_calls=false。ANTHROPIC messages 保 system 独立、assistant text/tool_use 顺序，下一 user 先完整 tool_result（错误 is_error）；end_turn 无工具或 tool_use 合法批次。具体支持范围遵 ARCH#capacity 官方协议依据，不在本设计重定义。

StrictJson 全量解析，重复/缺失 ID、未知工具/额外参数、旧 function_call、拒绝、length/max_tokens/content_filter、未知停止原因、混合矛盾、半段 arguments 均整响应失败且零执行。收到合法工具也不能越过整批验证。无 thinking/server tools/流式/Responses；不兼容明确报错，不自动换协议或模型。

HTTPS 禁 userinfo/query/fragment、禁重定向，连接15秒读取90秒；一个 operation 跟踪所有 HttpURLConnection，取消断开。401/403认证、429限流、5xx/超时传输错误；错误正文仅有界解析代码，不回显、不记密钥/原 URL。测试用注入假连接与固定响应，禁止付费 API。

<a id="redaction"></a>
## 12. 密钥与出站白名单

凭据只存在原密文、局部本地输入、网络认证头和请求时短寿命连接对象；模型只读 keyConfigured。快照/工具/状态投影显式选字段，不反射 AiConnection/Archive 音频路径/DocumentRef。资源路径和可调用 URI 变显示名/状态/句柄，录音内容仅文本不发音频到沟通服务。

脱敏器在可外发的文本值叶子匹配两 slot 实值、现有转义形式和常见 URL 编码，替换为 [redacted] 并计数；空 key 不匹配，替换标记不递归。序列化器拥有固定 JSON 结构/枚举/schema，不能全局替换编码后的 JSON 或改字段名，短 key 也不能破坏工具契约。编码前后对数据值做泄露哨兵检查；固定协议结构不作为自由文本重写。无法安全编码时阻断请求。

用户输入命中密钥时脱敏并提示使用本地设置；模型回复、工具参数/结果、诊断、自由文本均走相同数据出口。认证头由客户端局部构造，不写日志；网关未知内容不回显。换 key 立即取消链并清旧匹配历史/待发编码，本地 UI 回执仍可显示“凭据已更新”。业务数据/转写/诊断作为数据而非 system，不能增加工具或授予权限。

<a id="ui"></a>
## 13. 页面与交互

UiDestination 增 Conversation；tabs 按首页/档案/沟通/设置四项，图标按目的地选择，宽窄共用同列表/testTag。SettingsPage 增 AI，RECORDING 仍有效；Overview/SettingsRoute 更新入口及穷尽 when。录音页面仅开关/自动分析/采集/系统目录，AI 页面为分析/转写连接、凭据状态/本地编辑、测试/复制和预算。

ConversationScreen 显示目标告知（全文文本/host/model/无音频）、消息、捕获/请求/等待前台/执行状态、真实回执、错误、停止及新会话。busy 禁连续发送；模型自然语言与操作回执分开显示。若模型称成功而真实 API 失败，以本地失败回执为准。FAILED/容量/连接改变后明确已提交/未执行项。

ConversationViewModel 只持内存输入及收集 application 状态，不用 SavedState 存全集/历史/凭据。Application scope 保旋转，host 活跃状态独立。两页草稿保脏字段并显示外部更新冲突；本地凭据表单保存/取消清输入。现有归档/报表/通知深链、我的职位/外观/小组件及历史工作日体验保持。

<a id="errors"></a>
## 14. 失败与兼容边界

ApiError 增 CONFLICT（对应所有 UI when 更新），原错误键及 requiredCapabilities 保留。错误展示映射仅在 UI；新 snapshot_domain_failed/context_budget_exceeded/provider_context_rejected/protocol_unsupported/invalid_tool_response/action.* 等键遵 ARCH#errors。任何读取/解密坏、版本冲突、系统拒绝/取消、服务端拒绝都不能返回空集或成功。调度排队和 launch 不当业务完成。

原 namespace、Keystore alias、analysis/transcription JSON 和独立录音库结构不搬迁；预算为可选 versioned 子对象，旧 fixture 缺省读。负 ID/links/generation/CAS/删除标记保留，不迁正文到主库。旧程序降级可能丢新预算，沿用架构边界；任何不可逆格式变更须另报，不借实现自行扩大范围。

<a id="verification"></a>
## 15. 实现验收设计

|族|必须证明的结果与测试落点|
|---|---|
|V01|app AndroidRecordingStore/AiSettingsService 测旧密文两 key、交错页保存/冲突、Keep/Replace/Clear、完整验证、失败保留和复制；断言解密后字段而非仅返回文字|
|V02|backend 全量投影＋app SQL/录音/目录/诊断源测试：>200、跨年、非规则号、hidden/links/负 ID、全文尾哨兵、完整审计/历史/残留绑定；空与失败区别、500/501节点、深度4/5、两遍变化及3次失败|
|V03|注册表72项参数化路由核对实际共用 API；每项有有效/无效、返回状态及写通知断言，覆盖 IN_USE、时区、CSV、媒体和仅自述采纳，不用统一假成功掩盖|
|V04|可控 barrier/latch 驱动 UI↔AI/后台CAS、提交前版本变化、import→restore/delete、脏草稿和慢订阅；网络挂起时来电短写可完成，锁序无反入|
|V05|backend 假 client/clock/store 测整批非法零执行、串行/失败补齐、重复同参缓存异参拒绝、UNKNOWN不重放、stop前后提交、busy/generation、已排队状态|
|V06|app 假 HTTP 捕获两协议完整请求/回执配对，截断/拒绝/工具不支持/context错误；字节/行/溢出边界、完整schema预算、超限零HTTP零工具、工具后超限保账本|
|V07|backend票据＋ui生命周期/ActivityResult＋app probe测试 grant/deny/cancel/expired/noHost、重复迟到/旋转/死亡、伪URI/错误句柄/目标换代、MICROPHONE与pin/overlay真实状态|
|V08|两 slot 密钥哨兵放正文/工具/诊断/模型/网关与编码变体，检查请求/历史/结果/日志/持久偏好无实值（认证头例外）；短key不破schema、换key清理，无SavedState落盘|
|V09|ui JVM/共享Compose及需要的设备测试：宽窄四入口、旧深链、两设置/草稿/冲突、全部会话阶段/超限/停止、旋转切页/进程新会话与前台宿主|

执行后跑 `python tools/check_backend_boundaries.py` 及 `.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`；只依据实际退出码/XML/报告登记，失败保成果并修复或定向报告。源码 API/合同一致、JVM/UI依赖与展示文案边界必检。验收文档逐项记录 A1–A6、72 操作与 V01–09 证据，不以编译代替行为测试。

设备仅隔离 backendQa 包，不覆盖生产安装或清数据；权限/角色/SAF/tree/CSV/widget/overlay实机结果与 JVM 替身证据分别登记。有设备则验证，无设备明确列未测；真实付费服务、SIM/ROM后台/通话双方录音效果独立列边界，不伪称已验证。README 同实现更新四入口、两设置、外发全文/进程会话/容量与模型兼容。

<a id="tradeoffs"></a>
## 16. 取舍与阶段出口

选原存储内页补丁而非拆密文/整份覆盖：兼容成本小，需接脏字段冲突；选全局短发布栅栏而非只锁会话：接入生产写较多，但能证明版本与提交一致；选显式72项类型路由而非反射/自由代码：代码量较大，换取原校验、确定schema和可穷举证据。全量/非流式/单进程会话与预算保守拒绝均是已批首版限制，未经新批准不换检索、持久聊天或新协议。

本次仅新增本文，不修改产品/合同/实施计划；设计批准后计划按组件依赖列具体文件、测试、失败保留策略与提交边界。后续若发现真实契约冲突，保留成果并按稳定 QID 定向请主负责人裁定，禁止子 Agent。R1–R6/A1–A6 全部交付范围不因耗时或上下文削减。
