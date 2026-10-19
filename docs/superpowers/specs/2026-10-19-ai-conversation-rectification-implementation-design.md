# AI 沟通整改实施设计

ai-v2-implementation/epoch2，待批准，未实施。

<a id="requirements"></a>
## 1. 批准依据与交付范围

依赖已批准[架构](2026-10-19-ai-conversation-rectification-architecture.md)，commit `514fdf7fd316ee6fe5b3745e9b29d1c9bf95070f`、blob `64c4919f7c6260600c7bd51e354fcc5a383e8e94`，approval `approval-7b1ef70a24efd65ea5bbdfac1d65b961b9599b22559f66c9de4c7138698c6abe`。实施讨论 `AI-V2-IMPL-DISCUSSION r1`（IMPL-Q01–06）全部收敛。容量、授权和业务口径遵循架构。

路径简称：B=`backend/src/main/kotlin/com/greatwall/callring`，P=`app/src/main/kotlin/com/greatwall/callring`，U=`ui/src/main/kotlin/com/greatwall/callring/ui`。测试在各模块src/test，正式设备测试在app/src/debug。批准execution后才改产品；不动compact-main-ui-design/brainstorm状态文件。

|需求/验收|实施落点|完成凭据|
|---|---|---|
|R1/A1|§6|交错保存/旧配置/密钥/复制|
|R2/A2|§7|四导航/状态/回执|
|R3/A3|§3|21域/全文/无泄漏|
|R4/A4|§4|72映射/业务核验/刷新|
|R5/A5|§2–3/5|闲聊0读/聚合/容量|
|R6/A6|§4/8|真实授权/票据矩阵|
|R7/A7|§7|Markdown/布局/无障碍|
|R8/A8|§4–5/8|批量/恢复/去重/冲突|
|R9/A9|§9|原文/10条/DARK/重启|
|R10/A10|§7.2|框架对照/依赖/实测|

<a id="components"></a>
## 2. 组件与接线

P/bootstrap/BackendGraph注入AiQueryApi、目录及计量端口；snapshots仅保显式诊断，ConversationService不再引用snapshotsDelegate。源级读端口不包装SqliteBackendStore.read。

|组件/文件落点|输入→输出与职责|
|---|---|
|B/application/api/AiQueryApi.kt；ports/ScopedReadPort.kt|类型化查询→安全页/详情/聚合/文本，禁SQL/URI/路径|
|B/application/conversation/CapabilityCatalog.kt及ToolRegistry|静态discover→O01–O72及扩展schema，零业务I/O；增加用途/字段单位/范围/缺省/副作用/授权/依赖/核验说明|
|B/application/conversation/QueryService.kt|校验/预算/游标→源端口；统一stamp/完整性|
|P/data/SqliteScopedReadAdapter.kt；platform/ai/RecordingScopedReadAdapter.kt；bootstrap/ScopedQueryAssembly.kt|接核心/录音SQLite、偏好/运行/诊断/SAF；共用纯计算|
|ConversationService + 新 TaskPlanner/StepLedger/ReceiptVerifier|生命周期、发现/启用集、计划、预检/修复、顺序执行、核验与完成判定。ToolExecutor继续调用 commandApi 和原业务API|
|新增 ContextAssembler；ports/ConversationPorts.kt|ContextEnvelope与WireMessage→有限WireRequest；UI消息与协议配对消息分离|
|P/platform/ai/CompatibleConversationClient + ProviderProfileResolver（新增）|编码/HTTP/解码、安全元诊断；不实例化ToolRegistry、不做业务成功判定|
|U/feature/conversation 与 AiSettings|只订阅API状态/变更，不查询全集、不持密钥；业务正文、状态、回执分开呈现|

普通一轮：analysis连接配置/凭据→仅规则/历史摘要/discover→模型Final或发现→最少schema→相关查询/计划/执行→读回反馈→Final与账本判终态。无额外分类/摘要模型请求，无关键词路由。

`discover_tools(category?,exactNames?,query?,cursor?,limit=8)`限1..12，返回catalogVersion/ID/匹配原因/schema/nextCursor/complete。空query遍历全部，exactNames精确检索。启用集只保discover、当前任务/协议配对所需，新轮重新按需启用；未启用返回TOOL_NOT_ENABLED和发现方法。

<a id="io_errors"></a>
## 3. 源级读取与完整性

### 3.1 公共契约

