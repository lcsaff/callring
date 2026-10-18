# AI 沟通与统一业务操作架构

日期：2026-10-18；状态：架构提交，待主负责人审定，功能尚未实现。任务ai-architecture/architecture_design/epoch2；源码基线c05aa91f1098e21bbc44a7735b1737db9eeeb449。方向来源：ai-architecture-discussion revision1，digest ea7500852b3a561fa690b980a3705a49a77c3b40574d4130b21b3d368213c445，Q01–Q22已确认进入本阶段；当前批准上下文摘要e53a3da22ca813ded5828bca3677c30a7b9938f29355eb15edcfe10e13845ab4。本架构须另行批准；未批准参考稿不作契约依赖。

<a id="requirements"></a>
## 1. 需求、依赖与范围

|需求/验收|必须达到|契约|
|---|---|---|
|R1/A1|录音/AI分设，两页不覆盖最新值，旧配置密钥保留|interfaces、consistency、compatibility|
|R2/A2|底栏/侧栏四入口，发送/状态/错误/真实结果|components、state|
|R3/A3|全业务文本/配置/运行态，无页面/日期遗漏，缺失报错，无密钥实值|read-matrix、authorization|
|R4/A4|全操作映射，共用API/验证/保存，失败不报成功，双向刷新|write-matrix、consistency|
|R5/A5|完整全文/会话/提示/工具预算，超限明确停止，不裁剪|capacity、errors|
|R6/A6|AI发起，系统前台真实授权/选择，取消拒绝/迟到重复安全|authorization、state|

首版：完整快照＋注册工具＋前台类型化动作；沟通复用analysis，转写独立OPENAI；一个进程内会话；非流式串行工具。无向量库、多会话持久化、任意SQL/代码/路径/Intent或通用外发工具；不新增现有业务不支持的档案/通话删除。统计停用仍保留独立录音。未存入应用的系统通话、已删归档正文、已淘汰诊断不声称可读。

提交依赖为下列需求的contentDigest规范化SHA256（acceptance按ID排序）；批准时逐项复核。不编造尚不存在的架构approval ID；源码commit/blob为依据，讨论digest为方向来源。

|ID|内容digest|
|---|---|
|R1|aaf8a0609c0e03655a3e6cfaf290acfe476b4d017a734e40ee4112a0fc88b521|
|R2|f53e48302d378bcea19aaacfcc9e7df41354b999b845893db3eefca2d0e073f7|
|R3|a3c6d70707f5e92c71daa3cadcd335179f165032a462e01f68d5665c477b24c2|
|R4|15f09388cee1f841e9eee04a0af13fe853fba6359736c05e38a7035d9107c40e|
|R5|96eb450e657962865ee93c4c3924d76d23f44b3b90c325b4032a0305c0137f1f|
|R6|8d35de2231f4222a4392cb9b10e27645951392d8e99db16ff217b38f9f7fd9b6|

<a id="evidence"></a>
## 2. 源码依据

以下commit同基线；B=backend/src/main/kotlin/com/greatwall/callring，A=app/src/main/kotlin/com/greatwall/callring，U=ui/src/main/kotlin/com/greatwall/callring。blob列为真实对象唯一20位前缀；展开路径后git rev-parse <基线>:<路径>可得完整blob，符号行为见契约。

|ID|路径|blob|
|---|---|---|
|E01|B/application/api/CallringUiApi.kt|fe07cc9236d56ea67423|
|E02|B/application/api/RecordingApi.kt|9a938b78b6b998475e71|
|E03|B/domain/BusinessState.kt|07f380c2e7ceb4ba6ccd|
|E04|B/domain/BusinessRecords.kt|216125d309645e1ac679|
|E05|B/application/CallerProjection.kt|8ac91807b0b78941c2a1|
|E06|B/application/ReportCalculator.kt|bc5e76f1dd2a7f10958b|
|E07|B/application/BackendContext.kt|d6918390c2410fff8d77|
|E08|A/platform/ai/AndroidRecordingStore.kt|d72c5d0b933610caec8f|
|E09|A/platform/ai/AndroidRecordingRuntime.kt|8674c776607be166bc8d|
|E10|A/platform/ai/CompatibleAiClient.kt|ccf6c33b9d221654375c|
|E11|A/platform/AndroidPreferences.kt|4804a6ef1e4a596ff8c8|
|E12|U/ui/app/CallringActivity.kt|50c1ade632ee5765cbc9|
|E13|U/ui/app/DocumentPickers.kt|902717547d8ba8f4032f|
|E14|U/ui/app/CallringApp.kt|b7560e07cac6309876c1|
|E15|U/ui/feature/recording/RecordingViewModels.kt|0d13281c79954d47167e|
|E16|U/ui/feature/recording/RecordingScreens.kt|58a69b1ec8731ce1a6ed|
|E17|A/platform/ai/SystemRecordingSource.kt|f1e20899b76f15051674|
|E18|B/application/ports/BackendPorts.kt|6acc1d137f457e8e25dc|
|E19|A/data/SqliteBackendStore.kt|f306991f24e9e87bfc0d|
|E20|A/data/ScheduleStore.kt|16ab8fec2ffdfea5d087|
|E21|A/platform/AndroidDiagnosticsStore.kt|312e326958228c027d1c|
|E22|B/application/RecordingWorkflow.kt|9f0bd56dc7a6c732b9a8|

