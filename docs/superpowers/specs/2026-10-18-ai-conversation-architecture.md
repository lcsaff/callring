# AI 沟通与统一业务操作架构

日期：2026-10-18。状态：架构提交，待主负责人审定。仅为设计契约，不代表功能已实现。

<a id="requirements"></a>
## 1. 需求、批准基线与边界

任务 ai-architecture，阶段 architecture_design，epoch 2。源码基线为 c05aa91f1098e21bbc44a7735b1737db9eeeb449；讨论 ai-architecture-discussion revision 1，digest ea7500852b3a561fa690b980a3705a49a77c3b40574d4130b21b3d368213c445，Q01–Q22 全集已由主负责人确认进入本阶段。当前轮批准上下文摘要为 e53a3da22ca813ded5828bca3677c30a7b9938f29355eb15edcfe10e13845ab4；本架构仍须另行批准。原 ai-conversation-design.md 是未批准参考稿，不是契约依赖。

| 需求 | 验收 | 本架构必须提供 |
| --- | --- | --- |
| R1 拆分 AI 与录音设置并保留配置密钥 | A1 | 两页面、字段补丁、存储内合并与原子保存、兼容旧加密配置 |
| R2 并列 AI 沟通页 | A2 | 窄屏底栏/宽屏侧栏统一四入口、对话与真实操作状态 |
| R3 完整业务文本、配置、运行状态；密钥只见状态 | A3 | 无分页/日期遗漏的全量快照、域清单、独立录音与组件枚举、脱敏 |
| R4 共用接口修改全部配置与业务操作、双向刷新 | A4 | 方法级覆盖矩阵、共同校验/保存/错误/版本与变更流 |
| R5 简单全量加载，超限明确停止 | A5 | 每次请求全部输入预算、输出预留、服务端容量拒绝、无静默裁剪 |
| R6 系统授权与选择由 AI 发起、前台完成 | A6 | 类型化票据、真实结果、取消/拒绝、旧及重复回调失效 |

首版选择：完整文本快照＋注册业务工具＋前台系统动作；对话复用分析连接，转写保留独立 OPENAI 兼容连接；一个进程内会话；不流式执行工具，不增加检索、向量库、多会话持久化、任意脚本/SQL/Intent/路径访问。不增加现有业务没有的档案或通话删除；保留现有录音独立于统计的行为。应用未存储的系统通话、已删除归档正文、历史上未保留的诊断不声称可读取。

提交内容依赖为下列需求的规范化SHA-256（按插件contentDigest计算，acceptance按ID排序），批准时逐项复核；源码commit/blob是设计依据，讨论digest是方向来源。当前没有上游正式架构批准或附加材料版本可引用，不编造approval ID。

| 依赖ID | digest |
| --- | --- |
| R1 | aaf8a0609c0e03655a3e6cfaf290acfe476b4d017a734e40ee4112a0fc88b521 |
| R2 | f53e48302d378bcea19aaacfcc9e7df41354b999b845893db3eefca2d0e073f7 |
| R3 | a3c6d70707f5e92c71daa3cadcd335179f165032a462e01f68d5665c477b24c2 |
| R4 | 15f09388cee1f841e9eee04a0af13fe853fba6359736c05e38a7035d9107c40e |
| R5 | 96eb450e657962865ee93c4c3924d76d23f44b3b90c325b4032a0305c0137f1f |
| R6 | 8d35de2231f4222a4392cb9b10e27645951392d8e99db16ff217b38f9f7fd9b6 |

<a id="evidence"></a>
## 2. 已核实源码与变更依据

以下路径均相对仓库根，commit 均为上述基线。blob 可通过 git show <commit>:<path> 与 git rev-parse <commit>:<path> 复核；符号是源码锚点。

| 引用 | 路径 | blob | 锚点及事实 |
| --- | --- | --- | --- |
| E01 | backend/src/main/kotlin/com/greatwall/callring/application/api/CallringUiApi.kt | fe07cc9236d56ea67423998f9200c6a5dccc868e | CallringUiApi、SettingsApi、DataDomain：共用业务 API 和变更域 |
| E02 | backend/src/main/kotlin/com/greatwall/callring/application/api/RecordingApi.kt | 9a938b78b6b998475e71586f957f25edfa323173 | RecordingSettings、RecordingApi：配置整体保存，AiConnection 含实值密钥 |
| E03 | backend/src/main/kotlin/com/greatwall/callring/domain/BusinessState.kt | 07f380c2e7ceb4ba6ccdacd13803c48d1002089d | BackendState：全部主库集合、历史 schedule、统计区间与审计 |
| E04 | backend/src/main/kotlin/com/greatwall/callring/domain/BusinessRecords.kt | 216125d309645e1ac679ac52ea732cda9d5f8653 | RawCallRecord：hidden/linkedScreenId、旧值与分类事实 |
| E05 | backend/src/main/kotlin/com/greatwall/callring/application/CallerProjection.kt | 8ac91807b0b78941c2a191f7296795de90b7cd71 | visible/detail：隐藏关联筛选与 MERGED 投影 |
| E06 | backend/src/main/kotlin/com/greatwall/callring/application/ReportCalculator.kt | bc5e76f1dd2a7f10958bb7feb59c5b423910176e | score/report：权威评分、计数与日期范围 |
| E07 | backend/src/main/kotlin/com/greatwall/callring/application/BackendContext.kt | d6918390c2410fff8d770e8f91c8ff5732c6dd2c | mutate/write/notifyChanged：事务、非取消提交和进程 revision |
| E08 | app/src/main/kotlin/com/greatwall/callring/platform/ai/AndroidRecordingStore.kt | d72c5d0b933610caec8f7d33dc0be1c22a31c8d7 | settings/all/registerIncoming：原加密 config、独立 events/links、负 ID |
| E09 | app/src/main/kotlin/com/greatwall/callring/platform/ai/AndroidRecordingRuntime.kt | 8674c776607be166bc8da2f97785a7297fbf7853 | listArchives/saveSettings/importFile/setSystemFolder：录音业务执行入口 |
| E10 | app/src/main/kotlin/com/greatwall/callring/platform/ai/CompatibleAiClient.kt | ccf6c33b9d221654375ca6204c4ad8ce8bd9c595 | analyzePart/request/safe：现有分析禁止工具、禁重定向、有限响应与安全错误 |
| E11 | app/src/main/kotlin/com/greatwall/callring/platform/AndroidPreferences.kt | 4804a6ef1e4a596ff8c8756b4e7f9a3771868c41 | getBinding/updatePreferences：当前无全量绑定接口、失败回滚语义 |
| E12 | ui/src/main/kotlin/com/greatwall/callring/ui/app/CallringActivity.kt | 50c1ade632ee5765cbc9f07152a9504515cc7950 | requestCapability/pinWidget/onResume：系统入口仅触发刷新，尚无关联回执 |
| E13 | ui/src/main/kotlin/com/greatwall/callring/ui/app/DocumentPickers.kt | 902717547d8ba8f4032f0926a0a52d74ff46c8b7 | rememberImportPicker/rememberExportPicker：取消目前无结果通知 |
| E14 | ui/src/main/kotlin/com/greatwall/callring/ui/app/CallringApp.kt | b7560e07cac6309876c133ea196d85292a156a85 | tabs：目前首页/档案/设置三个入口 |
| E15 | ui/src/main/kotlin/com/greatwall/callring/ui/feature/recording/RecordingViewModels.kt | 0d13281c79954d47167ef8ee30824f663865ef47 | RecordingSettingsViewModel/adoptIdentity：整体 copy 保存与 UI 内组合采纳 |
| E16 | ui/src/main/kotlin/com/greatwall/callring/ui/feature/recording/RecordingScreens.kt | 58a69b1ec8731ce1a6ed281e18f4d127da9efe4d | ArchiveScreen：删除/自述采纳/连接测试的具体确认 |
| E17 | app/src/main/kotlin/com/greatwall/callring/platform/ai/SystemRecordingSource.kt | f1e20899b76f1505167471316ab0d602dcfc55b5 | list：深度 4、500 节点处静默结束 |
| E18 | backend/src/main/kotlin/com/greatwall/callring/application/ports/BackendPorts.kt | 6acc1d137f457e8e25dc2cb761f6970f1cf78e22 | BackendStore/PreferenceStore：主库单快照和偏好端口 |
| E19 | app/src/main/kotlin/com/greatwall/callring/data/SqliteBackendStore.kt | f306991f24e9e87bfc0dfbe17266376ebf7e5521 | read/transaction/StorageSession.load：SQL 事务加载全部主库状态 |
| E20 | app/src/main/kotlin/com/greatwall/callring/data/ScheduleStore.kt | 16ab8fec2ffdfea5d087506f997c422b4a4af3f6 | load/save：真实日期与历史工作配置持久化，未保留原下载 JSON |
| E21 | app/src/main/kotlin/com/greatwall/callring/platform/AndroidDiagnosticsStore.kt | 312e326958228c027d1c68ac73bede731a6d747d | read/addAt：诊断保留窗口及 legacy 转换，不能声称全部系统日志 |
| E22 | backend/src/main/kotlin/com/greatwall/callring/application/RecordingWorkflow.kt | 9f0bd56dc7a6c732b9a82d5faa1a4877fffbfb61 | compareAndSet/process：后台归档写入同样必须产生版本与失效 |

<a id="components"></a>
## 3. 组件与依赖边界

下列是拟增/调整的逻辑组件名，实现可按现有目录拆文件，责任和依赖不得合并绕过。