`QuerySpec(domain, projection, typedFilter, order, cursor?, limit=50, maxBytes=32768)`；limit 1..200，maxBytes≤131072。固定域枚举和过滤类型支持实体键、文本、时间、状态；默认 all_stored，无UI日期或页码继承。epoch毫秒/Long ID、ISO日期和zone分别校验。`get(domain,key,projection)`缺失返回NOT_FOUND。

`ScopedPage(items, stamp, matchedTotal?, returnedCount, nextCursor?, complete, status, missingReason?)`；stamp含domain/sourceVersion/observedAt/sourceScope/consistency。`readText(ref,cursor?,maxChars=4096)`上限16384 Unicode码点，返回 codePointStart/End、totalCodePoints、nextCursor、complete。大字段用 TextRef，行超限给引用或ITEM_TOO_LARGE，不截字段。TextRef绑定实体/字段/版本，仅进程内句柄。

游标绑定domain/filter摘要/projection/order/版本/末键/期限，敏感条件不入日志。keyset稳定排序，页/count同事务，变更CURSOR_STALE。终页next空/complete真，空页保stamp/范围/总量；跨源独立采集。状态COMPLETE/EMPTY/PARTIAL/NOT_CONFIGURED/UNAVAILABLE/DENIED，成功空源才EMPTY，总量未知null+原因。未实现阻断验收，不伪装源不可用。

### 3.2 D01–D21 实际来源

以下源级入口禁captureComplete/captureCore/captureRecordingText、StorageSession.load、recordings.all/systemRecordings全集后切片。小配置只读目标键；仅启动连接所需配置不计业务query。

|域|适配器及源级读法|业务口径与全文|
|---|---|---|
|D01 档案|核心SQLite callers；已有calls号码并集按number排序/精确IN查询|name/department/position/whitelist，不按UI搜索限制；CREATE查不存在|
|D02 职位|positions及settings.myPosition对应键，id游标|选项和本人引用均可读|
|D03 存储通话|calls，(time,id)排序，投影/过滤SQL参数化|包含hidden、未计分、统计未开启记录；详情按ID|
|D04 可见/计分通话|calls查询复用visible、区间等纯规则|明确VISIBLE/COUNTED范围，不替代D03|
|D05 统计|statistics_intervals及匹配calls流，固定聚合|共用score/reportTotals/quality，声明range/zone/规则版本|
|D06 排班|config_versions按(effective,id)分页，settings当前状态|指定snapshot JSON按目标行解码；history完整可遍历|
|D07 日历|calendar_years/calendar_days，year/date过滤和游标|所有已存年份可达，来源/版本与节假日名完整|
|D08 覆盖|overrides按date分页|windows用既有codec，目标日期精确读取|
|D09 请假|leaves按(start,id)分页|note可TextRef；状态依赖observedAt|
|D10 业务配置|settings按白名单key投影|score/quiet/lastQuiet/statistics/overlay完整，不附calls|
|D11 外观|AndroidPreferences的外观源接口|持久selection/seed/preset/colorMode，不猜当前主题|
|D12 铃声|音频控制器安全设置与即时播放观测|只给配置状态/显示名，不给私有路径|
|D13 录音配置|AiSettingsApi.getRecordingView|folderConfigured/displayName/access，无URI|
|D14 AI配置|AiSettingsApi.getAiView|两槽keyConfigured、预算、协议/model/profile安全值|
|D15 录音身份|recording_events/recording_statistics_links/deleted_archives各自keyset|保session链接和负callId，不转换为统计ID|
|D16 归档|archives元数据索引分页，指定call_id+generation正文读取|transcript与analysis所有字段/动作列表可分块，包含错误/模型等安全文本|
|D17 授权目录|SystemRecordingSource新增有界query树/children入口|provider可排序则分页，否则进程内有界遍历状态+取消/进度游标；未扫完PARTIAL，总量null，handle复用原注册表|
|D18 小组件|AndroidPreferences绑定源+真实AppWidgetManager观测|持久绑定和实际安装分别返回|
|D19 运行|AndroidRuntimeProbe与recordings.playbackStatus等定向探测|live_probe含权限/角色/电话/播放/任务队列；未知不能伪称授权|
|D20 诊断|AndroidDiagnosticsStore已存新旧键的安全投影|有界存储源逐行筛选/游标，不对UI最近列表分页冒充全集；只保白名单码/时间|
|D21 审计|calendar_audit按(created,id)/kind查询|列表元数据，raw按目标ID全文分片，保历史记录|