<a id="components"></a>
## 3. 组件与状态归属

|组件/模块|自有责任/依赖|
|---|---|
|ConversationApi/backend|send/stop/newSession/observeState，无Android类型|
|ConversationService/backend application|application拥有会话/轮次/账本/取消；依赖snapshot/client/tools/action端口|
|AiSnapshotService/backend|全量不可变投影/manifest；读主库/录音/偏好/平台，不依赖UI|
|SharedBusinessCommands/backend|UI/AI共用补丁、原校验/事务/投影；不存业务副本|
|AiSettingsService/backend＋app|原加密存储内合并本页补丁/本地凭据，返回安全视图|
|ToolRegistry/backend|固定schema→类型命令→安全回执，O01–O72同源|
|BusinessRevisionCoordinator|短提交栅栏、revision与失效流，覆盖全生产写入口|
|ConversationClient端口/app实现|完整请求→Final/ToolBatch，有界两协议/取消；不执行工具|
|ForegroundActionBridge端口/ui host|前台票据/短期文件句柄/真实结果，不保留Activity|
|ui Routes/VM|输入/展示、两页dirty草稿；全文不进SavedState|

BackendGraph懒装配，CallringUiApi增conversation/aiSettings/snapshots，保留recordings；关闭时取消请求/失效票据。来电不调用AI/快照；原RecordingWorkflow独立。流程：发送→告知→全量/脱敏/预算→完整协议验证→共用业务或前台→真实回执/失效→重新全量/预算→继续或结束。

<a id="interfaces"></a>
## 4. 接口、补丁、结果

ConversationApi：observeState→StateFlow；send(expectedSessionId,text)→TurnAccepted（非最终成功）；stop(expectedSessionId)→StopReceipt；newSession(expectedSessionId)→SessionView，均ApiResult。busy拒绝发送不排队；最终看state/本地操作回执，模型提前说“成功”无效。

AiSnapshotApi.captureComplete→ApiResult<AiBusinessSnapshot>。快照含schemaVersion=1/snapshotId/capturedAt/clock-zone/revisionVector/domains/manifest/redactionSummary。各域有status/itemCount/sourceVersion/collectedAt/sourceScope/digest；Complete类型仅在全部完整时构造。EMPTY=真零项，NOT_CONFIGURED限未配目录，UNAVAILABLE=已证不支持；读/解密/解析失败FAILED阻断。权限UNKNOWN为有效实测非grant。

RevisionVector=进程incarnation＋core、recording(events/links/archives)、recordingConfig、aiConfig、preferences、ringtone、diagnostics单调版本；成功提交递增，回滚不增；时间戳不当版本，重启不复用。录音/AI字段组独立版本但同密文。

CommandContext(operationId/origin/expectedVersions/sessionGeneration)本地绑定，模型不填身份/授权/版本。PatchField=Keep/Set(value)，缺失保留，可清空Set(null)，列表/weekly整字段替换。LocalSecretChange=Keep/Replace/Clear仅本地表单→凭据层；模型无key/原URI。旧完整API适配同执行器，不复制校验/开后门。

aiSettings给getRecordingView/patchRecording/getAiView/patchAi：录音页只启停/自动分析/采集/目录入口；AI页两slot/凭据入口/预算。其它资料/配置补丁同样在原业务事务合并验证。adoptSelfReportedIdentity(callId,expectedArchiveGeneration)抽出原getEditor+saveProfile组合，只非空自述name/organization替换，职位/白名单保留，不采纳推测。