| 组件/模块 | 输入与输出 | 自有状态 | 依赖/限制 |
| --- | --- | --- | --- |
| ConversationApi / backend API | send、stop、newSession、observeState；完成/错误/操作回执 | 接口无 Android 对象 | 通过 CallringUiApi.conversation 暴露；测试实现明确 unavailable |
| ConversationService / backend application | 用户消息 → 全量快照 → 模型响应 → 工具回执 | 会话、轮次、执行账本、待动作、取消代次 | 只依赖快照/客户端/工具/前台动作端口；单轮串行 |
| AiSnapshotService / backend application | captureComplete → 不可变 AiBusinessSnapshot | 短期快照、manifest | 主库投影＋录音/偏好/平台快照端口；不调 UI ViewModel |
| SharedBusinessCommands / backend application | 具名补丁/业务命令＋期望版本 → ApiResult/receipt | 不另存业务副本 | UI 与工具共用；原 service 校验/投影/保存仍为权威 |
| AiSettingsService / backend application + app storage | 录音补丁/AI补丁/本地凭据命令 → 安全设置视图 | 配置版本/凭据句柄 | 在原加密存储内合并；不能将密钥送回 conversation |
| ToolRegistry / backend application | 固定 tool name＋JSON → 类型化命令 | 注册表，无业务数据副本 | 表中全部能力，参数/结果 schema 与测试同源；无通用执行器 |
| BusinessRevisionCoordinator / backend application port | mutation publication、revision vector、ChangeFeed | 进程 incarnation、单调变更序号 | 主库/录音/偏好/铃声所有提交共用发布协议 |
| ConversationClient / backend port | 完整协议请求 → 完整 ParsedReply | 每请求取消句柄 | 不执行任何工具；不解析任意返回为操作文本 |
| CompatibleConversationClient / app platform.ai | HTTPS 和两协议编码/解码 | 有界网络连接 | 独立于原 CallAnalysis 分块路径；凭据仅用于认证 |
| ForegroundActionBridge / backend port + ui host | 类型化动作票据 → 核验结果 | 票据/短期 document 句柄 | ActivityResult、SAF、本地确认、设置导航；无 Activity 泄漏 |
| ConversationRoute/ViewModel / ui | 展示状态、发送/停止/新对话 | 输入框/滚动位置 | 会话由 application 持有；不用 SavedState 保存全文或密钥 |
| RecordingSettingsRoute / AiSettingsRoute / ui | 本页草稿 → 本页补丁 | 草稿、原版本、dirty | 不保存另一页字段；密钥编辑留专用表单内存 |

BackendGraph 懒装配 application 级服务和统一 api。UI 的 ViewModel 不持有后台长期会话所有权；关闭 graph 时取消网络、失效待动作、关闭录音资源。conversation 不触发 Telecom 决策路径；来电逻辑保持调用原业务对象。原 checkNumber、评分/工作分类、持久化端口继续共用。

CallringUiApi 新增 conversation、aiSettings、snapshots 三个属性（端口均定义于backend）；recordings保留原业务入口。aiSettings同时提供getRecordingView/patchRecording/getAiView/patchAi及本地credential流程，snapshots提供captureComplete与必要受限投影，ConversationService不是另建一个只给AI使用的业务后门。ForegroundActionBridge的deliver/host lease只供进程内UI持有，不作为模型工具参数。

数据流：用户发送 → 校验会话及完整文本外发告知 → 全量 capture → 脱敏 → 容量预检 → 对话客户端 → 完整解析/验证 → 工具映射 → 共用业务服务或前台桥 → 真实回执＋失效通知 → 再捕获完整快照/再预算 → 下一次模型调用或终止。原录音自动分析任务仍走 RecordingWorkflow，不共享对话历史或模型工具。

<a id="interfaces"></a>
## 4. 接口、类型与错误契约

### 4.1 ConversationApi 与内部端口

~~~kotlin
// 伪签名：声明设计责任，非本轮实现代码。
interface ConversationApi {
    fun observeState(): StateFlow<ConversationState>
    suspend fun send(expectedSessionId: String, text: String): ApiResult<TurnAccepted>
    suspend fun stop(expectedSessionId: String): ApiResult<StopReceipt>
    suspend fun newSession(expectedSessionId: String): ApiResult<SessionView>
}
interface AiSnapshotApi {
    suspend fun captureComplete(): ApiResult<AiBusinessSnapshot>
}
interface ForegroundActionBridge {
    fun observePending(): Flow<PendingAction?>
    suspend fun attachHost(hostToken: String): HostLease
    suspend fun beginLaunch(requestId: String, hostToken: String): LaunchDecision
    suspend fun deliver(requestId: String, result: HostResult): ActionResolution
}
~~~

send 的成功仅表示 TurnAccepted；最终状态来自 observeState。busy 时拒绝第二次发送而不排队。newSession 先停止旧轮并保留旧轮已提交回执直至新对话提示展示，再换 sessionId；stop 可在任何非终态调用，幂等返回最后已知的执行账本。UI 消息与模型协议消息分离：工具 assistant 文本在执行完前不作为“已成功”展示；操作列表以本地 receipt 为准。

内部 Reply = Final(text) 或 ToolBatch(calls, assistantProtocolBlocks)，不存在带半段工具的成功结果。ToolCall 包含 providerCallId、固定工具名及已完整验证的类型参数。全批次在执行首项前通过名称、字段、枚举、重复 ID、长度、停止原因校验。

### 4.2 快照与版本

AiBusinessSnapshot = schemaVersion(首版1)、snapshotId、capturedAt、clock/zone、revisionVector、domains、manifest、业务数据、redactionSummary。manifest 对每个域给出 status、itemCount、sourceVersion、collectedAt、sourceScope、digest；CompleteSnapshot 构造器仅在全部必需域完整时可产生。用独立类型阻止 PartialSnapshot 进入客户端或工具执行。

status 仅允许 COMPLETE、EMPTY、NOT_CONFIGURED、UNAVAILABLE、FAILED：EMPTY 是成功读取且确为零项；可选外部目录未配置为 NOT_CONFIGURED，平台已探测不支持为 UNAVAILABLE；应有数据读取失败是 FAILED，不能伪装为空或未配置。主库/录音/配置/绑定/已有诊断都是必需读取域；运行探测返回 AccessState.UNKNOWN/UNAVAILABLE 是有效测量，但探测基础服务异常导致整个域不能生成时 FAILED。已配置目录无法读取或枚举超限同样 FAILED。

RevisionVector = processIncarnation ＋ coreRevision ＋ recordingRevision（登记/关联/归档）＋ recordingConfigRevision ＋ aiConfigRevision ＋ preferencesRevision ＋ ringtoneRevision ＋ diagnosticsRevision；采样运行态另带 measuredAt，不把时间戳当并发版本。录音和AI配置虽在同一密文里，但各自字段组独立递增版本，不把另一页保存误作本页冲突。摘要/hash 不作为可靠版本计数替代品，也不在进程重启后复用旧票据。静态读域的版本在实际提交成功后递增，回滚不得递增；后台、界面、AI、接收器均遵守。

### 4.3 共同命令与字段补丁

每个写命令接受本地 CommandContext(operationId、origin、expectedRevisionVector/expectedEntityVersion、sessionGeneration)；模型不能自行填写 origin、版本或授权字段，由编排器把请求所依据的本地快照绑定进去。UI 提交自己的草稿基准和 dirtyFields。

PatchField<T> = Keep 或 Set(value)；可清空字段使用 Set(null)，缺失=Keep，不用 null 同时表示保留与清空。列表/weekly map 的 Set 是整个字段替换，不是猜测增删。凭据单独 LocalSecretChange = Keep、Replace(secret)、Clear，类型只在本地设置与凭据服务之间传递。conversation DTO 和 tool schema 中不存在 secret 参数。

录音配置提供 getRecordingView、patchRecording、getAiView、patchAi；旧 RecordingApi.getSettings/saveSettings 保留给录音 workflow/兼容调用但应标明内部完整对象，禁止进入出站序列化。所有生产 UI 和 AI 写入口迁到补丁路径。旧 saveSettings 如仍被调用也必须走相同存储内验证/发布，不能成为独立无通知写入。凭据视图只含状态，内部凭据访问用具名 connection slot，不由模型指定路径。

SharedBusinessCommands 将档案资料、免打扰、工作/评分/外观、职位/请假等补丁在所属业务事务内合并最新值并调用原校验函数。旧完整命令作为同一执行器的适配入口，不复制一套校验。新增 adoptSelfReportedIdentity(callId, expectedArchiveGeneration) 在一个应用操作内读取当前自述 name/organization 与最新档案并保存：空字段保留，职位与白名单保留，inferredIdentity/position 不采纳；结果返回修改字段。UI 原组合逻辑改用此入口。

### 4.4 操作结果

ToolReceipt 必含 operationId、toolCallId、status、commitEvidence(可空)、changedDomains、safeValue、safeError(可空)、observedAt。status 为 READ、COMMITTED、QUEUED、LAUNCHED、CANCELLED、DENIED、FAILED、UNKNOWN。COMMITTED 只用于可验证的业务提交；QUEUED 不代表后台分析/同步完成；LAUNCHED 不代表授权/桌面安装/悬浮窗实际显示完成；UNKNOWN 不能渲染为成功。

保留 ApiError 的 code/message/fieldErrors/requiredCapabilities/retryable；新错误可通过新增稳定 message key 表达，并为版本冲突/容量等增加明确代码。不可将原异常或服务端原始正文直接显示或返回模型。

<a id="errors"></a>
| 错误类/稳定键 | 触发 | 行为 |
| --- | --- | --- |
| VALIDATION + 原字段错误 | 类型/号码/时段/职位引用/URL/配置不合法 | 不提交；返回原业务字段；模型不能修复为默认值后伪成功 |
| CONFLICT / business.changed | 期望实体或所依赖域版本改变 | 不提交；撤销其余待执行计划，重新全量读取后由模型重算 |
| ai.snapshot_domain_failed | 必需域读取、解密、解析失败 | 不请求模型、不执行工具；列域名和可操作原因 |
| ai.snapshot_changed | 3 次捕获均发生业务域变更 | 明确重试，不用拼接残缺结果 |
| ai.context_budget_exceeded | 本地预算/字节/会话/响应限制超出 | 不裁剪，停止后续动作并显示实际大小与上限 |
| ai.provider_context_rejected | 识别的服务端上下文/请求过大拒绝 | 容量错误，保留已提交回执；不以网络重试继续动作 |
| ai.protocol_unsupported / ai.invalid_tool_response | 不支持工具、未知名称/参数/停止原因/重复ID冲突 | 整响应失败，执行零个该批工具 |
| ai.incomplete_response | length/max_tokens/内容过滤/截断 | 不执行该批工具；保留输入与本地已提交记录 |
| PERMISSION_REQUIRED / ROLE_REQUIRED | 原业务能力检查失败 | 真实失败，给出可发起系统动作，不能伪造授权 |
| action.cancelled / denied / stale / unavailable | 取消、拒绝、旧票据、无前台/无系统入口 | 不继续依赖业务；显示对应结果 |
| STORAGE_FAILURE / IO_FAILURE / SYSTEM_RESTRICTED | 原提交、媒体、网络/平台失败 | 按实际提交边界返失败或附带已提交回执，禁止笼统成功 |
| ai.turn_limit / ai.cancelled / ai.connection_changed | 轮数/停止/分析连接改变 | 终止链并列明已完成、未执行、待人工确认项目 |