核心SQLite复用CallringDatabase生命周期，scopedRead仅参数化SELECT，不构造BackendState。LazyBackendStore暴露独立ScopedReadPort，初始化不加载全库。不改核心v3表/ID/数据；必要索引先EXPLAIN，无需不升版。

归档现为 recording.db v2 的 `archives(call_id,generation,payload)`。升v3只加 `archive_projection`（call_id、generation、number、started、status、source等元数据）与 `archive_text_chunks`（call_id、generation、字段路径、码点区间、文本），payload仍为业务保存真源。所有注册/CAS/删除路径在同一SQLite事务维护派生投影；不改变录音generation语义。旧数据不在启动时全库解码：按call_id有界回填，每次最多16记录或1MiB源文本，带时间/取消检查。单个旧payload经SQL substr读取有限块，交Android JsonReader流式提取，避免CursorWindow装全文；不自制JSON解析器。未索引/回填未完的列表返回PARTIAL和进度，调用者继续直至完整；错误列明目标索引状态，不能省略后声称全集。指定详情可仅回填目标记录。chunk以码点边界分割，不断开代理对；持久化部分索引只有完成标记后可见，generation变化丢弃过时派生数据。迁移损坏保留原payload并报源错误；加入v2升级、进程中断续回填及CAS一致性测试。

提取ReportCalculator纯累加器供UI/AI共用，按号码/时间流式处理，跨页保前一计分时间；统计区间、分类、饱和加法/边界不变。固定count/sumDuration/score/reportTotals/groupBy，无模型SQL。扫描匹配行不存全集，只读关联规则。版本变化/未完成不给权威总数；超限AGGREGATE_INCOMPLETE+范围。隔离库与旧报告全量等价对照。

<a id="state"></a>
## 4. 执行计划、核验与一致性

### 4.1 原操作与计划

保O01–O72编号/名称/API，扩展discover、data_query/get/aggregate/readText、getCallDetail、task_prepare、callers_saveProfiles。机器检查OperationContract的id/name/api/投影/scope/核验/前台全集映射。O03/09/50/61等大列表走ScopedReadPort，旧UI API不变。

单项expectedCount=1；多写先task_prepare(kind,userMessageRef,expectedCount,targets?)签taskId。支持CREATE_PROFILES/SET_APPEARANCE及通用计划；业务/模型依赖取并集，循环/未知依赖不执行。计划不授权、不降用户数量/目标；无计划多写TASK_PLAN_REQUIRED零写，模型补齐。

`callers_saveProfiles(taskId,items[1..32])`及同reply多条saveProfile均归统一规划：整reply先验完整性/数量≤32/非空唯一callId，再验证各schema和整批目标。CREATE统一号码校验、同批去重、精确查现存，禁止覆盖；模型生成号码及字段，测试和编排不代填业务内容。预检失败项不启动，只有证明独立的合法项可继续；未知依赖阻塞。内部每8项顺序窗口，逐项取消/范围检查，不为8+2再请求模型重新生成。

StepLedger主键taskId/localStepId，callId和工具+规范化目标/参数摘要作索引。PREPARED→STARTED→COMMITTED→VERIFIED，另有FAILED/CANCELLED/DENIED/SKIPPED/UNKNOWN；读回失败为COMMITTED_UNVERIFIED。相同callId改参拒绝，换callId重复语义复用既有回执，未启动修复项可更新摘要；已提交项不得改参或重放。摘要仅进程内，不导出可枚举敏感哈希。

一个步骤成功必须保存回执与目标字段持久化读回一致。幂等同值报告已是目标，不造提交。核验器按原操作分组：档案/白名单读号码字段；职位/请假/覆盖删除以不存在及引用结果证明；排班/评分/业务/外观配置读原API或源安全值；归档按callId+generation及目标字段；widget绑定读持久值；铃声读配置/真实播放；连接复制读安全视图。QUEUED/LAUNCHED保持待观察，不能升级成完成。前台系统返回也不证明后续异步业务完成。

COMPLETED须目标/核验满足，已有提交未达标PARTIAL，零成功且不可继续FAILED。Final不覆盖账本，可预算内补齐一次；报告核验/提交未核验/失败/未执行数与阻塞，后续错误/停止保回执。同进程显式taskRef仅续未启动项，退出不自动恢复，不承诺跨进程exactly-once。

### 4.2 范围基线和发布