ForegroundActionBridge：observePending；attachHost→lease；beginLaunch(requestId,hostToken)原子防重；deliver(requestId,HostResult)核验消费，均仅进程内UI可用。Reply仅Final或完整ToolBatch，整批schema/ID/停止原因验证后才执行。

ToolReceipt含operationId/toolCallId/status/commitEvidence?/changedDomains/safeValue/safeError?/observedAt。READ/COMMITTED/QUEUED/LAUNCHED/CANCELLED/DENIED/FAILED/UNKNOWN明确区分，排队/发起/未知不当完成；保留原ApiError字段和requiredCapabilities。

<a id="errors"></a>
|错误|处理|
|---|---|
|VALIDATION/原字段错误、NOT_FOUND、IN_USE|不提交，保留原错误，不默认补值伪成功|
|CONFLICT/business.changed|不执行旧计划；重新全量后重算|
|ai.snapshot_domain_failed / snapshot_changed|列明失败域；3次不稳定明确重试，不用部分数据|
|ai.context_budget_exceeded / provider_context_rejected|停后续请求/工具；保留已提交回执，不裁剪|
|ai.protocol_unsupported / invalid_tool_response / incomplete_response|整响应失败，该批零执行，不从文本/半段JSON猜操作|
|PERMISSION_REQUIRED / ROLE_REQUIRED|真实失败，可发起系统动作但不得伪造grant|
|action.cancelled/denied/stale/expired/unavailable|不继续依赖操作，明确结果|
|STORAGE_FAILURE/IO_FAILURE/SYSTEM_RESTRICTED|依真实提交边界返回，原异常不回显|
|ai.turn_limit/cancelled/connection_changed|终止链，列已提交与未执行|

HTTP401/403认证、429限流、5xx/超时传输失败；400不能一律当容量，限定有界结构化错误代码识别context拒绝，413标请求过大。未知正文不回显。无自动网络重试；用户重试先核对本地账本，未知非幂等结果不重放。

<a id="read-matrix"></a>
## 5. 全量读取矩阵

应用层全量端口，不用UI分页/日期。正文完整；私有路径/可调用URI本地保存，模型见显示名/状态/短期句柄。

|域|全部事实/权威来源|
|---|---|
|D01 callers|主库全部号码/姓名/单位/职位/白名单/关系，含非规则号码|
|D02 positions|全活动/停用职位、level、我的职位|
|D03 callFacts|全RawCallRecord含hidden/关联/account/原现分类/旧值/备注|
|D04 callDetails|全visible明细/合并来源/决策/更正，复用detail|
|D05 statistics|全统计有效区间、每号码全时段totals/quality/score与rolling，复用ReportCalculator|
|D06 work|当前及全历史schedule/version/effective、工作状态/nextBoundary|
|D07 calendar|全年份来源/版本/发布时间、真实节假日日期名称/补班；未存原JSON不伪造|
|D08 overrides|全日期kind/windows，不限当前年|
|D09 leaves|全时段/备注/状态/版本，含过去未来、同一采样时间|
|D10 policy|score、quiet/weekly/allNumbers/lastQuietMode、statistics/overlay|
|D11 appearance|PreferenceStore当前主题/颜色/亮暗|
|D12 ringtone|AudioController设置/资源状态与实测播放|
|D13 recordingSettings|原密文录音/自动分析/采集/目录授权安全视图|
|D14 aiSettings|两slot协议/URL/model/keyConfigured/预算，解密失败非未配置|
|D15 recordingEvents|录音库全events/links/负ID/仍存删除标记，不从calls反推|
|D16 archives|未删全归档全文transcript/全部analysis、代次/状态/错误/重试/模型/质量/来源|
|D17 systemFiles|已授权目录全音频元数据；未配置合法，超限/无权失败|
|D18 widgets|新listBindings：全存储绑定＋宿主ID/支持，installed与残留分明|
|D19 runtime|probe全权限角色含麦克风/声音/通话/播放/pin支持/时间|
|D20 diagnostics|新captureStoredText导出现存全诊断/legacy，不能take(18)漏尾|
|D21 audit|同SQL读事务查全calendar_audit(kind/target/raw/created)；pendingAudits仅待写|

深复制state不逃逸。hidden事实不重复计visible/counted；全时段实际min/max用饱和算术，空集totals0/score=null；rolling范围时区独立。legacy脱敏不重记；坏行报错，既有保留策略不需无限增长。

目录500节点/深度4保护，仍有项即enumeration_limit，恰好边界无后续不误判。null Cursor/撤权/循环/提供器错不当空；两次全枚举比较ID/名称/modified/大小，变化总3次内重试。缺时间保留unknown，完整指稳定遍历观测非外部原子快照。

