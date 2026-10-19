# AI 沟通按需加载与可靠性整改架构

2026-10-19（Asia/Shanghai），ai-v2-architecture / epoch3。本文待审定，仅改本文件；不代表产品验收通过。

<a id="requirements"></a>
## 1. 需求、依据与批准边界

适用R1–R10/A1–A10；当前R5替代旧全量默认，完整业务可达不等于每轮全发。负责人已确认AI-V2-DISCUSSION r1/Q01–Q20，无未决，digest：`f3b79f0407c801828466ab4ed113f36244f4c5a642c84cbfa0c8439c4e993f62`。本轮新增协议证据细化 Q10，不改变其余结论。

基线commit：`b4097378ed7d6f75ac147e21e0c33319c1ddc74b`。路径缩写B=`backend/src/main/kotlin/com/greatwall/callring/application`，P=`app/src/main/kotlin/com/greatwall/callring`，T=`app/src/test/kotlin/com/greatwall/callring`，均指该提交；下列blob已核对。

|证据|路径/来源|用途|
|---|---|---|
|E1|B/conversation/ConversationService.kt#runTurn；`d7ab1ad72c2cc6f501631cb8351c1a1dccbf5703`|全快照/72schema/8上限/账本|
|E2|P/platform/ai/CompatibleConversationClient.kt#decode；`94c051360560999e4a601298f9a9a2a6173b7c53`|null判断/错误折叠|
|E3|B/conversation/ToolRegistry.kt；`1971ae2403f5febf24539866a50c67bc25f32f8e`|72项原工具|
|E4|B/BusinessRevisionCoordinator.kt#check；`62bbad0a488cebae1a9ac6a84fcf0cad476375f0`|全向量/fence|
|E5|B/CoreSnapshotProjection.kt；P/bootstrap/CompleteSnapshotAssembly.kt|21域/统计|
|E6|P/platform/ai/AiSettingsService.kt；B/api/AiSettingsApi.kt；B/conversation/ForegroundActionService.kt|patch/票据|
|E7|docs/plans/AI沟通验收.md#epoch9-verification；T/platform/ai/ToolBusinessIntegrationTest.kt|旧72业务/确认，非真模型|
|E8|任务epoch1/3负责人诊断|10失败、8+2成功、colorMode成功；临时QwenNullFieldDiagnosticTest 2项复现，非真Qwen|
|E9|[Function Calling](https://help.aliyun.com/en/model-studio/qwen-function-calling)、[Chat兼容](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions/)、[模型能力](https://help.aliyun.com/zh/model-studio/qwen3-8-flash)|合法null/思考历史|
|E10|§8固定tag/Maven元数据|平台/依赖，兼容构建待测|

|需求/验收|架构覆盖|实施后必需证据|
|---|---|---|
|R1/A1|§2、§6：独立页面及patch，保密钥/旧值|跨页交错保存、字段冲突、旧配置回归|
|R2/A2|§2：现有应用级对话、四导航|窄/宽屏四入口与动态状态/错误/回执|
|R3/A3|§3：D01–D21完整来源、全文与安全视图|源级分页、全文尾哨兵、缺失错误、无密钥|
|R4/A4|§3.4、§4：原72业务与读回、变更订阅|逐项业务覆盖、校验一致、双向UI刷新|
|R5/A5|§3、§5：发现→相关查询、聚合/游标、历史容量|普通聊天0读取、完整性清单、实际字节/轮次|
|R6/A6|§7：前台一次性授权及权威目标|真实许可/拒绝/取消/过期/迟到/重复结果|
|R7/A7|§2、§8.2：精简UI、原生Markdown|兼容构建、渲染/触达/无障碍、动态错误|
|R8/A8|§3–§7：分层错误、去重、修复、范围核验|10项/8+2、部分成功、错误恢复与冲突矩阵|
|R9/A9|§9：生产链路真实Qwen+真机|两条原始指令、实际存储与UI、脱敏指标|
|R10/A10|§8.1：修轻量循环与隔离框架替代|保留实测依据；切框架须构建/差量/同任务证据|

<a id="components"></a>
## 2. 组件、责任与UI

整改现有轻量编排：UI依赖application.api，纯JVM backend管业务/编排，app适配HTTP/SQLite/加密/系统宿主。保ConversationApi.sendTo/stop/newSession与状态流，禁止AI旁路写库。

|组件/归属|输入→输出|责任与禁止事项|
|---|---|---|
|ConversationViewModel/Screen，ui|用户输入、ConversationState→UI事件|不读取全集、不持密钥、不解释模型文字为提交事实|
|ConversationService，backend|用户轮次→状态/模型请求/步骤账本|代次/取消、发现、规划、受限恢复、执行预算与完成判定|
|CapabilityCatalog，backend|discover_tools→目录/schema页|静态元数据，无业务I/O；稳定O编号、语义、字段、授权和核验描述|
|AiQueryApi/QueryService，backend|有类型查询→带范围的页/详情/聚合/文本块|共用业务口径；不调用captureComplete，不暴露任意SQL或文件路径|
|ToolExecutor+StepLedger，backend|已校验步骤→业务回执及核验|复用commandApi与原API；不在协议层进行业务校验|
|ProviderProfile/ConversationClient，app，端口在backend|WireRequest↔DecodedReply/TransportError|HTTP/协议与供应商适配，不访问业务存储或做业务成功判断|
|ContextAssembler，backend|历史/引用/活动成对消息→有界请求|确定性压缩、完整性/字节检查；不额外调用分类/摘要模型|
|现有业务API/存储+RevisionCoordinator|原命令→保存回执/变化事件|唯一校验/保存入口；资源范围检查与原子发布|
|ForegroundActionService+Activity宿主|一次性票据↔真实系统结果|权限/角色/文件/目录/凭据/确认；不接受聊天文本伪造许可|

导航保留首页/档案/AI/设置，窄屏底栏、宽屏侧栏；录音与AI设置分开。移除静态说明与身份标签，新对话用图标；用户右侧主题色气泡、AI左侧无背景。发送图标置输入框右下，约一字视觉/48dp触达区/contentDescription，适配键盘/大字体。动态显示禁用/停止/等待/错误/回执；未配置时给设置入口。工具前文显示动态待核验，不能呈伪成功；新对话确认/停止保留提交事实。

AI正文用原生Markdown，用户输入纯文本，工具安全回执按需展开。链接只经用户点击受控打开，不自动下载图片/执行HTML脚本；不展示思考正文。

### 2.1 协议端口

将ConversationRequest中强制snapshot改为ContextEnvelope：system规则、可见历史、已启用工具schema、当前任务相关有来源数据；无业务工具时无snapshot占位、无后台快照。WireAssistant与UI ConversationMessage分离：协议必需的toolCalls/blocks及受控扩展不混入可见正文。DecodedReply只区分Final/ToolBatch及finishReason/usage/安全诊断；业务registry在编排层使用。保留Anthropic配对与系统工具边界，修OpenAI兼容不偷换全部连接的协议。

<a id="io_errors"></a>
## 3. 输入输出、按需读取与错误契约

### 3.1 发现与查询

每轮仅启用`discover_tools`，静态规则只列类别/发现指引。请求启动仅读必要连接配置/正常密钥链，不计业务query，禁止顺带读其他配置/运行状态。一次正常模型请求直接聊天或发现，无额外分类请求/关键词分类。发现参数category/exactNames/query/cursor可选、limit=1..12默认8；输出稳定ID、匹配解释、摘要/schema、nextCursor/complete/catalogVersion。exactNames精确定位，空query遍历全部类别；未命中可浏览目录。静态检索业务读取0。

发现schema自下一请求启用；启用集仅含discover、当前计划和活动配对所需定义，封存后释放。未启用工具返回TOOL_NOT_ENABLED及发现方式，不执行；新轮次不继承全部schema。

查询端口：

```text
AiQueryApi.query(QuerySpec): ApiResult<ScopedPage<SafeRow>>
AiQueryApi.get(Domain, EntityKey, Projection): ApiResult<ScopedValue>
AiQueryApi.aggregate(AggregateSpec): ApiResult<ScopedAggregate>
AiQueryApi.readText(TextRef, cursor?, maxChars): ApiResult<TextChunk>
QuerySpec = domain + projection + typedFilter + fixedOrder + cursor? + limit + maxBytes
SourceStamp = domain + sourceVersion + observedAt + sourceScope + consistency
ScopedPage = items + stamp + matchedTotal + returnedCount + nextCursor? + complete
TextChunk = ref + stamp + codePointStart/End + totalCodePoints + nextCursor? + complete + text
```

`data_query/data_get/data_aggregate/data_readText`经发现按domain投影启用，不接受任意domain/字段表达式。filter仅允许域内实体键/文本/时间/状态；缺省all_stored，无隐式UI日期限制。ISO日期带zone、epoch毫秒、ID为Long。limit默认50范围1..200，maxBytes默认32KiB上限128KiB；文本默认4096上限16384码点。单行超限给TextRef或ITEM_TOO_LARGE，禁止删字段；详情缺失NOT_FOUND。

现有SqliteBackendStore.read会StorageSession.load全集，不能包装read/captureFull后切片。新增ScopedReadPort，Android源级参数化投影/keyset排序，同读事务内count与页，计算共用纯业务规则。录音不先all()，SAF不先systemRecordings()全集；SAF不能seek则有界遍历+取消点/进度游标，未扫完PARTIAL、总量未知。全文只读目标记录/字段，流式/分块；旧归档payload目标记录解码，列表不附全部转写。源不支持目标投影/精确聚合报UNAVAILABLE，不回退全库快照。

游标是进程内不透明令牌，绑定domain/filter摘要/projection/order/源版本/最后键/期限；无敏感值入日志。分页前核版本，变化CURSOR_STALE；终页next为空且complete=true，空页也带总量/stamp。缺页/尾界/过滤不符不能拼全集。跨库无统一事务：持久源snapshot，运行live_probe，均带采集时间；all_stored不等于系统未同步数据。

### 3.2 全业务文本来源清单

|域|来源/投影与范围|聚合与完整性|
|---|---|---|
|D01 档案|profiles∪已有calls号码；号码/名称/单位/职位/白名单|不受UI搜索页限制；匹配档案数，已存在号码集合可按批精确查|
|D02 职位/本人职位|positions及myPositionId|ID与引用一致；全选项分页|
|D03 全部存储通话|calls，包括统计未开启/不可计分记录|原始条数、方向/时长等允许聚合|
|D04 可见/计分通话|共用visible/统计区间规则|必须标VISIBLE，不能等同D03|
|D05 统计|statisticsIntervals及共用score/reportTotals/quality|权威总量/分组，含明确range/zone/规则版本；不对当前页相加|
|D06 排班|current/history及工作状态|history分页；当前状态标时间|
|D07 日历|year/source/version、节假日/补班日|年份/日期过滤，无隐式只本年|
|D08 日期覆盖|overrides|完整日期范围可遍历|
|D09 请假|leaves、leaveVersion|状态计算带observedAt；备注可全文分块|
|D10 业务配置|score/quiet/lastQuietMode/statisticsEnabled/overlayEnabled|安全完整字段，不取无关通话|
|D11 外观|PreferenceStore外观视图|实际持久化值，不取主题猜测值|
|D12 铃声|安全设置、实际播放状态|不输出私有路径；状态是即时观测|
|D13 录音配置|AiSettingsApi.getRecordingView|folderConfigured/displayName/access；无URI/私有根路径|
|D14 AI配置|AiSettingsApi.getAiView及兼容配置|仅keyConfigured；预算/协议/URL/model按安全视图|
|D15 录音事件/关联/删除标记|recording_events/links/deleted|三类分别分页，保留负callId等原身份语义|
|D16 归档/转写/分析|archives元数据、指定全文字段与TextRef|覆盖transcript及analysis全部字段/列表，尾界完整可证明|
|D17 已授权目录|真实provider有界枚举、会话handle|未配置/撤权/查询失败/空目录分开；不输出URI|
|D18 小组件|持久绑定及已安装观测|两者区分，不能把绑定当实际安装成功|
|D19 运行|runtime权限/角色/电话/播放/队列状态|live_probe；未知/拒绝如实返回|
|D20 诊断文本|实际存储诊断安全投影|按时间/类别分页；凭据/敏感值先移除|
|D21 审计文本|calendar_audit等原完整业务文本|类型/时间分页及指定原文分片；不吞掉旧记录|

状态为COMPLETE、EMPTY、PARTIAL、NOT_CONFIGURED、UNAVAILABLE、DENIED；正常空只在已成功访问源后返回。matchedTotal未知用null加明确原因，不能写0。报告全集必须列明所需domains、过滤、版本、已收集/总量、缺失页及complete；任一必需域未完成就不能声称完整读取。

aggregate允许count、sumDuration、业务reportTotals/score及经声明的固定groupBy；不接受模型SQL。聚合遍历所有匹配记录（源级流式或SQL与共享规则），不是UI当前页；无法在时限/容量内完成返回AGGREGATE_INCOMPLETE和范围，不以估计替权威总数。按需只是减少无关读取，不能改原统计业务规则。

### 3.3 错误与协议行为

错误统一为 `AiFailure{kind,code,retryable,httpStatus?,providerCode?,fieldPath?,scope?,counts}`，映射现有ApiError并扩展有类型细节。kind与UI文案一一对应；不把任意Exception归为“协议不支持”。

|层/代码族|识别与处理|
|---|---|
|TRANSPORT/TIMEOUT|网络/编码/超时分别诊断；自动网络重试本版为0，用户可重新发送；不重放已启动写入|
|HTTP_REJECTED/AUTH/RATE/CONTEXT|保HTTP状态；400解析白名单error.code/type；非JSON网关400仍是HTTP；401/403、429、413及已知context码单列|
|PROTOCOL_JSON/SHAPE/STOP|严格JSON；期望assistant及现代tool_calls；不能读取的必要结构、未知停止原因单列|
|OUTPUT_TRUNCATED/RESPONSE_TOO_LARGE|finish_reason=length/max_tokens或字节超限；本次不完整批次零执行，旧已提交项保留；不自动升output|
|TOOL_COUNT/ID|完整reply最多32、ID非空且唯一；超过硬限/重复ID在任何新执行前拒绝，区别参数错误|
|UNKNOWN_TOOL/NOT_ENABLED|不执行；安全名称建议/发现入口，受恢复预算限制|
|ARGUMENT_JSON/SCHEMA/DOMAIN_VALIDATION|参数JSON/类型/字段/枚举与业务校验分开；可恢复者回送安全字段错误和schema|
|CONFLICT/CURSOR_STALE/NOT_FOUND|指明被影响范围；不自动重试写入；重新读取/重新授权需新有效依据|
|COMMITTED_UNVERIFIED/UNKNOWN|分别表示已提交但核验失败、提交是否发生不明；不重放、不显示verified|

OpenAI合法null规则：`function_call`缺省或null均接受；非null旧式function_call明确LEGACY_FUNCTION_CALL_UNSUPPORTED，不和现代tool_calls混用。tool_calls缺省/null/空数组表示无调用；Final需finish_reason=stop及可见非空正文；调用需finish_reason=tool_calls且结构完整，content可null。refusal缺省/null正常，非空拒绝单列且不执行；audio等未知可选null字段忽略，关键字段类型错不能宽松吞掉。解码不实例化ToolRegistry，不因darkMode错误把完整回复丢掉。

本版Qwen策略是FAST，直接HTTP请求体顶层发 `enable_thinking:false,preserve_thinking:false`，保持max_tokens=当前output（缺省4096）。ProviderProfileResolver只对OPENAI+已维护的阿里官方HTTPS endpoint主机清单+qwen3.8-flash及明确支持快照启用；必须校验主机边界，不能用contains("aliyun")。自定义代理默认为标准OpenAI；AI设置增加高级兼容方式AUTO/STANDARD/QWEN_FAST（默认AUTO），自定义endpoint仅显式选QWEN_FAST才加这两个字段，且模型必须匹配支持清单。兼容方式是非密钥的会话配置，测试/patch/指纹/复制均包含；不能向所有OpenAI服务盲加供应商字段。其存储与patch细节见§6。

FAST的所有工具往返使用同一profile，历史不含思考字段；若服务仍返回非空reasoning_content，报PROVIDER_MODE_MISMATCH并在执行该回复工具前停止，保留旧回执，不默默忽略或拼入content。缺省/null/空reasoning_content接受。STANDARD未宣告思考历史策略而收到非空思考时同样停止并提示选受支持profile，不推测模型支持程度。

本版不开放QWEN_PRESERVE思考模式。将来启用须独立profile与验证：WireAssistant私有字段逐字保留所有需要回传的reasoning_content，独立字段回传、计入真实字节/服务token预算，不放UI/日志/普通摘要；不能压缩该profile仍依赖的历史，容量不足就终止或开启全新会话。配置/连接/profile变更取消活动轮次、失效票据、清除旧wire历史。禁止把“忽略思考字段”泛化为Qwen多轮策略。官方默认preserve_thinking=true且规定完整回传，也说明历史本无该字段时开启并不报错；因此遗漏是兼容风险，尚不能认定用户第二轮400的原因。[E9]

### 3.4 原操作覆盖与核验映射

保留O01–O72原编号/名称；E3是完整名称/字段表。每项增加用途/字段单位范围枚举/可空缺省/副作用/依赖授权/核验描述，目录↔执行器↔API↔核验器机器检查全集。

|编号|共用业务接口→核验|
|---|---|
|01–06|checkNumber、home.load、callers.search/getEditor/getOverview/getLatestCall→原校验/实体/范围观测；增getCallDetail入口|
|07–08|saveProfile/setWhitelisted→按号码读回字段|
|09–10|reports.load/exportCsv→权威总计/真实ExportReceipt|
|11–17|summary、统计/悬浮窗、免打扰读改启停→对应配置；权限仍系统|
|18–21|职位读存删/本人职位→ID/字段/引用，删后NOT_FOUND|
|22–24|排班读存重置→有效版本/weekly/makeup/zone|
|25–29|日历读同步导入/日期覆盖存删→来源/年份/版本/日期|
|30–32|请假读存删→ID/区间/备注或删除证明|
|33–35|评分读存重置→实际权重/窗口|
|36–37|外观读/UiEdits.appearance→getAppearance字段；主题另验UI|
|38–43|铃声读启用导入恢复播放停止→设置/媒体回执/实际播放或LAUNCHED|
|44–46|状态/诊断/请求同步→真实观测，同步仅QUEUED|
|47–49|widget绑定读存删→持久值，非pin成功|
|50–53|归档列表/录音设置/combined保存/测试→安全视图/真实测试，付费仍确认|
|54–59|归档读导入重试删除播放停止→getArchive+generation/来源；retry仅QUEUED|
|60–63|目录选择/文件枚举/widget列表/采纳→真实目录/handle/绑定/采纳字段|
|64–66|AI读patch/凭据编辑→安全视图/仅keyConfigured|
|67–71|权限/应用设置/pin/悬浮窗/预览→真实前台回执，LAUNCHED不报完成|
|72|复制连接→确认源目标槽/安全配置读回|

旧O03/09/50/61大列表AI返回升级ScopedPage，不能绕过按需；旧UI API不变。新增query/全文/getCallDetail/task_prepare/callers_saveProfiles用扩展ID，不挤占O编号。

<a id="state"></a>
## 4. 状态归属、批量任务与一致性

ConversationService拥有session/generation、TurnContext、StepLedger、活动模型client及取消句柄；业务持久状态仍归原存储；UI仅订阅投影。状态为IDLE→PREPARING→REQUESTING→VALIDATING→DISCOVERING/READING/EXECUTING/REPAIRING/WAITING_FOREGROUND→REQUESTING，终态COMPLETED/PARTIAL/FAILED/STOPPED；旧phase映射更新可见标签，不再称所有任务“完整读取数据”。

写前建立本地TaskPlan：单项expectedCount=1；多项通过发现后的`task_prepare`声明kind/原userMessageRef/expectedCount/枚举目标谓词，编排签发taskId。CREATE_PROFILES核验唯一合法新号码/数量/字段，SET_APPEARANCE核验目标字段。计划不授新权，建立后不能降低完成标准；语义仍由模型理解，真实验收独立对照原文，不能以计划自证正确。

`callers_saveProfiles(taskId,items)`上限32，号码与字段由模型生成。先全部预检schema/号码/唯一性并精确查询现有号码；CREATE不得覆盖既有档案，逐项复用callers.saveProfile与原验证。普通档案patch仍可编辑现有实体。一次10条普通调用也走此计划器；多项写缺计划返回TASK_PLAN_REQUIRED且零写，由同一模型补齐。单批1..32，内部分8项顺序窗口，每项检查取消/依赖，不额外请求模型重新生成已接受的8+2。

StepLedger进程内以taskId/本地stepId为键，索引工具+规范化目标/参数摘要、toolCallId。PREPARED→STARTED→COMMITTED→VERIFIED，旁路FAILED/CANCELLED/DENIED/SKIPPED/UNKNOWN；读回失败COMMITTED_UNVERIFIED。同ID改参拒绝，换ID重复已提交语义步骤复用回执。修复绑定未启动step更新摘要，已提交不可改。再次副作用须新用户意图/步骤；摘要不入日志。

先整批校验协议/数量/ID，再预检全部schema；无效项不启动。依赖为业务声明与模型附加边并集，模型不能删除。仅已证明独立的合法项可继续，未知依赖阻塞。业务失败保留已提交项，依赖项SKIPPED+blockingStep；报告已核验/提交未核验/失败/待执行数，不伪称全批回滚。

可恢复schema/无副作用字段错误按正确tool_call_id反馈同一模型：字段路径/白名单码/类型枚举/安全示例（colorMode=DARK），不含错误实值。每step修复<=2、全轮<=4，未知/未启用工具重新发现也占恢复预算；所有尝试占执行预算。不自动改名darkMode，不用脚本生成操作；拒绝/取消/冲突/提交未知不进入修复，不能扩大目标/绕确认。

保存回执+持久化字段读回成功才VERIFIED；幂等无操作报告已是目标状态，不造commit。扩展ToolReceipt.verification而保留QUEUED/LAUNCHED，异步任务后续观察才称完成。Final只结束模型文字，不覆盖账本；计划未达标时PARTIAL/FAILED+剩余步骤，可有一次受预算约束的模型补齐，仍失败准确停止。停止/超限保留真实回执。

同进程继续仅针对显式taskRef的未启动项，未知提交不重放。进程退出清聊天/计划/票据，无跨进程自动恢复/exactly-once；重开读当前状态建新计划，不以同值证明上次提交。宿主测试保留脱敏证据。

### 4.1 依赖范围与发布

CommandContext新增非空expectedScopes（业务范围版本/字段基线），保operationId/generation/active/mayCommit。Coordinator同一fence内检查/发布/记提交，网络/SAF/系统等待/长查询在锁外。RevisionVector保观察/旧API；渐进链路移除全向量相等及任何变更就整轮冲突的条件。

业务接口产生依赖，不能让模型传expectedVersion/删scope。范围来自读目标的源stamp及字段基线；提交前若同目标被其他操作修改必须失败，不能临提交才无条件抓最新版“消除冲突”。自身已提交项仅更新本计划对应scope的基线；外部变化仍须重新读取/解释。

|操作/观测|必需依赖范围|无关变化不拒绝示例|
|---|---|---|
|档案保存/白名单|profile:number的实际变动字段、引用position:id的存在/active/规则；CREATE须号码不存在基线|其他号码/录音/主题|
|职位删除/本人职位|position:id及myPosition引用、profile对该职位引用集合版本|无该职位引用的其他实体|
|外观|appearance被patch字段与selection/seed等交叉校验字段|通话/归档/未改的独立外观字段|
|录音/AI设置|recording被改字段；ai槽URL/model/protocol/密钥revision、budget/profile各自范围|其他槽/录音目录的独立字段|
|导入/删除/采纳/重试|callId/recording generation、归档身份/分析、被写档案字段；目录handle绑定scope|其他归档/其他号码|
|排班/日历/请假/评分/免打扰|目标资源及业务规则所依赖的排班/日历/请假/统计区间聚合版本|铃声/外观|
|报表/总计|匹配通话集合、统计区间、排班/日历/请假/评分口径版本|AI/外观配置|
|小组件/铃声/前台动作|widget:id或ringtone版本；真实权限/角色/票据与权威确认目标|无关业务事件|

扩展UiEdits/patch字段基线；UI/AI/后台/录音共同发布实体与聚合版本。旧未声明命令保守查全向量，新渐进写必须映射非空scope。feed可宽范围通知，但不作全局冲突依据；UI活跃页重取最新值，AI后续也读真实新值。

## 5. 历史与容量

UI消息、结果引用、wire历史分离。活动assistant/tool严格配对；部分执行后取消/错误也为全部已接受callId补真实或未执行结果，不留悬空调用。UI保真实回执，网络仅当前任务必需内容。

完成轮次确定性摘要含意图引用、工具/operationId/状态、核验计数、未完成项和来源版本/TextRef；不额外请求模型摘要。旧大结果不反复发，摘要不当新鲜真值，需要时重读；普通闲聊无后台刷新。UI最多100个已结束轮次或2MiB可见文本，显示被归档范围；摘要/引用亦有界，活动/未决项不裁剪，装不下报CAPACITY_EXCEEDED。

预算：reply工具<=32、轮次工作项<=64、模型请求<=16、step修复<=2/全轮<=4。发现/查询/核验/批量每项都占工作配额，批量不按1项逃逸；本地读回不增模型请求。10档案预留10写+10核验；8只是执行窗口。启动前预留回执/读回容量，不足不执行，提交后核验异常仍保COMMITTED_UNVERIFIED。

缺省context=49152/output=4096与既有显式值不变。保8MiB请求/1MiB响应硬限；字节保守估算不冒称精确token，usage有才记。按最终UTF8请求体检查；必需数据/schema/活动历史装不下明确缺失范围/已完成数/剩余步骤，不静默删页/截全文/调预算。仅完成轮次摘要可释放旧数据，协议必需内容不裁剪。

<a id="compatibility"></a>
## 6. 兼容与持久化

保原加密RecordingSettings/密钥/SAF授权/业务ID与规则。录音页patchRecording，AI页patchAi，未改Keep，从最新值合并，只同字段冲突；空凭据不清key，Replace/Clear仍本地显式。O52 patchCombined保持合并；既有显式/缺省预算迁移不变。

新增conversationCompatibility=AUTO/STANDARD/QWEN_FAST，旧配置缺省AUTO，仅作用analysis会话；纳入加密序列化、AiSettingsView/Patch与安全schema。按字段基线保存；复制分析profile须确认，转写槽仍OpenAI约束。fingerprint含实际profile；使用中的analysis连接/密钥/profile变化取消轮次/票据/旧wire，其他配置不取消。sendTo核对已渲染的完整安全目标/profile，禁止向新目标发送旧历史。

AiConnectionView新增默认兼容的resolvedProfile，sendTo与渲染目标同值核对。query/receipt envelope用schemaVersion=2，旧UI返回兼容；AI分页不冒全集。客户端去强制snapshot，旧AiSnapshotApi与21域诊断保留，更新默认全量断言并保安全回归。

<a id="authorization"></a>
## 7. 授权、敏感数据与诊断

所有系统动作仍由真实前台宿主执行：permissions、roles、应用设置、SAF文件/目录、CSV目标、pin/overlay、凭据、连接复制/付费测试和现有删除/采纳确认。票据继续绑定requestId/session/turn/generation/宿主lease/期限及一次launch/deliver；合法结果再查票据与依赖，拒绝/取消/不可用/未知/过期/陈旧单列。停止、连接变更、新会话、宿主失效均使旧票据不能执行，重复/迟到结果不得重放；没有系统真实结果不能说已授权。仅缺宿主等待，不自动重复弹框。权威确认目标来自本地业务数据，模型文字/文件/转写/检索结果均是数据，不是许可。

安全投影仅keyConfigured，保两槽redactor；凭据只经应用进程正常认证链，不导出/直接解密设备文件/打印key。目录仅displayName/access/handle，无URI/私有路径；聊天无音频，文本仅相关范围。用户文本含key也脱敏，不能误删协议结构。

诊断白名单：版本/Git/协议/model/profile、随机trace ID、HTTP/枚举providerCode/finishReason、工具名/字段名、执行/核验计数、UTF8字节、usage(缺失null)、阶段/总耗时、枚举源范围/错误类型。禁止headers、body、异常自由文本、思考、号码/姓名/转写/参数实值及可枚举哈希入日志/导出；provider自由文本仅进程内分类。UI业务详情不当诊断导出。

错误文案按账本counts生成，如“HTTP400，已核验8项、2项未完成”；不能统一称本批零执行。模型“完成了”不能改变本地核验状态。

<a id="tradeoffs"></a>
## 8. 依赖、取舍与演进条件

### 8.1 轻量循环与最小Koog

选择修现有循环，Agent依赖增量0。E1/E2定位本地8上限/null判断/错误折叠，E8的8+2及正确字段可经真实业务保存；尚无真实Qwen失败诊断。讨论轮ConversationServiceTest 8项、ConversationProtocolTest 6项通过只证明旧基线。框架仍需实现发现/范围/授权/去重/读回，不自动保证可靠性。

[Koog1.3.0构建](https://github.com/JetBrains/koog/blob/1.3.0/agents/agents-core/build.gradle.kts)与[平台约定](https://github.com/JetBrains/koog/blob/1.3.0/convention-plugin-ai/src/main/kotlin/ai.kotlin.multiplatform.gradle.kts)确认Android/minSdk23/compileSdk36/Java17。项目JDK17/Kotlin2.3.21/coroutines1.10.2符合声明，JVM目标1.8、D8/反糖化/API29运行仍待测，不能称Android不支持。

[最小agents-core元数据](https://repo.maven.apache.org/maven2/ai/koog/agents-core-android/1.3.0/agents-core-android-1.3.0.module)：AAR2242790字节，17直接API依赖（9 Koog、IO/serialization、5 Ktor含server SSE/CIO、stdlib），运行另有jdk9协程/Jackson/logging；非最终APK增量。现有HttpURLConnection/自有JSON无需这些新增依赖，不为品牌重做。

只有真实诊断表明持续编排/恢复维护问题且局部修复仍不达A8/A9时评估切换；先排除协议缺陷。Koog须独立AgentEngine适配相同ConversationClient/执行端口，保业务API/前台边界；提供最小依赖树、编译/D8、API29及当前设备运行、APK/方法数差量、相同两条真实任务请求/耗时/回执对照，经主负责人审定才接入，不能写未测性能胜出。

### 8.2 Markdown

固定首个兼容候选 `com.mikepenz:multiplatform-markdown-renderer:0.40.2` + `-m3:0.40.2`，不使用develop动态版本，不接图片/语法高亮/网络插件。[主仓](https://github.com/mikepenz/multiplatform-markdown-renderer)约1.1k stars且有持续更新；星数仅维护线索，非质量保证。[固定发布](https://github.com/mikepenz/multiplatform-markdown-renderer/releases/tag/v0.40.2)声明Compose1.10.3/Kotlin2.3.20；[core元数据](https://repo.maven.apache.org/maven2/com/mikepenz/multiplatform-markdown-renderer-android/0.40.2/multiplatform-markdown-renderer-android-0.40.2.module)列markdown0.7.3、immutable0.4.0、stdlib2.3.20，core AAR380849字节，[m3](https://repo.maven.apache.org/maven2/com/mikepenz/multiplatform-markdown-renderer-m3-android/0.40.2/multiplatform-markdown-renderer-m3-android-0.40.2.module)37979字节。项目Compose BOM2025.12.01与候选API/二进制兼容尚未构建证实。

实施必须先用该固定组合进行resolve、dependencyInsight、UI编译及Markdown smoke运行；记录实际选中Compose版本、增量依赖/APK大小。若失败，提交失败证据与最小兼容版本/局部Compose对齐方案给主负责人，批准后再变更，禁止自制解析器或静默跳过Markdown。真实浅深色/长文/表格/代码/链接/字号回归之后才能完成A7。

<a id="verification"></a>
## 9. 验证、真实验收与交付

### 9.1 实施后的必要自动验证

|编号|检查与通过标准|
|---|---|
|V1|设置交错保存、密钥Keep/Replace/Clear、旧预算/profile缺省、同字段冲突、连接变更取消/目标告知|
|V2|窄宽导航与精简布局；图标48dp/描述、大字体/键盘；Markdown标题/列表/链接/代码/表格/中文长文及双主题|
|V3|普通聊天captureComplete/业务query/运行探测均0、仅正常模型请求、schema仅discover；发现目录遍历全部72+扩展能力|
|V4|D01–D21跨页/日期/全文尾哨兵、稳定排序/游标失效/源失败/空/撤权；匹配全集与权威聚合对照；内存/源调用证明确无全库capture/StorageSession.load回退|
|V5|真实原72业务/验证/保存路径回归与新增读回映射总表；AI→UI、UI→AI双向刷新；QUEUED/LAUNCHED不报完成|
|V6|10工具一reply、8+2、多批、重复换ID、同ID改参、停止/未知、部分业务失败、依赖项跳过、预算后续停止不丢回执|
|V7|darkMode→字段错误→真实模型可修复的安全反馈契约；2/4修复上限；错误/未知工具不执行、拒绝/冲突不自动重试|
|V8|function_call缺省/null在Final/ToolBatch均接受，非null旧式明确失败；refusal/tool_calls/content可空组合；length截断、32/33、重复ID、坏JSON、400网关/已知code、401/403/429/413/5xx分别断言|
|V9|FAST每次往返均顶层两false，STANDARD无Qwen字段；官方主机边界/自定义profile/模型适用；非空reasoning触发模式不匹配，旧回执保留；未来preserve策略须独立回归|
|V10|范围冲突正反例：来电/无关归档不拒绝主题；号码B变化不拒绝A；同字段/关联职位/录音generation/统计规则变化拒绝；后台与UI发布同范围|
|V11|真权限票据单次launch/deliver、拒绝/取消/过期/宿主替换/迟到重复；权威目标变化取消；原密钥/路径/供应商body/思考泄漏哨兵|
|V12|请求真实字节、读行数/schema数/usage缺失/耗时、历史封存上限；无静默丢必需数据；依赖兼容构建/边界/lint|

模拟HTTP/Robolectric不替真机/模型。按变更运行既有边界、backend/ui/app测试、assembleDebug/lintDebug和Markdown smoke，证据绑定版本。

### 9.2 A9不可替代的真机/真实模型门槛

验收目标是已装debuggable `com.greatwall.callring` 的真实设备；负责人提供地址192.168.10.149:5555，实施时重新核对在线、当前安装versionCode/versionName、APK摘要/Git、系统API/屏幕/主题状态。读取安全AiSettingsView核对model=qwen3.8-flash、protocol=OPENAI、所用profile，不导出密钥。凭据、设备或网络缺失均记未通过。

通过debug/instrumentation在正式应用进程内调用正常ConversationApi→真实CompatibleConversationClient→生产业务API/存储，使用原加密凭据链；不得用脚本预制tool_calls或伪client代替模型。前台Activity须真实挂载，模拟宿主许可不能替代真实系统结果。

1. 精确发送原始指令“添加 10 个随机号码档案”。测试夹具只设可识别的合成数据命名/隔离约束及检查谓词，不替模型生成号码或操作；基线记录已有号码集合于进程内，逐项checkNumber/精确存在查询，禁止覆盖既有档案。差集必须恰好10个唯一合法合成号码，各字段与模型提议/实际回执一致并持久化读回；界面列表与详情刷新可见。不能用总数+10代替逐项核验，不能碰无关真实资料。无安全合成约束/生成不合法则失败并保留准确部分结果。
2. 精确发送“修改深色模式”。核对模型真实生成settings_saveAppearance/colorMode=DARK或等价已声明工具，经原UiEdits保存；getAppearance持久化读回DARK，前台真实Compose主题切换，重启进程仍DARK，浅深色显示证据不只验证枚举。此前已为DARK时记录no-op，不伪称发生切换；切换实证用安全测试基线经原业务接口置LIGHT并记录，结束按负责人验收约定保留DARK。
3. 同场普通聊天验证0业务源读取及无额外分类请求；分别记录原指令、版本/model/protocol/profile、实际请求次数与UTF8字节/schema数/历史量、阶段耗时、服务usage(若有)、HTTP/finishReason/工具次数、逐项真实回执与核验计数。诊断不附原始响应/密钥/思考/真实个人数据；截图选择合成数据界面。若失败，留脱敏分类证据并修复后用相同原文重新验收，不能提预算后掩盖缺陷。
4. 测试创建资料保持可识别，只能经现有业务能力处理本次新建且未被他人修改的记录；无删除能力不得私自SQL清理。设备测试与其余真实系统动作A6逐项列“通过/失败/未测”，两条A9通过不等于全部系统动作已验收。

本轮未调用模型/操作设备/构建新增依赖，A9未执行；讨论轮14项旧测试与负责人2项null缺陷复现仅作定位依据。

### 9.3 确认账本追踪与交付

Q01–Q02→§2/6；Q03–Q05→§3.1–3.2；Q06→§5；Q07–Q09→§4；Q10→§3.3/6；Q11–Q12→§3.4/4.1；Q13–Q14→§7；Q15–Q16→§8；Q17–Q18→§5/9；Q19–Q20→§1/3.4。原问题正文保留于确认账本；Q10补null与FAST/历史边界。