CommandContext增加非空expectedScopes，保expectedVersions兼容。ScopeKey=资源/实体/字段或聚合集合，ScopeStamp=版本/字段基线，由业务读取产生，模型不能传版本。Coordinator同一fence检查/提交/发布/记回执；网络/SAF/系统/长查询在锁外。旧命令保守全向量，新渐进写禁止空scope。

|命令族|依赖与发布范围|
|---|---|
|profile/whitelist|number及patch字段、引用position存在/active/规则；CREATE持不存在基线|
|position删除/myPosition|position实体、myPosition引用、引用该职位的profile集合|
|appearance|patch字段及交叉校验selection/seed/preset，无关calls/recordings不冲突|
|录音/AI设置|被改字段、对应槽连接/密钥revision/profile/budget；其他槽独立字段不冲突|
|归档操作/采纳|callId、generation、身份/分析及被写profile字段、目录handle|
|排班/日历/请假/评分/免打扰/报表|目标+实际依赖的规则/区间/匹配calls集合版本|
|widget/铃声/系统动作|widgetId或铃声状态、真实授权和权威票据目标|

共享保存入口按前后差异发布字段/引用集合，覆盖UI/AI/来电/同步/录音后台。自身提交只推进本计划对应基线，外部变化须重读/确认，不能临提交消冲突。feed可宽通知但不判全局冲突；UI按changes刷新，AI后续读新值。测试外观/来电、号码A/B、同字段、职位引用、归档generation、统计规则正反例。

<a id="capacity"></a>
## 5. 历史、预算与可恢复错误

UI消息只保正文/回执，WireAssistant保toolCalls/blocks，结果按callId配对；部分失败/停止也补齐已接受callId结果，禁悬空调用。结束轮次确定性摘要含意图/状态/核验/未完项/sourceStamp/TextRef，不反复发大结果、不调用模型总结；摘要非新鲜真值，按需重读。

UI≤100结束轮次或2MiB，显示归档范围；摘要/引用有界，活动项不裁剪，装不下CAPACITY_EXCEEDED。保49152/4096及旧显式预算、8MiB请求/1MiB响应。检查最终UTF8体，估算非精确token；不裁必需schema/数据/配对。

TurnBudget模型≤16、工作≤64，发现/查询/写/核验/修复逐项计；reply≤32，窗口8。启动前预留回执/核验，10条至少10写+10核验。不足不启动后续；已提交保COMMITTED_UNVERIFIED与回执。

schema/参数/无副作用业务验证错误以正确tool_call_id反馈字段路径、白名单码、预期类型/枚举和安全例子colorMode=DARK，不回送错误实值。单step≤2、全轮≤4修复，未知/未启用重新发现也计恢复预算。模型自己修正，不自动把darkMode改名。拒绝/取消/冲突/提交未知不重试；网络自动重试0，不重放写入。

<a id="compatibility"></a>
## 6. 设置、profile与AR-001复制矩阵

保patchRecording/patchAi/combined；Keep取最新，仅改字段判基线冲突。密钥Keep/Replace/Clear本地显式，空输入不清，交错保存不覆盖。RecordingSettings分析会话新增conversationCompatibility=AUTO/STANDARD/QWEN_FAST，旧缺省AUTO；加密codec/view/patch/testDraft/schema/指纹均覆盖，预算解码不变。

AiConnectionView增resolvedProfile默认兼容值，分析view另披露选择值；sendTo核对渲染目标的protocol/url/model/resolvedProfile，fingerprint额外含密钥revision及兼容选择，活动分析连接/密钥/profile变化取消client、票据、旧wire。非连接独立设置变化不取消对话。

|复制方向|同事务保存|兼容方式规则|回执/活动轮次|
|---|---|---|---|
|ANALYSIS→TRANSCRIPTION|复制url/model/key，转写protocol强制OPENAI，旧接口语义不变|只属分析会话的conversationCompatibility不传播；保原analysis选择/解析profile|本地确认源目标安全视图，读回转写；分析连接未变不取消当前会话|
|TRANSCRIPTION→ANALYSIS|复制完整转写连接url/model/key/protocol到analysis|原子把analysis conversationCompatibility重置AUTO，按新目标重新resolve|确认包含重置告知；返回resolvedProfile，取消旧活动会话/票据/旧wire，读回analysis|

同槽拒绝。复制沿用expectedRevision和确认，原子检查/保存，失败不留半复制；安全回执仅keyConfigured。双向测试非默认选择、强制OPENAI、AUTO解析、并发、取消条件、读回/重启。