<a id="consistency"></a>
## 6. 一致性、保存、刷新

锁序PublicationFence→service/store→DB/偏好短提交，禁止倒序；不包网络/全文/媒体准备/拷贝/模型等待，监听消费/调度锁外。主库来电/同步、录音register/link/CAS/delete/后台、偏好/铃声/诊断全参加发布；来电不做AI快照，定向测竞争。

短提交区原子核会话代次/读依赖版本→原验证/事务或原子替换→revision/receipt/通知入账。媒体/日历锁外准备，发布前再核取消/目标代次/版本，失败清scratch保旧；已提交不因后续调度失败改说未保存。

最多3次捕获：短Fence采v0→各源事务复制→运行态→短Fence采v1；版本变丢整份重试不拼旧域，持续变报错；时钟不比较，跨源稳定观测非全局SQL原子。

UI拆页在原密文锁内合并：另一页变仍只存本页dirty；本页变逐字段比基准、冲突拒绝；完整validate用最新双连接，不回退别人。AI绑定请求读版本（可保守全静态域）拒旧计划；自有成功更新向量，外变仍冲突，同批不猜新ID。

批次串行无跨域回滚；每操作后全量/预算才下一项，结果全配对才发请求，失败余项skipped。changes增RECORDING_SETTINGS/AI_SETTINGS/RECORDING_ARCHIVES，后台实际CAS/配置/目录均通知；StateFlow累计域或全域回退防慢订阅漏域。脏草稿保留/提示差异，无脏刷新；onResume重probe，轮询仅辅助。

<a id="write-matrix"></a>
## 7. 全操作矩阵

工具名=前缀_方法名（保留方法大小写，例如settings_savePosition）；固定schema禁止额外字段。命令类型复用E01/E02，date=ISO LocalDate、时间=Long epochMillis、ID精确Long、时区ZoneId；缺字段不是清空。每行共同要求原验证/错误/安全投影/真实回执。V03全部操作、V02所有读、V04所有写、V07所有S、V01配置、V05生命周期、V08全部结果。R=读，W=业务操作，S=前台系统或既有确认。首版所有实际成功变更发布全域失效，包含新增录音/AI域；只读不通知。