HTTP 401/403 是认证/授权；429 是限流；5xx/超时是可重试传输错误；HTTP 400 不能一律认定容量。只读取有界错误体并识别允许列表中的结构化容量代码，413 可标为请求过大。无法识别则显示安全 HTTP 状态，不回显正文。对话没有自动网络重试；用户重试前先核对本地账本，不能重新派发已经提交的命令。

<a id="read-matrix"></a>
## 5. 全量读取覆盖矩阵（A3）

快照每次都使用以下全集，不受当前页面的 query/date/page 影响。后续查询工具可返回用户指定报表，但不能代替该完整快照。

| 域ID | 全部文本/事实 | 权威来源/新增能力 | 完整性与排除边界 |
| --- | --- | --- | --- |
| D01 callers | 全部 ProfileRecord、号码/姓名/单位/职位引用/白名单、派生关系 | BackendStore.read + profile/options 投影 | 含非3xxxx号码；不能仅 caller search 第1页 |
| D02 positions | 全部含停用职位、level/active、我的职位 | 主库 positions/myPositionId | 不只 selectablePositions |
| D03 callFacts | 全部 RawCallRecord 的业务字段、hidden/linkedScreenId/account/旧值/原始及当前分类 | 单次主库读事务 | hidden 事实可见但不重复计数；不扫描未授权系统全通话库 |
| D04 callDetails | 全部 visible 明细、合并来源/决策备注/分类更正 | 复用 detail/visible | 无分页和日期窗口；与 D03 按 ID 关联 |
| D05 statistics | 全部统计有效区间、全时段每号码 totals/quality/score、当前观察窗评分 | 主库统计区间 + ReportCalculator | 明确各范围时区与 counted；空集评分 null，非0 |
| D06 work | 当前及全部历史 schedules/configVersion/effectiveFrom、当前工作状态与 nextBoundary | 主库/WorkClassifier | 不能仅 getWorkSchedule 当前值 |
| D07 calendar | 全部已存年份来源/发布时间/版本、真实 holidays 名称/日期与 makeupDates | ScheduleStore/BackendState | 不只 CalendarInfo 的年份元数据；未保留原 JSON 不伪造 |
| D08 overrides | 全部日期调整 kind/windows | 主库 overrides | 不受日历当前年份限制 |
| D09 leaves | 全部时段/备注/状态/版本 | 主库 leaves | 含已结束/未来；状态以同一 evaluatedAt 计算 |
| D10 policy | score、quiet/mode/allNumbers/weekly、lastQuietMode、statistics/overlay | 主库 | 所有业务配置字段，包含关闭时的记忆模式 |
| D11 appearance | 选色模式/主题/自定义色/亮暗 | PreferenceStore | 不仅 summary 缓存 |
| D12 ringtone | enabled/source/displayName/previewSeconds、可测播放态 | AudioController/RuntimeProbe | 私有音频路径换为本地资源句柄/可用状态，不传音频 |
| D13 recordingSettings | recordingEnabled/automaticAnalysis/captureMode、目录配置与授权状态 | 原加密 config 的安全投影 | 目录 URI 留本地；模型见已配置/可访问/显示名 |
| D14 aiSettings | analysis/transcription protocol/url/model/keyConfigured、对话预算 | 同一配置存储 | 不序列化 AiConnection；密钥不可见且状态读取错误明确失败 |
| D15 recordingEvents | 全部独立登记、负ID、统计关联、仍存在的删除标记 | 新增 RecordingStore.captureTextSnapshot 读事务 | 与主库 calls 独立，不把 callId=负数当无效 |
| D16 archives | 未删除所有归档，全文转写/analysis所有字段、status/generation/error/attempts/next/analyzedAt/model/source/质量 | 同一录音快照事务 | getArchive/listArchives 不能取代 events/links 全集；不恢复已删正文 |
| D17 systemFiles | 已授权系统录音目录内全部可读取音频元数据/显示名/modifiedAt | SystemRecordingSource 完整枚举，签发句柄 | 未配置可合法 NOT_CONFIGURED；已配置但无权/超限报失败；无私有绝对路径 |
| D18 widgets | 全部存储绑定 + 当前宿主 widgetId 集合/支持状态 | 新 PreferenceStore.listBindings + 平台枚举 | 仅宿主ID会漏残留绑定；标识 installed/binding-only，避免混同 |
| D19 runtime | 每项权限/角色含麦克风、audibility/phoneActivity/playback/pinWidget支持、checkedAt | RuntimeProbe 安全扩展 | UNKNOWN 保留，不把打开设置当 grant |
| D20 diagnostics | 应用当前仍保存的全部诊断文本、legacy时间与来源/解析状态 | 新 diagnostics.captureStoredText | 不沿用 read().take(18) 截断旧存量；既有保留策略不必无限增长；不读取系统logcat |
| D21 audit | 仍持久化的迁移/日历审计 kind/target/raw/createdMillis | 新增主库只读快照端口在原SQL读事务查询calendar_audit | pendingAudits仅为待写队列、不是历史全集；未识别文本保留为数据并脱敏；坏行报错 |

主库 state 回调不能逃逸；捕获时深复制集合。全时段范围以实际已存调用时间 min/max 包围形成 TimeRange，使用饱和加减；无通话时 totals=0、score=null，显式空范围，不用 LocalDate.MIN/MAX 导致溢出。评分对可见 counted 调用继续复用 score；全时段与 rolling 范围分别明确，模型不得把原始隐藏事实另计一次。明细保留所有状态，即使统计未计入。

诊断保留是产品既有策略；快照完整性指“当前存储全集”，不要求恢复已淘汰历史。若现存 legacy 文本在原 read 中被泛化或截断，专门导出投影保留其存储文本，作为不可信数据并统一脱敏，不重新记入事件日志。解析失败不得省略该行；无法安全解读时返回域失败。

目录枚举继续使用内容提供器。首版可以保留 500 节点/深度4资源保护，但触及边界必须探测是否仍有未枚举项：确实存在即返回明确 enumeration_limit，不能给出 COMPLETE；不要因为恰好500项且无后续而误判。null Cursor、遍历中授权撤销、循环/重复目录标识、提供器错误均不得当作空集。目录可能在采样时变化，记录 captureStart/end 和该来源的非事务属性；两次完整枚举按文档标识、显示名、修改时间、大小排序后比较，变化则在总计3次捕获内重试，持续变化给出snapshot_changed。比较至少覆盖完整条目清单，不仅根目录时间；没有修改时间时保留unknown并比较可读取元数据，不伪造精度。COMPLETE表示本次完整遍历与稳定观测，不宣称外部提供器原子快照；若连完整遍历也不能取得则明确失败。

<a id="consistency"></a>
## 6. 捕获一致性、共同保存与失效

### 6.1 版本采样与提交栅栏

共享发布协调器仅包围短提交区，不包围全量读取、网络、文件拷贝、媒体解码或模型等待。所有生产写路径，包括 recordIncoming/importSystemCalls、录音 register/link/compareAndSet/delete、后台 workflow、偏好/铃声保存、诊断，必须参加版本发布，不能仅由工具发通知。

锁顺序固定为 PublicationFence → 业务 service/store monitor → DB/SharedPreferences 短提交。已持 store 锁的代码禁止倒过来申请 Fence；重构入口的外层顺序，不通过 SharedPreferences listener 在原锁内重入。现有回调/同步任务调度/ChangeFeed 消费不在 Fence 内执行。模型锁、UI线程、HTTP连接不能等待它们持有的资源后再取 Fence。来电路径只增加已有提交边界的版本发布，不调用快照构建或文案/模型处理；新增锁竞争必须由定向测试验证。

每个提交在同一 Fence 内完成：校验会话代次与命令 expected 的全部相关版本 → 执行原验证及事务/原子替换 → 成功递增对应域 revision → 生成回执与失效事件 → 释放；回执在取消可观察前入账。文件导入等先在锁外准备临时文件，最终发布前再次检查取消、目标generation及版本；失败清理 scratch，保留旧资源。取消之后尚未进入提交区的动作不启动；已经提交的动作不能因后续调度/媒体停止失败被改写为“未保存”。

SharedBusinessCommands 不是在 mutex 外 get 然后调用旧 save；合并/版本校验必须位于保存事务中。主库只有一个 state 回调；加密录音配置合并/完整 validate/密文 commit/失败恢复同属一次受保护更新。不同页面并发提交只碰自己的 dirtyFields：录音页的旧 analysis 副本不能进入提交。UI配置提交携带本页基准字段值/版本：另一页已改时仍在最新config合并本页补丁并保存；本页版本变化时逐dirtyField比较基准，冲突字段才拒绝。最终跨连接校验使用最新配置，若另一页修改使automaticAnalysis无法合法开启，返回真实字段错误，不把对方值回退。AI写入对其模型读依赖采用更严格的快照向量检查，不能套用UI局部基准策略继续旧计划。

captureComplete 最多3次：采 v0 → 各源完整读事务/不可变拷贝 → 采运行态/时钟 → 采 v1；任一业务版本不等则丢弃整份临时快照重试，不能拼接旧域；版本在读取期间可变但只有完整稳定一轮成功才交付。运行态时钟标注采样时间，不参与静态版本相等。快照有跨源稳定观测窗口，不宣称一个跨SQLite/偏好的全局 SQL 原子事务。外部目录核验另按第5节处理。

### 6.2 执行前冲突与依赖

模型请求在本地绑定 snapshot revisionVector。每项写入/有副作用读取绑定其读依赖域；首版允许保守绑定全部静态业务域，减少漏依赖，时间戳不作为冲突条件。首个动作执行前版本改变，整批不执行，收集全量后重算。当前批次自己成功提交的域版本更新进本地 executionVector；其后项检查更新后向量，外部改变仍会冲突。模型未收到前项结果前不推测由前项创建的新ID：同批若需要新ID，必须等下一次模型响应。