QWEN_FAST AUTO仅在OPENAI、架构维护的官方HTTPS精确主机清单与明确支持qwen3.8-flash模型/快照匹配时生效，主机解析拒绝伪后缀/用户名混淆，不用contains。自定义代理AUTO为STANDARD，显式QWEN_FAST也须匹配模型支持清单。FAST每次请求顶层enable_thinking=false、preserve_thinking=false；其他连接不加。收到非空reasoning_content为PROVIDER_MODE_MISMATCH，执行该回复工具前停止，保旧回执；空/null正常，不显示思考。本版不支持preserve模式。

<a id="tradeoffs"></a>
## 7. UI与依赖取舍

### 7.1 展示

保首页/档案/AI/设置四入口，宽侧栏/窄底栏。删静态说明和身份标签；新对话图标保描述/确认/提交事实。用户纯文本右侧主题气泡，AI左侧无背景Markdown；回执可展开，工具前文动态待核验，成功受账本约束。

发送图标16–20dp置输入框右下，48dp触达/描述/禁用态/键盘发送，文字不遮挡；适配IME/insets/窄屏/大字体/长文滚动。保动态阶段/错误/等待/目标变化/未配置入口。增PREPARING/DISCOVERING/READING/REPAIRING/PARTIAL并更新busy，删旧全量文案。

固定 `com.mikepenz:multiplatform-markdown-renderer:0.40.2` 与 `multiplatform-markdown-renderer-m3:0.40.2`，不装网络图片/高亮插件，不执行HTML/脚本，链接仅用户点击受控打开。先dependencies、dependencyInsight、compileDebugKotlin、D8/assemble及Markdown smoke；记录实际Compose选中版本与APK差量。依赖失败不自制parser、不静默换版，把最小兼容/局部Compose对齐方案交负责人裁决。UI测标题/列表/表格/代码/链接/中文长文、LIGHT/DARK、大字号、宽窄屏与TalkBack描述/48dp。

### 7.2 轻量循环

修现有循环，Agent依赖增量0；依据是8阈值/null/错误折叠定位及8+2/正确字段业务保存，尚非真实Qwen根因结论。架构§8.1固定Koog1.3.0支持Android/minSdk23/compileSdk36/Java17、最小AAR及17直接API依赖；不能称其不支持Android。项目JDK17/Kotlin2.3.21，JVM1.8/D8/API29待测，框架仍需scope/票据/去重/核验。当前不引入、不称未测性能优势；交付本实现依赖/构建/请求/耗时证据。A8/A9仍失败才带诊断评估隔离AgentEngine同任务对照，经批准切换。

<a id="authorization"></a>
## 8. 协议错误、前台与诊断

扩展AiFailure类型携带kind/code/retryable/httpStatus/providerCode/fieldPath/scope/counts，转换ApiError供UI和工具安全反馈。HTTP响应先判状态后按白名单提取code/type，非JSON400仍HTTP_REJECTED；401/403 AUTH、429 RATE、413/已知context码CONTEXT、5xx SERVICE。网络TIMEOUT/TRANSPORT、严格JSON/关键SHAPE/STOP、OUTPUT_TRUNCATED/RESPONSE_TOO_LARGE、TOOL_COUNT/ID、UNKNOWN_TOOL/NOT_ENABLED、ARGUMENT_JSON/SCHEMA/DOMAIN_VALIDATION、CONFLICT/CURSOR_STALE/NOT_FOUND分别保留。后续失败文案合并账本counts，不归为“模型不支持”。

OpenAI function_call缺省/null正常，非null旧式明确LEGACY_FUNCTION_CALL_UNSUPPORTED；tool_calls缺省/null/[]无调用。Final必须stop+非空可见正文，ToolBatch必须tool_calls+完整结构，content可null；refusal非空单列零执行，未知可选null忽略，关键类型错拒绝。length/max_tokens或不完整批次本次零执行，旧提交保留。decode不依赖业务registry，参数错交编排层受限修复；Anthropic原配对/块边界回归。

前台票据保request/session/turn/generation/hostLease/期限，一次launch/deliver。权限/角色/文件目录/CSV/pin/overlay/凭据/复制/付费测试/删除采纳仍交真实宿主。停止/新会话/连接变化/host失效作废，返回再查票据/scope；拒绝/取消/迟到/重复零提交。确认取本地权威字段，文本不授权，LAUNCHED不等于获权。