|ID 能力=共用方法|参数/边界|类别|
|---|---|---|
|O01 root.checkNumber|raw，纯计算|R|
|O02 home.load|{}|R|
|O03 callers.search|CallerSearch，page≤200|R指定分页|
|O04 callers.getEditor|number?|R|
|O05 callers.getOverview|OverviewQuery|R|
|O06 callers.getLatestCall|{}|R|
|O07 callers.saveProfile|number/资料补丁，原职位校验|W|
|O08 callers.setWhitelisted|number/enabled|W|
|O09 reports.load|ReportQuery|R指定范围分页|
|O10 reports.exportCsv|ReportExportQuery＋真实CSV目标|S/W，recordCount与CSV防注入|
|O11 settings.getSummary|{}|R|
|O12 settings.setStatisticsEnabled|enabled，保留区间/原同步|WALLS/REPORTS|
|O13 settings.setOverlayEnabled|enabled，真实OVERLAY|W|
|O14 settings.getQuietHours|{}，记忆模式另见D10|R|
|O15 settings.saveQuietHours|补丁→原validateQuiet/updateQuiet|W|
|O16 settings.setQuietMode|QuietMode|W|
|O17 settings.setQuietEnabled|enabled，恢复上次模式|W|
|O18 settings.getPositions|{}，含停用|R|
|O19 settings.savePosition|SavePosition补丁，active=false停用|W|
|O20 settings.deletePosition|id，原确认/IN_USE|S/W|
|O21 settings.setMyPosition|id?，null清除|W|
|O22 settings.getWorkSchedule|{}，历史在D06|R|
|O23 settings.saveWorkSchedule|SaveWorkSchedule补丁|WALLS/REPORTS|
|O24 settings.resetWorkSchedule|{}，原默认/生效版本|WALLS/REPORTS|
|O25 settings.getCalendar|{}，真实日期另D07|R|
|O26 settings.synchronizeCalendar|year，网络在锁外|WALLS/REPORTS|
|O27 settings.importCalendar|系统文件，原JSON≤1MiB校验|S/W|
|O28 settings.saveDateOverride|DateOverride|WALLS/REPORTS|
|O29 settings.deleteDateOverride|date|WALLS/REPORTS|
|O30 settings.getLeaves|{}全部|R|
|O31 settings.saveLeave|SaveLeave补丁，AllDay/Timed|WALLS/REPORTS|
|O32 settings.deleteLeave|id，原确认|S/WALLS/REPORTS|
|O33 settings.getScoreSettings|{}|R|
|O34 settings.saveScoreSettings|ScoreSettings补丁|W|
|O35 settings.resetScoreSettings|{}|W|
|O36 settings.getAppearance|{}|R|
|O37 settings.saveAppearance|AppearanceSettings补丁，原偏好回滚|W|
|O38 ringtone.getSettings|{}|R|
|O39 ringtone.setEnabled|enabled，原PHONE_STATE/ROLE|W|
|O40 ringtone.importAudio|系统音频，≤20MiB/可播放/原子替换|S/W|
|O41 ringtone.restoreBuiltIn|{}|W|
|O42 ringtone.startPreview|{}，原idle/可听/PHONE_STATE|W|
|O43 ringtone.stopPlayback|{}|W|
|O44 runtime.getStatus|{}含麦克风|R|
|O45 runtime.getDiagnostics|{}原窗口，全集D20|R|
|O46 runtime.requestCallLogSync|{}真实CALL_LOG|W，仅QUEUED/ALREADY_QUEUED|
|O47 widgets.getBinding|widgetId|R|
|O48 widgets.saveBinding|widgetId/number，原校验|W|
|O49 widgets.removeBinding|widgetId|W|
|O50 recordings.listArchives|{}全部安全视图|R|
|O51 recordings.getSettings|getRecordingView/getAiView安全组合|R，内部原getSettings|
|O52 recordings.saveSettings|非敏感补丁→patchRecording/patchAi→原validate|W，无key/URI|
|O53 recordings.testConnection|slot，原固定测试/当前本地凭据|S/网络，测试告知|
|O54 recordings.getArchive|callId，负ID/旧关联|R|
|O55 recordings.importRecording|callId＋音频选择/已签系统项|S/W，原≤256MiB/身份/媒体校验|
|O56 recordings.retry|callId，原连接校验|W，仅QUEUED|
|O57 recordings.delete|callId，原确认/代次/文件清理|S/W|
|O58 recordings.play|callId，原ownedFile/generation|W|
|O59 recordings.stop|{}，失效待prepare|W|
|O60 recordings.setSystemFolder|前台tree选择/持久READ授权|S/W|
|O61 recordings.systemRecordings|{}完整枚举/句柄，超限失败|R|
|O62 widgets.listBindings 新|{}全存储＋installed|R|
|O63 recordings.adoptIdentity 新|callId→adoptSelfReportedIdentity|S/W，原自述确认|
|O64 ai.getSettings 新|{}→getAiView|R|
|O65 ai.patchSettings 新|两slot非敏感字段/预算→patchAi|W，analysis变终止链|
|O66 ai.editCredential 新|slot→本地Keep/Replace/Clear保存|S/W，只返回状态|
|O67 system.requestCapability|Capability含MICROPHONE→共用前台/probe|S|
|O68 system.openApplicationSettings|{}固定应用设置Intent|S，回实测非强改|
|O69 system.pinWidget|{}共用pinWidget|S，launch/安装回证区分|
|O70 system.testOverlay|{}原测试入口/能力检查|S，只证启动或真实显示|
|O71 system.previewCall|callId→真实CallDetail→原previewCall|S，无模型构造Intent|
|O72 ai.copyConnection 共用化|from/to，原copyAnalysisToTranscription/copyTranscriptionToAnalysis|S/W，本地预览保存/内部复制key|

O52不允许直接写systemFolder，必须O60；UI走同patchRecording。O53界面可测未保存本地草稿，工具只测已保存slot，最终共用验证/固定测试。O55 number/startedAt/source从权威录音身份解析，不能模型拼目标；O61句柄有效可直接关联系统音频，不重弹选择器。O72含凭据复制在本地表单，源/目标slot版本绑定；向transcription复制沿用强制OPENAI。ChangeFeed.observe是基础设施流，不注册无限流工具。读工具也必须实际映射，不因“快照已有”省略方法。

<a id="state"></a>
## 8. 会话与执行状态

application拥有会话/轮次ID、generation、全文历史/账本、快照摘要/连接告知、requestSequence；不落SavedState/偏好/日志，切页旋转保留、死亡新会话。