多项动作无跨域回滚事务；原子性只保证各个业务操作。每项执行后先重新captureComplete、更新执行向量并计算当前完整快照/已知回执的容量，才能执行下一项；此时不能向服务发送缺少尚未执行tool_result的协议历史，全部配对后才再次编码请求并做最终预算。任一失败后停止当前批次其余项，给未执行项结构化 skipped 结果，重新全量后再由模型解释/选择下一步；不得继续执行基于失败前提的后项。允许模型下一轮纠正字段错误，但原用户明确停止、拒绝、取消或语义禁止不自动重试。

### 6.3 ChangeFeed 与草稿

扩展 DataDomain 至 RECORDING_SETTINGS、AI_SETTINGS、RECORDING_ARCHIVES，必要时保留 domains 累积失效/全域回退；组合 backend feed 与 recording/preferences/runtime invalidation 为一个 api.changes。不能用非重放 SharedFlow 丢掉慢观察者的改变；沿用当前 StateFlow 全域失效原则或以单调revision合并缺失域。录音 compareAndSet 成功即通知状态变化，失败不通知成功。后台转写/分析每个实际持久化阶段及输入变更都走该路径。

UI无脏草稿时跟随最新值；dirty时保留草稿并标注外部变更，保存依 dirtyFields 在最新值合并，字段冲突拒绝并显示差异。不把 AI 修改覆盖用户正在输入的表单。窗口重获焦点也重新探测系统能力；服务端响应前配置改变使本轮网络结果过期，不能提交旧目标操作。录音列表、归档详情、设置、主页/组件分别按相关域刷新，不靠3秒轮询实现正确性；轮询可以保留为显示辅助。

<a id="write-matrix"></a>
## 7. 方法级工具与业务能力覆盖矩阵（A4）

每行是一项独立注册能力，固定工具名为“前缀_方法名”（方法大小写保留，例如 settings_savePosition），字符均为字母/数字/下划线。schema由本地类型适配定义，不允许额外字段。前缀：root/home/callers/reports/settings/ringtone/runtime/widgets/recordings/ai/system。读工具与快照可共用投影，仍实际调用相应业务能力；返回分页只表示指定查询，不声称全局快照。

参数记法：{}为空；原命令类型沿用 E01/E02 的字段/枚举；date 为 ISO LocalDate，时间为64位 epochMillis，数字 ID 精确解析为 Long，时区需 ZoneId 校验。外部选择只接受宿主句柄，由本地 adapter 转 DocumentRef。补丁只允许原命令对应业务字段；所有“返回设置/归档”均转换安全 DTO，不泄露密钥/私有路径。

表中 R=只读；W=业务保存/真实操作；S=等待前台系统/本地确认；→domains 为成功后失效域。所有行共同验证：参数非法拒绝、原业务错误保持、失败不报成功、结果脱敏；读取无持久化通知。V编号对应第12节，明确测试族而非已执行结果。

| ID/工具前缀.方法 | 参数/共用接口与语义 | 类别、前台及成功失效 | 验证 |
| --- | --- | --- | --- |
| O01 root.checkNumber | raw；CallringUiApi.checkNumber 原纯计算 | R | V03 |
| O02 home.load | {}；HomeApi.load | R，当前工作与运行态 | V02 |
| O03 callers.search | CallerSearch；CallersApi.search | R，原page≤200/查询校验；不代替全量 | V02/V03 |
| O04 callers.getEditor | number?；getEditor | R | V03 |
| O05 callers.getOverview | OverviewQuery；getOverview | R | V02 |
| O06 callers.getLatestCall | {}；getLatestCall | R | V02 |
| O07 callers.saveProfile | number＋资料补丁；SharedBusinessCommands → saveProfile 验证器/事务 | W → CALLERS/REPORTS | V03/V04 |
| O08 callers.setWhitelisted | number/enabled；setWhitelisted | W → CALLERS | V03/V04 |
| O09 reports.load | ReportQuery；ReportsApi.load | R，指定范围与分页 | V02/V03 |
| O10 reports.exportCsv | ReportExportQuery；真实 CSV目标选择后 exportCsv | S+W，写实际文档；结果recordCount/mime/range | V03/V07 |
| O11 settings.getSummary | {}；getSummary | R | V02 |
| O12 settings.setStatisticsEnabled | enabled；原 setStatisticsEnabled | W → STATISTICS/CALLS/REPORTS；开启可请求同步，不抹旧区间 | V03/V04 |
| O13 settings.setOverlayEnabled | enabled；原 setOverlayEnabled | W → OVERLAY_SETTINGS；启用先检查真实 OVERLAY能力 | V03/V07 |
| O14 settings.getQuietHours | {}；getQuietHours | R，快照另包含 lastQuietMode | V02 |
| O15 settings.saveQuietHours | QuietSettings字段补丁；原 validateQuiet/updateQuiet | W → QUIET_HOURS；完整字段替换显式 | V03/V04 |
| O16 settings.setQuietMode | QuietMode；setQuietMode | W → QUIET_HOURS | V03 |
| O17 settings.setQuietEnabled | enabled；setQuietEnabled | W → QUIET_HOURS，保留恢复上次模式语义 | V03/V04 |
| O18 settings.getPositions | {}；getPositions | R，含停用 | V02 |
| O19 settings.savePosition | SavePosition/id?及补丁；savePosition | W → POSITIONS/CALLERS/REPORTS，active=false即停用 | V03/V04 |
| O20 settings.deletePosition | id；deletePosition | S+W → POSITIONS；保留被引用 IN_USE 与删除确认 | V03/V07 |
| O21 settings.setMyPosition | id?；setMyPosition | W → POSITIONS/CALLERS/REPORTS，null清除明确 | V03/V04 |
| O22 settings.getWorkSchedule | {}；getWorkSchedule | R，当前值；历史在D06 | V02 |
| O23 settings.saveWorkSchedule | SaveWorkSchedule补丁；saveWorkSchedule | W → WORK_CALENDAR/CALLS/REPORTS，原时区/时段校验 | V03/V04 |
| O24 settings.resetWorkSchedule | {}；resetWorkSchedule | W → WORK_CALENDAR/CALLS/REPORTS，复用原默认与生效版本 | V03 |
| O25 settings.getCalendar | {}；getCalendar | R，元数据+调整；实际日期在D07 | V02 |
| O26 settings.synchronizeCalendar | year；synchronizeCalendar | W，网络准备在提交外；成功 → WORK_CALENDAR/CALLS/REPORTS | V03/V04 |
| O27 settings.importCalendar | 系统文件选择；importCalendar | S+W，UTF8 JSON≤1MiB与原语义校验 → WORK_CALENDAR | V03/V07 |
| O28 settings.saveDateOverride | DateOverride；saveDateOverride | W → WORK_CALENDAR/CALLS/REPORTS | V03/V04 |
| O29 settings.deleteDateOverride | date；deleteDateOverride | W → WORK_CALENDAR/CALLS/REPORTS，维持原校验 | V03 |
| O30 settings.getLeaves | {}；getLeaves | R，全部 | V02 |
| O31 settings.saveLeave | SaveLeave/id?补丁；saveLeave | W → WORK_CALENDAR/CALLS/REPORTS，保留 AllDay/Timed 语义 | V03/V04 |
| O32 settings.deleteLeave | id；deleteLeave | S+W → WORK_CALENDAR/CALLS/REPORTS，原删除确认 | V03/V07 |
| O33 settings.getScoreSettings | {}；getScoreSettings | R | V02 |
| O34 settings.saveScoreSettings | ScoreSettings补丁；saveScoreSettings | W → SCORE_SETTINGS/REPORTS | V03/V04 |
| O35 settings.resetScoreSettings | {}；resetScoreSettings | W → SCORE_SETTINGS/REPORTS | V03 |
| O36 settings.getAppearance | {}；getAppearance | R | V02 |
| O37 settings.saveAppearance | AppearanceSettings补丁；saveAppearance | W → APPEARANCE；原主题校验/偏好失败恢复 | V03/V04 |
| O38 ringtone.getSettings | {}；getSettings | R | V02 |
| O39 ringtone.setEnabled | enabled；setEnabled | W → RINGTONE；启用原PHONE_STATE/ROLE检查 | V03/V07 |
| O40 ringtone.importAudio | 系统音频选择；importAudio | S+W → RINGTONE，≤20MiB/可播放时长/原子替换 | V03/V07 |
| O41 ringtone.restoreBuiltIn | {}；restoreBuiltIn | W → RINGTONE，原恢复语义 | V03 |
| O42 ringtone.startPreview | {}；startPreview | W → RUNTIME/RINGTONE；原通话idle/可听/PHONE_STATE检查 | V03/V07 |
| O43 ringtone.stopPlayback | {}；stopPlayback | W → RUNTIME/RINGTONE | V03/V05 |
| O44 runtime.getStatus | {}；getStatus | R，真实探测含麦克风扩展 | V02/V07 |
| O45 runtime.getDiagnostics | {}；getDiagnostics | R，原界面窗口；D20是完整已存投影 | V02 |
| O46 runtime.requestCallLogSync | {}；requestCallLogSync | W → RUNTIME，真实CALL_LOG检查；QUEUED或ALREADY_QUEUED | V03/V05 |
| O47 widgets.getBinding | widgetId；getBinding | R | V02/V03 |
| O48 widgets.saveBinding | widgetId/number；saveBinding | W → WIDGET_BINDINGS；id/号码校验 | V03/V04 |
| O49 widgets.removeBinding | widgetId；removeBinding | W → WIDGET_BINDINGS | V03/V04 |
| O50 recordings.listArchives | {}；listArchives | R，全部未删归档安全投影 | V02 |
| O51 recordings.getSettings | {}；getRecordingView/getAiView 的安全组合 | R；内部仍读取原getSettings，不直接回传 | V01/V08 |
| O52 recordings.saveSettings | RecordingSettings的非敏感补丁；patchRecording/patchAi共用原saveSettings校验 | W → RECORDING_SETTINGS/AI_SETTINGS；模型无apiKey/systemFolder URI字段 | V01/V03/V04 |
| O53 recordings.testConnection | slot=analysis/transcription；原 testConnection 使用本地当前凭据 | S+网络，固定测试文本/音频告知；无业务上传 | V03/V06/V07 |
| O54 recordings.getArchive | callId；getArchive | R，尊重负ID/旧统计关联 | V02/V03 |
| O55 recordings.importRecording | callId；宿主选音频或系统项句柄后 importRecording | S+W → RECORDING_ARCHIVES；本地解析权威number/startedAt/source，≤256MiB与原验证 | V03/V07 |
| O56 recordings.retry | callId；retry | W → RECORDING_ARCHIVES；原连接校验；QUEUED，非已分析 | V03/V05 |
| O57 recordings.delete | callId；delete | S+W → RECORDING_ARCHIVES；原删除确认/代次失效/文件清理 | V03/V05/V07 |
| O58 recordings.play | callId；play | W → RUNTIME；原ownedFile/播放generation | V03/V05 |
| O59 recordings.stop | {}；stop | W → RUNTIME；使待prepare失效 | V03/V05 |
| O60 recordings.setSystemFolder | 前台目录选择；setSystemFolder | S+W → RECORDING_SETTINGS；真实持久READ授权 | V01/V07 |
| O61 recordings.systemRecordings | {}；systemRecordings完整性改造 | R，宿主句柄而非任意URI；超限报错 | V02/V07 |
| O62 widgets.listBindings（新增） | {}；全存储绑定+installed集合 | R | V02 |
| O63 recordings.adoptIdentity（新增共用操作） | callId；adoptSelfReportedIdentity | S+W → CALLERS，保留职位/白名单/非空自述语义 | V03/V04/V07 |
| O64 ai.getSettings（新增） | {}；getAiView | R，analysis/transcription安全状态与预算 | V01/V08 |
| O65 ai.patchSettings（新增） | analysis/transcription非敏感字段/预算补丁；patchAi | W → AI_SETTINGS；分析连接改变终止当前链 | V01/V04/V08 |
| O66 ai.editCredential（新增） | slot；本地凭据表单Keep/Replace/Clear，再共用保存 | S+W → AI_SETTINGS；回执仅keyConfigured/版本 | V01/V07/V08 |
| O67 system.requestCapability | Capability（增加MICROPHONE）；共用前台请求+probe | S；GRANTED/DENIED/UNKNOWN/UNAVAILABLE，→ RUNTIME | V07 |
| O68 system.openApplicationSettings | {}；固定应用设置Intent | S；返回实测状态，无强制修改，→ RUNTIME | V07 |
| O69 system.pinWidget | {}；共用pinWidget | S；请求已提交或安装已核验；不要用true冒充installed | V07 |
| O70 system.testOverlay | {}；共用testOverlay | S；能力检查/固定测试卡启动，不能声称后台来电已验证 | V07 |
| O71 system.previewCall | callId；由业务查真实CallDetail后共用previewCall | S；实际显示能力检查，不能模型构造号码或私有Intent | V07 |
| O72 ai.copyConnection（既有UI动作共用化） | from/to=analysis/transcription；本地连接复制操作/目标预览保存 | S+W → AI_SETTINGS；内部复制凭据只返回状态，向transcription复制沿用强制OPENAI语义 | V01/V03/V07/V08 |