诊断仅闭合结构：Git/APK/model/protocol/profile、随机trace、HTTP/枚举provider码/finishReason、工具名/字段名、计数、UTF8字节/历史量/schema数、usage或null、阶段/总耗时、枚举源范围。禁headers/body/自由异常/思考/业务实值/可枚举哈希。两槽redactor覆盖用户文本/业务投影；测试secret/URI/path哨兵，不能先记录后清洗。

<a id="verification"></a>
## 9. 验证与交付门槛

先按子系统增加能证明缺陷的失败测试再实现，保已有72业务覆盖与边界检查。自动测试分组：

|组|必须证明|
|---|---|
|V01 设置|字段交错/冲突、密钥Keep/Replace/Clear、旧预算/AUTO、双向复制与活动会话|
|V02 UI/依赖|四导航、精简气泡/图标、48dp/描述/IME/大字体、Markdown smoke及双主题|
|V03 查询|D01–D21多页/尾哨兵/真实源失败/空/撤权、版本游标；禁止全载调用计数；聚合等价|
|V04 编排|闲聊0快照/query/runtime和1模型请求；发现遍历72+扩展；10及8+2/多批/去重/改参/部分失败/停止/有限修复/容量|
|V05 写入|目录↔执行↔API↔核验全集；共享校验/保存、双向刷新、字段scope并发正反例；QUEUED/LAUNCHED不误报|
|V06 协议|null组合、非null旧式、坏JSON/HTTP矩阵/截断/32与33/重复ID；FAST多轮两false/主机边界/思考错配/Anthropic|
|V07 系统安全|票据拒绝/取消/过期/宿主替换/迟到/重复、确认目标变化、密钥/URI/body/思考哨兵|

跑边界脚本、backend:test、ui/app testDebugUnitTest、assembleDebug/Release、lintDebug及相关UI设备测试。确认实际任务名，失败保产物；记录测试数/失败/跳过、依赖版本、APK大小/摘要、读行/字节/模型请求，不编造指标。backendqa结果不代A9。

新增app/src/debug/ConversationAcceptanceDeviceTest，取targetContext.applicationContext的正式CallringApplication.backendGraph/ConversationApi，launch真实Activity/宿主。禁隔离夹具/临时库/伪client/预制tool_calls。tools脚本显式设备/类选择，只跑此类，不跑修改真实库的旧全套测试。

1. execution阶段独占192.168.10.149:5555，复核在线/未锁屏、package=com.greatwall.callring、系统API/屏幕、versionName/code、APK SHA/Git、model=qwen3.8-flash/protocol=OPENAI/resolvedProfile。用同签名 `adb install -r` 保配置；签名不符即阻塞，不卸载/清数据。凭据仅正常应用进程认证链，不打印/导出/直接解密。
2. 先真实普通聊天，证实0业务快照/query/runtime，计数不包含必要配置/凭据读取；记录真实请求次数/字节/usage/耗时。不得通过测试开关跳过真实HTTP。
3. 经正常ConversationApi精确发送“添加 10 个随机号码档案”。验收夹具仅提供可识别合成数据约束及检查谓词，不生成号码/操作。基线旧号码集合留进程内不导出。逐项正常checkNumber、存在性、模型提议与真实回执/读回字段比较，差集恰好10，旧资料无覆盖；UI档案列表和详情实际刷新截图仅合成数据。合成资料保留可识别，不SQL清理；失败保留部分结果，先诊断再受控重试，不反复新增多批。
4. 通过原业务API记录LIGHT安全基线，前台截图确认；精确发送“修改深色模式”，核对模型真实工具/colorMode=DARK，原UiEdits保存与持久读回，真实Compose主题截图。停/重启应用后读回及界面仍DARK，保DARK。不用单个枚举断言替视觉切换；已DARK仅no-op不能冒称切换。
5. 脱敏导出每轮schemaVersion=2证据，含原指令/版本/协议/profile、最终请求字节/上下文量、轮次/阶段耗时/usage、HTTP/finishReason、工具数/实际逐项回执状态/核验计数，业务实值不入诊断。具体合成字段仅在受控UI检查与进程内核对，导出用不含敏感值的顺序步骤ID与通过谓词；独立审查可复跑读取验证。任何凭据/设备/网络缺失或真实任务失败记A9未通过，不能以模拟替代。

交付docs/plans/AI沟通整改验收.md及ai-rectification-evidence，逐项A1–A10/架构V1–V12列通过/失败/未测与commit。A6其他系统动作单列。本文未跑新增依赖/模型/设备；设计与计划批准进入execution后才实施。