IDLE→CAPTURING→BUDGETING→REQUESTING→VALIDATING→EXECUTING/WAITING_FOREGROUND→CAPTURING…→COMPLETED；可STOPPING→STOPPED或FAILED。stop幂等、断HTTP/失效后续，不可取消提交者先入账再STOPPED；旧响应无效，新对话先说明旧提交。

幂等键(sessionId,turnId,providerCallId)＋参数摘要；同参返缓存异参拒；副作用前IN_FLIGHT、回执先入账。异常核提交/实体版本，UNKNOWN不重放，无跨进程exactly-once。单批8工具/轮16模型请求32工具，整批超限零执行；失败补齐skipped，无法配对需新对话不删历史。后台网络可读完，新增写/平台须RESUMED lease，否则等前台再核版本；死亡无恢复执行。

<a id="capacity"></a>
## 9. 容量与协议

context默认32768（8192–1048576），输出预留4096（1024–16384且<context），客户端上限非模型承诺。估算=全输入文本/schema UTF8字节数＋1024协议余量＋输出预留＋max(1024,ceil10%输入估算)，checked Long；说明保守估算非任意tokenizer保证，未知不无限。

请求8MiB/响应1MiB/单用户消息32KiB；源事务先计数/长度，累计≥8MiB或>20000业务行丢整份报容量，主库在全state加载前预检、不catch OOM。JSON转义后再预算。每请求含系统/当前全文/完整会话及工具结果/全schema/开销/预留；当前快照替换context不追加旧副本为历史，历史不删。工具后超限保回执停后续；新对话只减历史，全集过大明确首版不能处理。

沿用HTTPS/禁userinfo-query-fragment/禁重定向/15秒连接90秒读取/安全异常；非流式全量有界读＋StrictJson后才工具，不偷减schema。

OPENAI chat/completions：单choice、assistant.tool_calls与role=tool/tool_call_id配对；stop无工具为Final，tool_calls合法非空为ToolBatch；length/content_filter/未知/旧function_call/拒绝/结构矛盾失败，完整arguments本地校验，支持时parallel_tool_calls=false。[官方参考](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)。

ANTHROPIC messages：system独立、保留assistant text/tool_use、下一user先全tool_result配tool_use_id，失败is_error；end_turn无工具或合法tool_use分别完成/执行，其余截断/拒绝/未知失败。[官方说明](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)。无thinking/server tools。