新增录音页 patchRecording 是 O52 非敏感录音字段的直连 UI API，AI 同用；用户不能通过 O52 越过 O60 的目录选择。testConnection 的 UI 可测试尚未保存的本地草稿，工具只测试已保存 slot；两者最终用同一连接验证/固定测试客户端，不向模型返回测试凭据。所有 unsafe 旧返回类型经过安全 mapper 后才进入 ToolReceipt。

O55 系统项关联路径允许引用 O61 签发且仍有效的句柄直接进入共用 importRecording，不强迫重新选择文件；来源/归属由宿主登记，模型不能把 IMPORTED 改为 SYSTEM。独立录音已存在则以录音权威身份解析，旧正统计ID仅由原关联逻辑解析，禁止模型提供number/startedAt拼凑目标。报告导出仍遵守 ReportsApi CSV防公式注入与实际记录数。系统添加小组件不写任意widgetId；绑定保存复用原 widgetId/number 校验并回报宿主存活状态。

O72将现有copyAnalysisToTranscription/copyTranscriptionToAnalysis从ViewModel纯copy移入本地设置服务，AI发起时打开本地设置的目标预览/保存，不把源密钥返回模型。源/目标slot版本绑定到预览票据，变更则失效重取，两个slot始终在一次受保护配置更新中复制/校验/保存。ordinary非敏感字段复制可由O65补丁完成；含凭据复制必须经过本地表单。

ChangeFeed.observe 是基础设施订阅能力，不注册为无限流模型工具；通过每次快照/工具结果体现。所有读能力 O01–O61 均须有路由，不能以“快照大概已经有”跳过映射核对；其安全投影不改变权威服务语义。

<a id="state"></a>
## 8. 会话、轮次、执行账本和取消

ConversationState 由 application ConversationService 独占更新，Flow只读发布。Session含随机sessionId、generation、完整用户/已确认模型历史、operation ledger、lastSnapshot摘要、目标连接版本/告知状态。轮次含turnId、phase、requestSequence、pending batch、已提交与未执行回执。全文不落 SharedPreferences、SavedStateHandle、日志或进程恢复包；切页/旋转复用同服务，应用进程退出后清空并在页面说明。

阶段：IDLE → CAPTURING → BUDGETING → REQUESTING → VALIDATING → EXECUTING / WAITING_FOREGROUND → CAPTURING → … → COMPLETED；任意阶段可进入 STOPPING → STOPPED 或 FAILED。执行账本独立于模型网络Job；STOPPING必须允许当前已经进入不可取消提交区的动作完成并入账，再公布 STOPPED。busy 的 send 直接拒绝；停止按钮保持可用，停止不会等网络readTimeout才有反馈，客户端断开连接且旧requestSequence响应无效。

operationKey=(sessionId,turnId,providerCallId)，另存规范参数摘要。重复同key同参数重放缓存回执，绝不再执行；同key异参数协议失败。在触发副作用前写入 IN_FLIGHT 记录；完成时记录真实状态。进程内异常发生在提交与回执之间时优先通过提交证据/业务实体版本核对；不能证明则 UNKNOWN，不自动执行第二次。进程死亡后不恢复任何待执行任务，不承诺跨进程 exactly-once。

响应给出多个工具时先验证全批，再串行执行。达到单批8项、单用户轮次最多16次模型请求/32次工具调用的上限则整批或后续轮明确停止（同批超过8时一个也不执行）。本地提供停止/超限说明及全部已提交回执，不需要模型再生成一句“成功”。不同副作用的读依赖由注册表声明，不能由模型省略。

未完成工具批次终止时为每个未执行callId写本地 CANCELLED/FAILED/skipped 回执，协议历史保持工具请求/结果配对；下一用户轮先完整捕获，只把已终结且成对历史发给服务。协议已损坏无法配对时该会话标记需新对话，保留本地可查看操作回执，不通过删除历史来继续。

切页不丢会话；前台宿主进入后台时不自动发起新的平台动作。已有普通网络请求可完成只读解析，但任何后续写/平台动作执行前要求应用具备当前有效的前台会话lease，否则 WAITING_FOREGROUND；用户回到前台后重新校验版本再继续。不会在应用无人可见时悄悄展开新的操作链。

<a id="capacity"></a>
## 9. 容量、网络与两种工具协议

### 9.1 明确的容量策略

首版新增 clientContextTokens 默认32768、outputReserveTokens 默认4096；它们是客户端保守上限，页面明确“请按服务实际上下文调整；估算不保证兼容服务接受”。验证 context 为8192–1048576，reserve为1024–16384且严格小于context，checked算术防溢出；上限不意味着任何模型具备此容量。字节上限请求8MiB、响应1MiB，单条用户输入32KiB；超限保留草稿并显示，不截断。

为避免先分配巨大全文后才发现超限，快照端口在各源读事务内先检查可计数的行数/文本长度，再生成投影；归档cursor累计正文大小，在达到8MiB安全输入字节或20000业务行资源阈值时丢弃整个临时快照并报容量错误，不产出部分列表。主库专用快照端口的轻量计数应在加载完整BackendState之前执行，不能只是原read结束后的length检查。总量用checked Long累计；JSON转义后可能增大的实际请求仍需最后预算。没有分页续用，也不通过捕获OutOfMemoryError当常规容量控制。限额是明确的首版资源保护，与模型上下文上限分别显示。

先把完整逻辑消息、工具schema、全文快照、已有工具调用结果和系统提示进行脱敏，编码为即将发送的协议请求。预算 inputEstimate = 所有输入文本与schema UTF8字节数（1字节按1个估计token）＋逐消息/工具协议固定余量1024；再加 outputReserveTokens 与max(1024,10% inputEstimate)安全余量，要求总和≤clientContextTokens，且实际serialized请求≤8MiB。这是故意偏保守的工程估计，非任意服务tokenizer的数学保证；真正容量拒绝按专门错误处理。记录各部分大小以解释超限，不记录内容。

每次请求重新预算：包含完整会话、当前完整快照、系统提示、注册工具全文、全部工具结果、协议开销、输出预留。快照作为当前请求专用context块替换上次context块，不把旧快照追加成会话消息；完整用户/助手/工具历史保持原样。旧快照不是历史对话的一部分，不伪称自动压缩历史；各历史回执带原snapshotId和时间以免把旧事实认作当前。

工具提交后若新全文或结果超限，停止下一次模型调用与其余待执行动作，显示已提交操作，不能拿提交前旧快照补发。新对话只降低历史体积；业务全集本身超预算时明确首版无法处理，建议调整服务预算或等待经审批的渐进读取版本，不替用户偷偷省略转写、档案、工具定义或前文。

### 9.2 传输与协议边界

HTTPS连接、AiConnection URL限制、15秒连接/90秒读取超时、禁重定向、认证头与异常不保留密钥沿用现有约束。首版非流式；完整有界读完后 StrictJson 验证，无工具执行早于 parse/validate 完成。工具schema完整发送，不能因容量不足只发部分工具并仍声称能力齐全。对于无工具协议支持，返回 ai.protocol_unsupported 并建议切换可用服务/模型，绝不解析自然语言为“已经执行”。

OPENAI 兼容路径保持 chat/completions：assistant.tool_calls 对应本地 ToolBatch；工具结果role=tool并携带原tool_call_id；只接受一项choice。finish_reason=stop且无工具为Final，tool_calls且非空合法调用为ToolBatch，length/content_filter/未知/旧function_call/拒绝为错误；stop却附工具也拒绝。服务支持时parallel_tool_calls=false，本地仍按串行约束处理。arguments必须完整JSON对象并由本地schema验证。协议与配对依据[OpenAI官方Chat Completions参考](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)。