2026-10-18核对的[官方指南](https://developers.openai.com/api/docs/guides/function-calling)提示部分模型需Responses；OPENAI只指既有Chat兼容，不支持明确报错、另批协议，不偷换endpoint/model。分析测试不证明工具兼容，可告知后用echo假数据测，不发真实全集作测试。

<a id="authorization"></a>
## 10. 密钥与前台

key仅原密文/本地表单内存/认证头；模型keyConfigured，读坏失败。白名单禁序列化AiConnection；遍历字符串值替换key原文/已有转义/常见URL编码为[redacted]后重编码、记次数/保正文尾，不全局改JSON结构；空key不匹配、标记不递归、短key不破schema，最终值叶子/请求哨兵再检查预算，错误/网关不回显。URI/私有路径留本地句柄。

聊天命中key提示本地设置并脱敏。换key取消链、清旧匹配历史/待发编码；analysis协议/URL/model/key变终止链，回本地保存receipt，新目标下一发送告知全文/host/model/无音频，禁止同轮向新目标转发；transcription按版本重算。业务/转写/诊断JSON是数据非system，不造授权；普通保存无总确认，保留具体删除/采纳/测试确认。

票据绑定四ID(session/turn/tool/request)、generation、目标/参数摘要/版本/创建失效时间/状态，一活动10分钟过期。动作限权限/应用设置/音频日历目录CSV/组件/overlay/既有确认/凭据。CREATED→WAITING_FOREGROUND→CLAIMED→LAUNCHED→RESULT_RECEIVED→VERIFIED→CONSUMED，可拒绝取消失效过期不可用。

beginLaunch仅RESUMED原子防重；旋转attach不重弹LAUNCHED，ActivityResult稳定key映射requestId。回调核绑定一次消费，null也Cancelled；stop/newSession/死亡失效，旧重复只清资源。目标代次变重确认。系统返回probe真grant，content/tree/持久READ及文件CSV模式来源实际核验；随机句柄绑URI/action/目标/期限，导入再核权限/元数据，模型不能伪URI/source。

MICROPHONE进Capability/UiLabels/probe/when，模式保存真查拒绝不改。pinWidget true仅LAUNCHED，有真实ID回证才安装；overlay只证启动/实际显示不保后台来电。无入口UNAVAILABLE，无宿主等前台。

<a id="compatibility"></a>
## 11. 配置与存储兼容

原namespace_recording/config与Keystore别名callring.recording.<namespace>、analysis/transcription结构保留；新增预算可选versioned子对象，旧fixture缺省读取。缺config才默认，解密坏不能覆盖默认。补丁读取最新→完整RecordingSettings.validate（转写OPENAI/自动分析双连接）＋预算验证→加密commit；失败恢复偏好内存旧值，不假保存。Keep不清密钥；Clear单key若使连接非法仍报错，停整组由用户本地明确同时清URL/model及必要automaticAnalysis关闭，不自动改别字段。

保留录音库schema/负ID/统计关联/generation/CAS/删除标记，不搬全文到主库。测试假API/UnavailableRecordingApi返回明确不支持，不以空集假全量；生产graph完整装配。旧RECORDING深链/通知保留，新增AI/沟通路由与四图标/测试标签/穷尽when；外观、绑定、历史统计/后台调度不重置。进程退出丢聊天，持久业务仍保留。

旧版程序可能丢新增预算，不承诺任意降级保留新字段，但旧连接应可读；不可逆格式/迁库必须另报范围变化。

<a id="verification"></a>
## 12. 验证契约

以下为实现验收，非本轮产品测试；注册表与矩阵同源核对，断言真实存储/API/调用次数/通知，不只“成功”文本。

|族|必需验证|
|---|---|
|V01 配置|旧密文两key、双页交错/dirty冲突/Keep-Replace-Clear、跨连接校验、commit解密失败保留、slot复制|
|V02 全量|>200/跨页跨年/非规则号/隐藏合并/负ID录音/删除、全文尾哨兵、历史工作/真实日历/统计区间/绑定残留/诊断审计尾部；空/缺失/目录边界/持续并发|
|V03 全操作|O01–O72逐项路由/原验证/失败/通知，IN_USE/时区/媒体/CSV/仅自述采纳|
|V04 并发|UI↔AI/后台CAS、锁内版本、导入中restore-delete、脏草稿/慢订阅、锁序/来电不等网络|
|V05 执行|整批非法零执行、串行/部分失败、重复ID同参缓存异参拒、UNKNOWN不重放、stop保提交、QUEUED不假完成、generation不复活|
|V06 协议容量|两协议假HTTP配对/截断/拒绝/无工具；全部预算/输出预留/算术/字节、超限零请求零执行、工具后超限保回执、400区分context|
|V07 系统|grant/deny/cancel/unknown/无宿主/过期、旋转/死亡/重复迟到、伪URI句柄来源/目标变更、麦克风拒绝、widget-overlay真假完成|
|V08 密钥|两slot哨兵置正文/工具/诊断/网关，正文结果历史日志无实值(认证头例外)，旧新key清理、无落盘/SavedState|
|V09 UI|两屏宽四入口/深链、两设置/草稿、全部对话阶段/停止/已提交/超限、切页旋转/重启/前台|

A1=V01/04/09；A2=V05/06/09；A3=V02/08+manifest；A4=O01–O72/V03/04/05；A5=V06/09；A6=V07/09。实现跑.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug，核实际任务；当前compileSdk36/min29/target33，PROJECT旧SDK注释不作准。设备用隔离backendQa测权限/角色/SAF/CSV/目录/桌面/overlay，不删主包数据/自动发付费请求；真实SIM/ROM后台/服务未验项单列，提交真实测试证据。

<a id="tradeoffs"></a>
## 13. 取舍与演进条件

|决定/代价|有依据的演进条件|
|---|---|
|全量快照＋全工具，可能容量/内存/延迟高|真实超限样本/分项大小证明不可承载后，另设计按需读取完整性/游标/版本；不先加检索|
|复用analysis，模型可能无工具|实际失败或用户要独立模型后加conversation slot，不自动换模型|
|进程单会话，无重启续聊|用户明确需持久化后设计加密/保留删除/跨进程恢复，不提前落盘|
|独立录音＋稳定采样，无全局SQL原子|有无法解决的一致性样本再评估持久版本/事务，不搬全文进Telecom库|
|短提交栅栏/乐观版本接入成本|有锁竞争实测才细分锁/域，不拆散原子校验提交|
|非流式串行/轮次有限|有交互延迟证据再加仅显示文字流，仍完整后才工具|
|保守估算可能早拒绝|有可靠分词/usage校准再提高精度，保留服务端拒绝/字节保护|
|既有Chat/Anthropic两协议有模型边界|选定模型确需Responses时另批准新增协议及全配对测试|

本轮仅此文档；实现者另提交实现设计/实施计划分别批准才改产品。README随实现更新四导航/两设置/完整文本外发/进程会话/容量。范围或接口变化逐项说明对A1–A6及QID影响，保留有效结论/成果。

<a id="discussion-ledger"></a>
## 14. 问题账本

原问题不变，结论详见契约；原全文绑定revision1/digest。

|ID|原问题|结论|
|---|---|---|
|Q01|R1/A1：拆页如何保留现有录音、分析、转写配置及 Keystore 密钥，避免旧页面覆盖新值？|原密文/别名保持；拆页补丁在存储内合并校验，凭据本地三态。|
|Q02|R2/A2：一级导航、会话寿命及页面状态采用什么最小首版方案？|四入口统一，进程会话保切页/旋转，状态以真实回执为准。|
|Q03|R2-R5：首版用完整快照、按需检索还是整份配置覆盖；连接独立还是复用？|全量快照＋工具，复用analysis；不兼容失败，不伪执行。|
|Q04|R3/A3：全部业务文本的读取范围如何证明，能否靠页面与报表拼装？|21域manifest，空/错分明，无分页/日期遗漏。|
|Q05|R3/A3：隐藏/合并通话、统计公式与观察窗应怎样表示，避免全量事实和去重统计相冲突？|事实保隐藏关联，统计用visible去重，权威全时段/观察窗。|
|Q06|R3/A3：统计停用时独立录音如何完整读取，删除内容与系统目录又如何界定？|录音events/links/归档独立枚举；目录超限失败，不恢复删除正文。|
|Q07|R3/A3：多数据库、SharedPreferences 和易变运行状态如何保持可信快照？|各源事务复制＋版本核验，3次不稳定失败，非全局原子。|
|Q08|R3/A3与R4/A4：密钥只可见配置状态与所有配置可修改如何同时成立？|模型只凭据状态，key本地表单；出站投影/脱敏。|
|Q09|R4/A4：配置覆盖矩阵到底必须列出哪些既有动作？|全部配置逐方法映射，原验证/保存，不执行代码SQL。|
|Q10|R4/A4：业务操作、档案采纳、报表和组件的全面范围如何定义？|现API/界面操作全覆盖；自述采纳共用化，不新增删除。|
|Q11|R3-R4：小组件绑定及麦克风权限在现有接口的缺口如何补齐？|补全绑定列表/MICROPHONE，模式保存真检查权限。|
|Q12|R4/A4：旧快照/旧表单如何避免覆盖 UI 的最新配置或资料？|UI字段补丁合并，AI版本冲突拒绝旧计划。|
|Q13|R4/A4：AI/界面双向刷新如何覆盖录音后台状态和未保存草稿？|后台CAS也发失效，脏草稿保留/提示冲突。|
|Q14|R4/A4：多工具、错误、异步排队、取消与重复调用采用什么结果契约？|串行/幂等/真实回执；停止保提交，未知不重放。|
|Q15|R4/A4与R6/A6：现有确认语义和外部服务切换怎样处理？|保既有具体确认；目标改变终止链，下一发送告知。|
|Q16|R6/A6：系统动作桥接如何关联身份与防止迟到、重复、旋转/重启后的误执行？|票据一次消费，旋转不重弹，死亡不恢复执行。|
|Q17|R6/A6：权限、角色、目录文件与系统添加组件各怎样判断真正完成？|授权重probe，SAF真权限/句柄，launch不假完成。|
|Q18|R5/A5：容量预算覆盖哪些内容、未知模型容量和服务端拒绝如何处理？|完整预算与字节限制，超限/服务拒绝停止，不裁剪。|
|Q19|R5/A5：工具执行后快照或会话膨胀、模型响应截断与协议异常如何阻止残缺上下文执行？|整响应/整批验证，操作后重全量预算，异常停后续。|
|Q20|R3-R6：可读取诊断、错误和业务自由文本怎样实现密钥边界及完整性而不过度扩张范围？|存量诊断/审计完整脱敏，坏行失败，不声称系统全日志。|
|Q21|A1-A6：验证应以何种数据与故障证明覆盖、且哪些实机边界必须单列？|V01–09验证真实存储/API/通知，协议假服务器/设备边界分列。|
|Q22|阶段边界及需要主负责人确认的全集是什么？|保留全集；只交架构，审定后实现者另交设计/计划。|