ANTHROPIC 兼容路径messages：system与messages分开；完整保留assistant的text/tool_use块，在下一条user消息中先放全部tool_result、关联tool_use_id，并在失败时is_error=true；本地仍串行执行。只接受end_turn且无工具为Final、tool_use且非空合法块为ToolBatch；max_tokens/refusal/未知停止/不支持块报错。依据[Anthropic官方工具调用说明](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)。不启用server tools、thinking、shell、computer或自动URL访问。

官方文档在2026-10-18核对；协议枚举 OPENAI 表示既有 Chat Completions 兼容服务，不保证覆盖所有最新 OpenAI 模型。官方说明有模型工具调用要求 Responses API，首版不能因协议名而假定支持；见[官方Function calling指南](https://developers.openai.com/api/docs/guides/function-calling)。当用户实际配置的模型要求Responses时明确不支持，再以单独需求评估第三协议适配；不在本任务暗中切换endpoint或模型。

连接测试 O53 维持已有固定分析/转写测试；它不证明工具调用兼容。AI页另显示“对话工具兼容性未验证/最近验证结果”；可用无副作用本地echo工具假数据做兼容验证，同样明确发送测试，不能自动把真实业务全集用作连接测试。

<a id="authorization"></a>
## 10. 密钥、授权与前台动作

### 10.1 密钥与指令边界

模型所有请求正文、消息、返回DTO、诊断/错误均只有 keyConfigured（凭据服务错误则fail，不是false）。apiKey只存在原本地加密配置、专用设置表单短期内存、认证头；原 AiConnection.toString 的redacted不能替代安全mapper。凭据编辑、复制分析/转写连接由本地服务处理，工具不能读取实值；UI复制仍保持明确用户动作，输出仅两个slot的状态。

出站先按白名单投影，再遍历所有字符串值，对本地当前凭据实值的原文及已有转义/常见URL编码变体替换兜底，替换为[redacted]并统计数量，之后重新编码JSON；不得对原始JSON字节全局替换而破坏结构字段或引号。对业务文本命中同一密钥也脱敏，manifest声明此安全变换，不无声遗漏业务尾部。检查编码后的值叶子和完整请求哨兵，再预算实际字节，认证头不在此检查范围（认证本来需要密钥）。空凭据不参与替换，脱敏标记不递归处理，短凭据也不能导致无限替换或修改固定schema。新输入不得把密钥当聊天内容；本地检测命中配置凭据时提示改用本地设置并对发送副本脱敏。

历史和日志不得记录凭据；工具参数被拒绝时错误不能原样引用含密钥输入。密钥更新前取消并使当前对话链失效，清理/重新脱敏内存消息中旧凭据匹配，旧凭据不得留在已编码待发请求。没有可靠解析结构的provider错误不回显，避免认证密钥被网关错误回传。私有音频路径和可调用URI仅留本地资源表，模型见显示名/类型/可用状态/短期句柄。

用户消息是当前目标来源；转写、分析、档案、日历备注、diagnostic和工具自由文本都作为带域标签的JSON数据，不拼入system指令。模型选择工具仍必须经过注册表、原权限与业务校验；业务文本中的“删除全部/发到某地址”等不能创建授权。没有通用网络外发工具或模型自定义URL打开器。首次发送显示完整文本外发说明、当前协议/服务host/model、录音二进制不上传；确认记录只在进程内与目标连接版本关联，不另加每次普通保存统一确认。

analysis 的protocol/url/model/credential任一改变时，立即取消当前HTTP并冻结后续工具，持久化成功回执在本地展示，标记CONNECTION_CHANGED；新目标需重新展示外发说明，等待用户下一次发送。不能在同一工具链改变endpoint后把全集自动发给新服务。transcription改变不转移当前对话目标，但使依赖配置的后续业务计划按版本冲突重算。

### 10.2 系统票据与宿主

PendingAction包含 requestId、sessionId、turnId、toolCallId、generation、actionType、targetId、canonicalArgumentsDigest、expectedVersions、createdAt/expiry、status。类型限 REQUEST_CAPABILITY、OPEN_APP_SETTINGS、PICK_AUDIO、PICK_CALENDAR、PICK_DIRECTORY、CREATE_CSV、PIN_WIDGET、PREVIEW_OVERLAY、CONFIRM_EXISTING_OPERATION、EDIT_CREDENTIAL。只保留一个活动票据，默认10分钟有效；超时明确EXPIRED，用户主动继续时签新票据而不自动重弹。

状态 CREATED → WAITING_FOREGROUND → CLAIMED(host lease) → LAUNCHED → RESULT_RECEIVED → VERIFIED → CONSUMED；可由任一步转 CANCELLED/DENIED/EXPIRED/STALE/UNAVAILABLE。beginLaunch 原子防重，只允许可见RESUMED宿主；旋转重新attach但不能再次launch已LAUNCHED票据，ActivityResult注册key稳定并把requestId映射保留在应用服务。所有回调先比对票据身份/代次/目标/参数/有效期，匹配后消费一次；UI取消必须调用deliver(Cancelled)，不能像现有选择器一样忽略null。

收到系统结果后真实核验：权限/角色重新probe，resultCode不等于GRANTED；目录content/tree+持久READ授权成功，音频/日历/CSV验证content URI、实际访问模式与来源，不将句柄传成任意文件路径。用户在系统页自行改了配置，应重新读取完整快照并检查目标版本；不得因“回来了一次”继续旧写操作。确认弹窗对应的目标若已变(如归档generation或职位引用变化)则失效，再发新确认，不能复用旧确认删除新内容。

DocumentSelectionStore在本地将随机句柄关联uri/授权模式/actionId/目标/过期时间。模型参数只可提供当前session签发且仍有效的句柄。只读系统录音项可在来源实际已授权、仍存在/元数据匹配时使用，最终import再验证；不能凭句柄绕过scope。取消前台等待、newSession、stop、进程退出一律失效；迟到结果释放临时资源/权限，不执行依赖操作。

麦克风加入公共Capability.MICROPHONE与UiLabels/RuntimeProbe/拒绝提示穷尽映射；patchRecording设置MICROPHONE_EXPERIMENTAL时共用服务真实检查录音许可，UI与AI一致。能力授权被拒绝不修改captureMode。其它已有能力沿用原用途，不把设置入口当强制grant。

pinWidget的requestPinAppWidget返回true仅LAUNCHED；若接入系统回调仍核对真实widgetId安装状态才表示完成。无确认时显示待桌面确认/结果未知。测试/预览悬浮窗能验证启动服务则LAUNCHED，只有收到实际显示回证才报告显示成功；不由此断言后台来电支持。授权/角色无系统入口显示UNAVAILABLE，保留实际能力状态。

<a id="compatibility"></a>
## 11. 配置兼容、持久化和降级

保留现有 namespace_recording SharedPreferences的config、Keystore别名callring.recording.<namespace>与analysis/transcription加密结构，不迁移到明文或新别名。新增对话预算写入同一加密JSON的可选versioned子对象，旧配置读取缺省预算；新codec保留已有字段并测试旧fixture。没有可读密文时报告配置读取失败，不用默认配置覆盖。初始化缺少config才允许默认值。

保存先成功解密/合并/原RecordingSettings.validate（转写OPENAI、automaticAnalysis跨连接校验）/新增预算验证，再加密并commit；commit失败恢复 touched keys 对应的内存旧值并报告失败，不能让 SharedPreferences内存假保存。两个页面不写未知字段；本地密钥Keep不做清空。单独Clear密钥若令尚有URL/model的连接不合法，仍返回原校验错误；用户要停用整组连接时由本地表单明确提交URL/model/credential清空及必要的automaticAnalysis关闭，不自动替用户清其它字段。systemFolder只通过目录授权命令修改；自动分析开关留在录音页但校验仍依赖两连接，不把“拆页”变为降低验证。

保留录音数据库schema、负ID登记/正统计ID关联、generation/CAS/删除标记语义，新增快照查询及版本发布不需要把全文搬进主业务库。新增接口在测试假实现/UnavailableRecordingApi明确不支持，不返回空全集假成功；生产BackendGraph必须装配完整能力。若平台某必需读取不可用，沟通页显示对应完整快照失败而其它原页面可继续使用。

保留原导航深链/通知target=setting,page=RECORDING；新增AI页和conversation映射，不把旧入口悄悄导航到空白。更新when穷尽处理、测试标签与四个图标。预设色/组件绑定、统计历史区间、后台录音调度不重置；应用重启无对话历史，业务持久化结果仍在原服务可见。

新代码回滚兼容重点是旧配置可读、Keystore不换、独立录音库不重建。旧版本程序保存时可能丢新增预算，因此不承诺任意降级版本保留新增字段；其analysis/transcription等旧字段必须保持可读。如codec必须升级不可逆格式，应作为范围变化交主负责人确认，不在实现中顺带迁库。

<a id="verification"></a>
## 12. 验证契约与验收证据

本节列待实现验证，不是本轮已运行测试。每项测试必须断言权威存储/原API结果和真实副作用次数，不能仅断言工具返回字符串包含“成功”。注册表映射测试从本矩阵生成/核对O01–O72覆盖，针对新增补丁/CAS/系统票据优先行为测试。

| 测试族 | 必须覆盖的事实与故障 | 验收 |
| --- | --- | --- |
| V01 配置拆页与兼容 | 旧加密fixture/两密钥保持；双页面A录音后B AI再A保存不覆盖；dirtyFields合并、Keep/Replace/Clear、自动分析跨域验证；解密/commit失败旧值不丢；目录字段不能由普通补丁写入 | A1/A4 |
| V02 全量快照 | >200档案/跨年通话、UI不同page/date、非规则号码、hidden+merged、负ID独立录音、已删正文不恢复、全文尾部哨兵、所有分析字段；历史schedule/真实节假日补班/统计区间/组件存储残留；诊断存量尾部；empty/failed/unavailable区分；三个并发捕获失败明确 | A3 |
| V03 共用业务能力 | O01–O72逐项注册/类型/未知字段校验/原业务调用；负ID处理、档案职位引用/IN_USE/时间时区/评分/CSV/媒体限制；ApiResult.Failure从不转成功；采纳仅自述且保留职位白名单 | A4 |
| V04 并发与刷新 | UI↔AI、录音后台compareAndSet每阶段通知；版本在提交锁内检查；旧计划失效；导入准备期间restore/删除/配置修改；脏草稿不覆盖；跨存储向量捕获与ChangeFeed慢观察者不漏域；锁序无反转且来电路径不等网络/大文件 | A1/A4 |
| V05 工具链与取消 | 批次非法工具零执行、串行/部分失败skipped、sameID同参缓存/异参拒绝、超时/UNKNOWN不重放、stop后已提交保留/未提交不执行、分析/同步只显示QUEUED、播放代次/删除期间迟到完成不能复活、新对话旧回调失效 | A2/A4/A6 |
| V06 协议与容量 | 两种本地假HTTP服务器：完整工具请求结果配对/多工具/stop reason/截断/拒绝/错误体；原工具无支持明确报错；预算包含全部输入+reserve，边界checked算术；预算超限零网络零工具；每次工具后重算超限停止；400非容量与明确context拒绝区分；请求/响应字节上限，不静默缩减 | A2/A5 |
| V07 前台桥 | 真grant/deny/cancel/UNKNOWN/无入口/无宿主/过期；旋转只launch一次、进程死亡无票据、重复/迟到回调；真实content/tree授权、伪造URI/句柄/来源拒绝、导入目标变更重确认；麦克风拒绝模式不变；widget/overlay只launch不伪完成 | A6/A4 |
| V08 密钥边界 | 两slot密钥哨兵置于配置/档案/转写/诊断/工具错误/网关回显；遍历序列化请求、每类工具结果/会话/日志无实值；只认证头存在；endpoint改动终止链，旧/新凭据历史清理；无密钥SavedState或落盘历史 | A3/A4 |
| V09 界面与导航 | 窄屏bottom/wide rail四入口、图标/外部目标；两设置页/未保存草稿/密钥本地编辑；发送/读取/请求/执行/等待系统/失败/停止/部分已完成/超限；旋转切页保留，重启明确新会话；前台lease与错误操作提示 | A1/A2/A5/A6 |

实现阶段基础验证命令：在仓库根运行 .\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug（按实际任务命名核对）。当前build.gradle为compileSdk36/minSdk29/target33，不能按PROJECT.md旧SDK33注释配置构建。构建工具问题按真实错误记录，不以文档审阅替代构建通过。

设备条件具备时使用隔离backendQa/测试包做权限、角色、SAF导入/目录/CSV、系统返回、pinWidget、悬浮窗启动与回证测试；不向真实付费服务自动发送全集。真实SIM/ROM后台限制/真实兼容模型是否支持工具单列未验证状态。设备自动测试不得删除用户主包数据或改变与本需求无关设置。

| 需求/验收 | 设计锚点 | 必需证据 |
| --- | --- | --- |
| R1/A1 | interfaces、consistency、compatibility、write-matrix | V01/V04/V09与旧配置fixture |
| R2/A2 | components、state、capacity | V05/V06/V09及两种屏宽导航截图/断言 |
| R3/A3 | read-matrix、authorization、consistency | V02/V08，域manifest计数/尾部哨兵与安全序列化 |
| R4/A4 | interfaces、write-matrix、consistency | O01–O72逐项映射清单、V03/V04/V05及实际API调用/通知证据 |
| R5/A5 | capacity、errors、state | V06/V09，超限零执行与工具后超限保留提交回执 |
| R6/A6 | authorization、state | V07/V09，成功拒绝取消/旧重复/旋转、launch与完成区分 |

<a id="tradeoffs"></a>
## 13. 取舍、演进条件与交付界限

| 决定 | 本次依据 | 代价/风险 | 可启动演进的证据条件 |
| --- | --- | --- | --- |
| 全量快照＋全注册工具 | A3/A5优先完整可核验，现有主库已可全量加载 | 数据/工具定义大时明确不可用，内存/延迟上升 | 真实超限样本及各部分大小证明全集无法承载，才另设按需读取覆盖证明/分页游标/版本协议；不能仅为“以后可能大”引入检索 |
| 沟通复用analysis连接 | 原有两连接与密钥配置已经存在，首版简单 | 某分析模型不支持工具 | 真实兼容失败/用户明确要不同模型，再考虑独立conversation slot；不自动复制或改模型 |
| 进程内单会话 | R2未要求跨重启，避免凭据/业务全文新增落盘 | 进程退出失去对话但保留业务提交 | 用户明确需要重启续聊后单独设计加密、删除/保留周期和跨进程幂等恢复 |
| 独立录音存储 | 已有统计解耦、Telecom路径与大转写隔离 | 跨源只能稳定采样，不能全局SQL原子 | 只有实际一致性失败不能通过版本采样解决时才评估持久版本或事务方案，不迁移音频全文入来电库 |
| 提交栅栏与乐观版本 | A1/A4不丢并发，原服务已具备事务与非取消提交 | 接入全写路径与锁序需审查 | 有实测锁竞争导致来电处理退化才细分锁/版本域；保持原子校验与提交不分离 |
| 非流式、串行工具、有限轮次 | 避免半段JSON执行，真实回执易核验 | 首字延迟较高，复杂任务到上限需用户继续 | 有交互延迟证据再加“只显示文字流、完整后才工具”方案，不流式执行写入 |
| 保守预算与明确拒绝 | 未知兼容服务不能假定无限容量 | 高估可能提前拒绝 | 已知模型且有可靠分词器/usage校准再提高估算精度，保留服务端拒绝和字节保护 |
| 仅Chat Completions/Anthropic messages兼容首版 | 当前配置枚举与原endpoint路径 | 新模型可能要求Responses | 有实际选定模型/需求后批准新增协议，独立测试全部状态/工具配对，不能假定换URL即兼容 |

本阶段仅提交此文档。实现工程师需基于获批版本给出实现设计与实施计划，分别批准后再改产品代码；本架构没有批准任意代码路径或自动创建其他会话。实现需同步更新README的四导航、两设置页、完整文本外发、进程会话与容量限制；文档和真实结果必须一起验收。架构审定后若范围/接口契约要改变，逐项说明对A1–A6及既有QID的影响，保留有效结论和已有成果。

<a id="discussion-ledger"></a>
## 14. 已确认问题账本

以下保留Q01–Q22原问题正文、原建议结论与证据标识；“待确认”等措辞是revision 1原文的历史表述。主负责人已确认方向进入epoch 2，当前规范决策以第1–13节为准，不能把历史表述解释为本架构已经获批。

### Q01

问题：R1/A1：拆页如何保留现有录音、分析、转写配置及 Keystore 密钥，避免旧页面覆盖新值？

原结论：建议保持原加密 config 与密钥别名，拆分页面和公开 DTO。录音页仅改 recordingEnabled/automaticAnalysis/captureMode/systemFolder；AI 页改 analysis/transcription 与对话预算。统一存储边界内以字段补丁合并最新值、执行原完整校验并提交；读取后在 UI copy 再整体 save 不能避免竞态。密钥更新三态 keep/replace/clear 只由本地设置表单传递，普通页面保存默认为 keep；失败不重置旧密钥。同页并发冲突用版本拒绝或明确提示。

依据：E02、E08、E15。

### Q02

问题：R2/A2：一级导航、会话寿命及页面状态采用什么最小首版方案？

原结论：建议首页/档案/AI沟通/设置四入口共用同一导航清单，覆盖底栏/侧栏及外部目标映射。进程内单会话由 application 所有，切页和旋转不丢失；进程退出后新对话，页面明确说明。呈现读取、请求、执行、等系统操作、已停止、失败和完成状态，以及发送/停止/新对话、真实操作回执。首轮发送前说明完整业务文本将发往当前配置服务，不含录音二进制。

依据：E14、E12、E15。

### Q03

问题：R2-R5：首版用完整快照、按需检索还是整份配置覆盖；连接独立还是复用？

原结论：推荐完整文本快照＋具名工具调用，复用现有分析连接，转写仍独立 OPENAI；减少第三套凭据和首版检索遗漏。备选按需查询可扩容但需覆盖/分页证明，留后续；整份配置覆盖易丢并发且绕过业务语义，拒绝。兼容协议不支持工具时明确报错，不能退化成模型文本伪执行。现有 CompatibleAiClient 明确拒绝工具，不直接复用 analyze 返回 CallAnalysis 的路径；新增对话适配器并复用可提取的连接/网络约束。

依据：E10、E02。

### Q04

问题：R3/A3：全部业务文本的读取范围如何证明，能否靠页面与报表拼装？

原结论：建议新增应用层全量导出投影，非 UI 分页拼装；域清单包括全部档案(含非规则号码)/职位/白名单/我的职位、全部持久化通话事实和关联/可见性标记、统计区间、派生统计、历史及当前工作配置、实际节假日与补班日期及来源、日期调整、全部请假、评分、免打扰及记忆模式、悬浮窗、外观、铃声、录音和AI设置、组件绑定、运行权限角色/播放状态/现有诊断、迁移审计业务文本。序列计数器不属于业务正文。每域给数量/版本/时间/完整状态；空域必须与读取失败区分。

依据：E03、E04、E01。

### Q05

问题：R3/A3：隐藏/合并通话、统计公式与观察窗应怎样表示，避免全量事实和去重统计相冲突？

原结论：建议全量事实带 hidden/linkedScreenId/counting/source/原始及更正分类和旧枚举文本，另给遵循 visible 投影的业务明细与权威聚合，不把关联记录重复计数。全量事实不等于全部历史都计入当前评分；同时输出每号码全时段统计和当前 observationDays 评分，范围/时区明确，继续复用 ReportCalculator，不靠模型重新发明公式，也不枚举所有可能日期范围。系统未存入应用的数据不得伪称已读取。

依据：E05、E06、E04。

### Q06

问题：R3/A3：统计停用时独立录音如何完整读取，删除内容与系统目录又如何界定？

原结论：建议独立枚举 recording_events、统计关联、未删除全部 archives，保留负ID语义，不从统计 calls 反推录音全集。归档包括全文转写、分析全部字段、代次/状态/错误/重试与模型元数据；已删归档不恢复正文，只保留仍存在登记/删除状态。只处理应用存储及已授权目录元数据，不扫描未授权文件或上传音频。现有 systemRecordings 在深度>4或500节点静默停止，若纳入完整枚举须改为明确容量/访问错误或带不完整失败，不能当全量成功。

依据：E08、E09、E17。

### Q07

问题：R3/A3：多数据库、SharedPreferences 和易变运行状态如何保持可信快照？

原结论：建议业务主库单次 read 复制不可变投影，录音库同样在自身读事务内全量复制；偏好/铃声等各自快照。以各域变更版本前后核验、有限重试，持续变动报 snapshot_changed；补齐所有写入口(含后台 workflow)版本和通知。运行状态是标注采集时间的实测值，不宣称跨存储绝对原子，也不因时钟字段变化无限重试。不得跨网络持有数据库/全局锁。全量域缺失、解密/解析失败明确阻断，不能默认空集合继续。

依据：E18、E07、E08。

### Q08

问题：R3/A3与R4/A4：密钥只可见配置状态与所有配置可修改如何同时成立？

原结论：建议模型连接 DTO 只有 protocol/url/model/keyConfigured，绝不直接序列化 AiConnection；本地凭据层仅向网络认证头提供实值。模型可改非敏感连接字段，密钥新增/替换/清空由 AI 发起本地专用设置界面并用真实保存结果回执，工具参数不接受 key 实值。请求正文、历史、工具结果、错误和日志统一脱敏；URL 沿用禁止 userinfo/query/fragment 校验。凭据异常仅报不可用，不能误报未配置。模型引用/回显业务文本若碰巧含配置密钥也必须在出站边界去除。

依据：E02、E08、E10。

### Q09

问题：R4/A4：配置覆盖矩阵到底必须列出哪些既有动作？

原结论：建议逐项映射：统计开关/悬浮窗；免打扰 get/save/setMode/setEnabled(记忆语义)；职位保存/停用/删除/我的职位；工作时间保存/重置；日历同步/导入/日期调整保存删除；请假保存删除；评分保存重置；外观保存；铃声启停/导入/恢复/试听/停止；录音及AI补丁、两连接测试、目录设置。均调用共用业务API/校验保存，不引入 SQL/脚本工具。完整矩阵须对照每个 API 方法，标明读/写/系统动作/错误/通知/验证，不止举例。

依据：E01、E02、E07。

### Q10

问题：R4/A4：业务操作、档案采纳、报表和组件的全面范围如何定义？

原结论：建议覆盖 checkNumber、档案搜索/编辑读取/概览/最新通话/保存/白名单，报表 load/exportCsv，录音 list/get/import(文件或已授权系统源关联)/retry/delete/play/stop/systemRecordings，以及 widgets get/save/remove 和固定桌面入口、运行状态/诊断/请求同步、测试悬浮窗和现有来电预览入口。采纳自述姓名单位需抽出 UI 当前 getEditor+saveProfile 组合为共用应用操作，保留职位/白名单，不采纳推测。禁止凭空新增档案/通话删除或强制系统设置。

依据：E01、E02、E15、E12。

### Q11

问题：R3-R4：小组件绑定及麦克风权限在现有接口的缺口如何补齐？

原结论：建议 PreferenceStore/WidgetBindingsApi 增全量绑定读取，枚举已有存储绑定并与宿主 widget IDs 区分；目前仅 getBinding(id) 不能证明全集。麦克风权限目前在录音路由单独 RequestPermission，未列入 Capability，须纳入共用平台能力模型或明确独立类型，AI 可发起并获得真实结果；录音模式保存也执行相同能力约束，不能绕过 UI 前置检查。

依据：E11、E18、E12、E02。

### Q12

问题：R4/A4：旧快照/旧表单如何避免覆盖 UI 的最新配置或资料？

原结论：建议 AI 参数是精确字段补丁，执行时在应用层基于最新记录合并并走原验证；对有依赖的读后写带期望版本，冲突报 changed 并重新取全量后重算，不盲目重放。该补丁/合并服务同时供 UI 使用，至少先覆盖录音与AI拆页及所有 AI 修改面；不能只在模型工具外先 get 再 save。涉及整体列表的替换须明确语义及版本，不把未提供字段当清空。

依据：E07、E15、E01。

### Q13

问题：R4/A4：AI/界面双向刷新如何覆盖录音后台状态和未保存草稿？

原结论：建议将录音设置/AI设置/录音档案纳入统一 ChangeFeed(或组合 feed)和版本元组；后台转写/分析状态、导入删除、目录/配置、权限返回均发失效。UI 已保存数据与组件按域刷新；脏草稿不被后台刷新悄悄替换，保存冲突明确提示。对话每次发送/工具后读取最新完整快照，执行前校验版本；工具结果有真实提交状态，UI变化使待执行旧计划失效重算。

依据：E07、E09、E15、E14。

### Q14

问题：R4/A4：多工具、错误、异步排队、取消与重复调用采用什么结果契约？

原结论：建议首版单会话串行工具，严格工具名/字段/类型/数量校验，不并行执行模型给出的写入数组。以(sessionId,turnId,toolCallId,参数摘要)缓存真实结果，同ID不同参数报协议错，重试不重放成功写入。每项区分 committed/queued/launched/cancelled/denied/failed/unknown；请求通话同步或 retry 分析只算已排队，不算业务完成。停用/取消阻断后续操作，已提交操作依现有 NonCancellable 契约保留并展示；结果丢失需核验，不自动重试非幂等写。部分失败不能声称整批成功。

依据：E07、E01、E02。

### Q15

问题：R4/A4与R6/A6：现有确认语义和外部服务切换怎样处理？

原结论：建议保留既有删除职位/请假/归档、采纳自述身份、连接测试等具体确认语义，共用前台 action host；普通修改不新增总确认。首次完整业务文本外发说明与当前目标服务绑定；修改 analysis 连接后中断当前网络链，新配置经 UI 告知并在后续发送使用，不能在同轮悄悄把完整上下文发给新地址。业务/转写/诊断始终作为非指令数据，不能制造操作授权；模型无通用执行代码、SQL、任意路径或任意 Intent 能力。

依据：E16、E10、E12。

### Q16

问题：R6/A6：系统动作桥接如何关联身份与防止迟到、重复、旋转/重启后的误执行？

原结论：建议类型化动作票据绑定 session/turn/toolCall/requestId/目标及参数摘要/期望版本，一次仅一个等待；前台 ActivityResult/本地确认/导航宿主执行。真实结果后原子消费票据、校验会话仍有效且目标未变，重复/旧结果不触发业务。旋转保留应用内待动作并绑定 launcher；进程死亡不恢复自动写，回调无有效票据报失效。取消/新对话/停止使票据失效，后台提示回到前台，宿主不可用明确错误。

依据：E12、E13、E15。

### Q17

问题：R6/A6：权限、角色、目录文件与系统添加组件各怎样判断真正完成？

原结论：建议权限/角色回调后重新 probe，不能把 resultCode/打开成功当已授权；设置页返回重新核验，UNKNOWN 保持未知。麦克风同理。文件/目录/CSV 接受真实选择结果并验证 content URI、授权/类型/目标，再调用原导入导出保存；模型仅引用宿主签发的短期选择句柄或已授权系统录音项句柄，不能伪造 DocumentRef URI。目录必须实际 takePersistableUriPermission 成功。pinWidget 请求返回仅表示提交；无安装回证时报告已发起/待桌面确认，测试悬浮窗仅报告启动或可证实结果。

依据：E12、E13、E09、E17。

### Q18

问题：R5/A5：容量预算覆盖哪些内容、未知模型容量和服务端拒绝如何处理？

原结论：建议 AI 设置提供正数上下文预算和输出预留，作为客户端上限而非保证模型实际容量；预检逐次计算系统提示＋完整业务快照＋完整会话＋工具定义＋全部工具调用/结果＋协议开销＋输出预留。可使用明确标注的保守估算与请求字节硬上限；未知模型不假定无限。超过任一上限在请求前停止，不静默裁剪/摘要/删历史；服务端 context 400/413等精确识别为容量错误，不能显示一般网络失败后继续工具。新对话只减会话体积，业务自身超限明确首版暂不支持。

依据：E10、E02。

### Q19

问题：R5/A5：工具执行后快照或会话膨胀、模型响应截断与协议异常如何阻止残缺上下文执行？

原结论：建议每次发请求前重新全量收集与预算，包括工具返回；任一超限停止后续推理/动作并说明已提交的动作，不撤回或隐瞒。响应受字节/工具数/轮数上限，完整解析并验证全部批次后才执行首个工具；length/max_tokens、未知stop reason、结构不完整、无工具支持等直接失败，不从文本或半段JSON猜操作。配置分析中的分块合并不能复用于沟通全量上下文。协议实现阶段以官方文档和本地假服务器核对 OpenAI/Anthropic 工具消息顺序及停止条件。

依据：E10、E07。

### Q20

问题：R3-R6：可读取诊断、错误和业务自由文本怎样实现密钥边界及完整性而不过度扩张范围？

原结论：建议出站专用投影白名单＋密钥实值替换兜底，禁止把原异常/认证头/私有文件路径作为工具结果；保留所有业务正文，密钥字符串位置显式 [redacted] 并标注。读取/解密/格式错误是对应域失败，不能以去除坏记录达成全量。诊断仅包括应用现存可读历史，不能声称已读系统全部日志；目录/平台不支持与未配置均用明确状态，平台未知不伪造空值。

依据：E10、E08、E01。

### Q21

问题：A1-A6：验证应以何种数据与故障证明覆盖、且哪些实机边界必须单列？

原结论：建议契约测试按完整 API 操作矩阵逐项有映射/调用/校验/失败/变更通知证据；快照用>200条、跨年/窗、非规则号码、隐藏合并、独立负ID录音、历史配置/日历真实日期、组件全集、全文尾部哨兵及空/缺失/损坏域。测试并发拆页与凭据keep、全序列密钥哨兵、超预算不请求不执行、工具后超限、批次截断、重复ID、停止后已提交回执、系统拒绝取消/旧回调/旋转。协议本地假服务器，不付费真实请求；backend/ui/app测试+构建lint，前台授权/角色/SAF/pinWidget实机验证能力与未验证项单列。

依据：E01、E02、E10、E12。

### Q22

问题：阶段边界及需要主负责人确认的全集是什么？

原结论：本报告仅讨论，未创建正式架构或修改任何文件。建议主负责人整体核定Q01-Q21及其操作/读域边界、复用分析连接、进程内会话、明确容量失败和系统交互策略；若调整，保留原ID/问题正文逐项修订。确认全集后再于唯一允许路径写可实施详细架构、读写矩阵及A1-A6验证映射，以真实 commit/blob/锚点提交审批；当前参考草案仅作候选方向来源，不能视为批准。没有阻塞事实缺口，建议结论均待主负责人阶段确认。

依据：E01、E03。
