# AI 沟通整改实施计划

> REQUIRED SUB-SKILL: executing-plans，当前会话逐项执行，禁止子Agent。批准execution后才实施。

**Goal:** 精简Markdown UI、全业务按需、可靠执行、真实Qwen/真机验收。

**Architecture:** 保应用图/共享写，增源级查询、目录、账本/核验与profile，端口隔离UI/编排/协议/存储。

**Tech Stack:** 现有Android/Kotlin栈，Markdown0.40.2 core+m3，无Koog。

---

<a id="dependencies"></a>
## 依据与执行规则

依赖[实施设计](../specs/2026-10-19-ai-conversation-rectification-implementation-design.md)，commit `2f792d4a21fc3be6f1915b399d99450db40f8eaa`，批准 `approval-2b22c761fcec23f9f30b4e668c5d0dd0fb48b2cb8c3aded5ee5b718027668ec2`；R1–R10/架构契约不变，规范化依赖摘要随提交上报。

简称B=`backend/src/main/kotlin/com/greatwall/callring`，P=`app/src/main/kotlin/com/greatwall/callring`，U=`ui/src/main/kotlin/com/greatwall/callring/ui`；TB/TP/TU为各模块同包src/test/kotlin，UD为ui同包src/sharedTest/kotlin。“+”新增，其余修改；新测试类在对应步骤创建。

每个独立场景依次写断言→定向命令确认目标缺陷失败→最小实现→同命令通过，不事后补测试。任务完成`git diff --check`，只暂存本任务文件提交；失败保成果，无新证据同类两次停止重试并报告。提交用UTF8 body-file、单个连续trailer段，身份/轮次/检查点均取当前begin_round。

命令在当前工作树，Gradle用`.\gradlew.bat`；测试数>0、failures/errors=0，列明跳过。渐进查询禁全快照/StorageSession.load/全集切页，写仍共用业务入口。21域/72能力缺实现阻断，不伪装UNAVAILABLE；保49152/4096及旧配置，不改其他会话状态文件。

<a id="steps"></a>
## S01 基线与Markdown兼容探针（无依赖）

文件：ui/build.gradle；+UD/app/MarkdownCompatibilityTest.kt；必要时build.gradle。证据最终放docs/plans/ai-rectification-evidence/dependencies.md。

- [ ] 记录Git/JDK/Gradle、现有APK字节/摘要，跑`:backend:test :app:testDebugUnitTest :ui:testDebugUnitTest`，保基线测试数。先写渲染smoke：标题/列表/中文长文/表格/代码/链接，确认未接渲染器时失败。
- [ ] 固定添加Markdown 0.40.2 core+m3；运行`:ui:dependencies --configuration debugRuntimeClasspath`、`:ui:dependencyInsight --dependency androidx.compose --configuration debugRuntimeClasspath`、`:ui:compileDebugKotlin :app:assembleDebug`和smoke，记录选中版本/APK差量。禁图片网络/高亮插件、自制parser。
- [ ] smoke/编译/D8通过后提交。失败保日志/依赖图，向负责人提交最小版本或局部Compose对齐提案，批准前不换版、不跳过A7。

## S02 协议/profile及设置兼容（依赖S01）

文件：P/platform/ai/CompatibleConversationClient.kt、ConversationJson.kt、AiSettingsService.kt、AndroidRecordingStore.kt；+ProviderProfileResolver.kt；B/application/ports/ConversationPorts.kt、api/AiSettingsApi.kt及定义RecordingSettings的原文件；TP/platform/ai/ConversationProtocolTest.kt、AiSettingsServiceTest.kt；+ProviderProfileTest.kt。

- [ ] 协议断言：function_call缺省/null的Final与ToolBatch均成功；非null旧式、refusal、关键类型错、length截断、32/33与重复ID、400非JSON/已知context/401/403/429/413/5xx分层。跑`:app:testDebugUnitTest --tests '*ConversationProtocolTest' --tests '*ProviderProfileTest'`，确认新断言红。
- [ ] 按设计§6/8解耦解码/registry，WireAssistant/ContextEnvelope替换snapshot，保Anthropic配对；实现限定FAST/两false/思考错配和安全计量，STANDARD无扩展。
- [ ] 接AUTO/解析profile/指纹/目标核对/codec；测交错patch、密钥三态、旧预算和AR-001双向原子复制/强制OPENAI/反向AUTO/取消/读回。运行`:app:testDebugUnitTest --tests '*AiSettingsServiceTest'`及协议测试，全绿提交；失败保配置/密钥。

## S03 查询契约、目录和计量（依赖S02）

文件：+B/application/api/AiQueryApi.kt、ports/ScopedReadPort.kt；+B/application/conversation/CapabilityCatalog.kt、QueryService.kt；修改ToolRegistry.kt；+TB/ScopedQueryContractTest.kt、CapabilityCatalogTest.kt。

- [ ] 测目录零I/O/全集遍历/exactNames/limit/启用集；查询容量/字段、空/未知总量/缺失、码点界、游标过期/版本变化。运行`:backend:test --tests '*ScopedQueryContractTest' --tests '*CapabilityCatalogTest'`先红。
- [ ] 实现设计§3.1类型/投影/filter、静态discover、有限游标/引用表、read计数；schema补单位/枚举/缺省/授权/核验及类型化错误，不接全库默认实现。
- [ ] 上述测试全绿提交。容量超限给具体范围/缺页；缺实现明确测试失败，不以空页/UNAVAILABLE吞掉。

## S04 核心SQLite查询与统计规则（依赖S03）

文件：+P/data/SqliteScopedReadAdapter.kt；SqliteBackendStore.kt、CallringDatabase.kt、P/bootstrap/LazyBackendStore.kt；B/application/ReportCalculator.kt及visible/score共享纯规则；+TP/data/SqliteScopedReadTest.kt；+TB/ScopedAggregateTest.kt。

- [ ] D01–D10/D21测跨页/日期/排序/末页/空/坏源；覆盖calls独有号码、hidden/未计分与VISIBLE区别、全文尾哨兵/大行引用/旧审计。运行`:app:testDebugUnitTest --tests '*SqliteScopedReadTest'`确认红。
- [ ] 参数化投影/keyset/count与页同源事务，不构造BackendState。先在事务外取coordinator版本→SQLite读事务页/count→结束事务→再取版本；变化则丢页CURSOR_STALE。**SQLite读事务内不得获取/等待coordinator fence**；长查询不持fence。并发测试用闩锁安排原写fence→SQLite与读事务交错，超时失败检查死锁，不能用固定sleep代替同步。
- [ ] 提取流式纯累加器，保区间/饱和加法/分类/重复计分跨页前项；固定库与旧UI报告全量对照，源count与权威aggregate不按当前页相加。跑`:backend:test --tests '*ScopedAggregateTest'`及SQLite测试。断言StorageSession.load调用0、窄投影无全文、无关行不解码；通过提交，差异保范围/规则证据报告。

## S05 归档索引与录音源（依赖S03，S04后顺序执行）

文件：P/platform/ai/AndroidRecordingStore.kt；+RecordingScopedReadAdapter.kt；+TP/platform/ai/RecordingScopedQueryTest.kt、ArchiveProjectionMigrationTest.kt。

- [ ] 构造录音v2数据：负callId/links/deleted、全部analysis字段/列表、转写尾哨兵；记录接近及超过1MiB、单个超大字符串、Unicode代理对/转义跨块。写迁移/码点/稳定游标测试，运行`:app:testDebugUnitTest --tests '*RecordingScopedQueryTest' --tests '*ArchiveProjectionMigrationTest'`先红。
- [ ] 升v3新增元数据/文本chunk派生表，保payload真源/ID/generation；所有注册/CAS/删除同事务更新投影。仅相关查询推进旧记录索引，每次≤16记录或1MiB源文本，SQL substr有限块+JsonReader，持久checkpoint含目标/generation/输入偏移/字段与码点进度；可从已存块继续，解析状态不可安全续时缓存有限分片并从检查点重建，不每页重头。
- [ ] 测超限记录跨次最终可达、完成标记前不露半记录、取消/杀进程续进、generation废旧索引、坏payload保原文报错、终页证明；闲聊零回填。全绿提交，失败保旧payload不清库。

## S06 其余源接线（依赖S03–S05）

文件：+P/bootstrap/ScopedQueryAssembly.kt；P/platform/AndroidPreferences.kt、AndroidDiagnosticsStore.kt、AndroidRuntimeProbe.kt；P/platform/ai/SystemRecordingSource.kt、AndroidRecordingRuntime.kt；+TP/bootstrap/ScopedQueryAssemblyTest.kt、platform/ai/ScopedDirectoryTest.kt。

- [ ] D11–D14/D17–D20测设计§3.2各源、拒绝/撤权/未配置/空、目录取消/进度/未知总量。运行`:app:testDebugUnitTest --tests '*ScopedQueryAssemblyTest' --tests '*ScopedDirectoryTest'`先红。
- [ ] 接目标配置/运行探测、SAF有界children/游标/原handle、已存诊断全范围白名单；禁systemRecordings全集/私有路径。BackendGraph注入真实端口，机器核对21域实现。
- [ ] 断言未请求域调用0、密钥/URI/path哨兵无泄漏、部分页不报完整，测试全绿提交；设备不支持某源只按真实原因标未通过，不能掩盖未实现。

## S07 依赖范围与共享发布（依赖S02、S04–S06）

文件：B/application/BusinessRevisionCoordinator.kt、api/AiSnapshotApi.kt的CommandContext、SharedBusinessCommands.kt、UiEdits与各共享保存入口；P的settings/录音/偏好/后台发布点；+TB/ScopedRevisionTest.kt；扩展TP/platform/ai/ToolBusinessIntegrationTest.kt。

- [ ] 测外观/来电、号码A/B不冲突；同字段/职位引用/generation/统计规则及UI/后台变更冲突。运行`:backend:test --tests '*ScopedRevisionTest'`及app业务集成测试，确认旧实现失败。
- [ ] 接非空业务scope/字段基线，旧调用保全向量；共享入口按差异发布，同fence检查/保存/发布/记提交，I/O锁外。自身只推进关联scope，不临提交消冲突。
- [ ] 正反例和S04锁顺序测试绿后提交。发现遗漏发布点即补业务入口，不能在AI层忽略冲突。

## S08 计划/账本/72操作核验（依赖S03、S07）

文件：+B/application/conversation/TaskPlanner.kt、StepLedger.kt、ReceiptVerifier.kt、OperationContract.kt；ToolExecutor.kt/ToolRegistry.kt；+TB/TaskPlannerTest.kt、StepLedgerTest.kt；修改ToolOperationCoverageTest.kt；扩展TP/platform/ai/ToolBusinessIntegrationTest.kt。

- [ ] 测10单调用、8+2、多批、32/33、同ID改参/换ID重复、CREATE不覆盖/合法唯一、无计划零写、部分失败/依赖跳过/停止/提交未知。保存回执后故意读回失败须COMMITTED_UNVERIFIED，QUEUED/LAUNCHED不算完成。跑`:backend:test --tests '*TaskPlannerTest' --tests '*StepLedgerTest' --tests '*ToolOperationCoverageTest'`先红。
- [ ] 按设计§4.1接task_prepare/saveProfiles与依赖/预检、两索引去重/账本、容量预留；8仅窗口，预算不足不启动。执行原commandApi/校验/保存，机器核对72+扩展目录↔执行↔API↔核验。
- [ ] 每类原操作真实业务回归：读字段/删除证明/录音generation/配置/绑定/媒体回执，非用模型文字判成功。上述及app集成测试通过提交；保部分回执，任何未知提交不重放。

## S09 按需对话与有界恢复（依赖S02–S08）

文件：B/application/conversation/ConversationService.kt；+ContextAssembler.kt、TurnBudget.kt；api/ConversationApi.kt；P/bootstrap/BackendGraph.kt；TB/ConversationServiceTest.kt；+ContextAssemblerTest.kt、ConversationRepairTest.kt。

- [ ] 写闲聊1模型请求/仅discover/快照query运行回填全0；发现后最少schema；历史大结果只发一次；活动tool配对不裁剪；修复单步2/全轮4；10写+10核验工作预算；模型Final与账本目标不符PARTIAL。跑`:backend:test --tests '*ConversationServiceTest' --tests '*ContextAssemblerTest' --tests '*ConversationRepairTest'`先红。
- [ ] 删除默认snapshot，接查询/目录/计划/核验，预算16模型/64工作/32调用。按callId安全反馈参数错误/有限修复，冲突/拒绝/取消/未知提交不修复；HTTP失败保账本，网络自动重试0。
- [ ] 接确定性摘要、100轮/2MiB、8MiB请求/1MiB响应与最终UTF8检查，必需数据不足报范围；停止/连接变化清wire/票据、保提交。定向及多轮FAST/Anthropic全绿提交。

## S10 UI与前台系统边界（依赖S01、S02、S09）

文件：U/feature/conversation/ConversationScreen.kt；feature/ai/AiSettingsScreen.kt、AiSettingsViewModel.kt；app/AiRoutes.kt及现有四导航；B/application/conversation/ForegroundActionService.kt；真实Activity宿主；TU/UD的ConversationNavigationTest.kt、ConversationStateUiTest.kt、ConversationReviewUiTest.kt；TB/ForegroundActionServiceTest.kt。

- [ ] UI测四导航/精简气泡/Markdown/图标、右下发送16–20dp/48dp/描述、错误/部分成功、IME/大字体/窄宽/双主题。运行`:ui:testDebugUnitTest --tests '*Conversation*Test'`确认旧UI不符。
- [ ] 实现设计§7 UI，保动态目标/缺配置/停止/提交事实；前台动作保真实系统及权威确认。测一次launch/deliver、拒绝/取消/过期/换host/迟到重复/目标变化零误提交。
- [ ] UI与`:backend:test --tests '*ForegroundActionServiceTest'`通过提交；真实系统动作在S12分别记通过/失败/未测，不用模拟宿主声称已授权。

## S11 全验证与正式验收入口（依赖S01–S10）

文件：+app/src/debug/kotlin/com/greatwall/callring/ai/ConversationAcceptanceDeviceTest.kt；debug清单若需；+tools/verify_ai_conversation.py；docs/plans/AI沟通整改验收.md、ai-rectification-evidence下证据文件。

- [ ] 入口取targetContext的CallringApplication正式图，启动真实Activity/宿主，原凭据/CompatibleConversationClient。禁临时库/伪client/预制tool_calls；脚本限定设备/该类，拒绝uninstall/clear及旧全套测试。
- [ ] 脚本默认只本地核验，真实模型步骤须显式选择一次runId；新增10档案后持久记录本次合成步骤标识，重启验证/独立审查仅读，不自动再发新增原文。只记录脱敏闭合指标；旧号码基线留进程内，重启后用本次新记录引用读回，不导出旧个人资料。
- [ ] 跑`python tools/check_backend_boundaries.py`及`.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :ui:assembleDebug :app:assembleDebug :app:assembleRelease :ui:lintDebug :app:lintDebug --console=plain`，再相关UI设备包构建/测试。检查XML数量/失败/跳过、无秘密哨兵/数据body日志。构建/自动测试通过提交；失败先诊断，不能转模拟“完成”。

## S12 唯一设备真实Qwen验收与证据（依赖S11）

仅可修正S11入口/计量或S02–S10业务路径。证据沿S11位置。独占192.168.10.149:5555，其他会话只读。

- [ ] 记录在线/解锁、系统API/屏幕、com.greatwall.callring版本、Git/APK摘要、签名、qwen3.8-flash/OPENAI/resolvedProfile及预算。只同签名`adb -s 192.168.10.149:5555 install -r <已核验APK>`，不卸载/清数据/读取密钥文件。凭据缺失/签名不符/网络失败则真实验收未通过。
- [ ] 正常应用进程真实HTTP先普通聊天，核对业务query/snapshot/runtime/回填0，无额外分类请求。随后精确发送“添加 10 个随机号码档案”，夹具仅约束可识别合成资料、不生成号码/工具。逐项checkNumber/不存在基线/唯一性/字段与模型提议一致/真实回执/持久读回；差集恰10且旧资料不覆盖，列表/详情实际刷新，截图只合成资料。失败保部分成功，先诊断，不原样重复新增多批、不SQL清理。
- [ ] 原业务API设LIGHT并记录截图，再精确发送“修改深色模式”；真实模型工具/colorMode=DARK→原UiEdits保存→读回DARK→Activity实际主题切换。重启进程后再读回/截图DARK，结束保DARK；原已DARK只算no-op，不能代LIGHT→DARK证据。
- [ ] 导出v2脱敏证据：原文/版本/model/protocol/profile、请求轮次/最终字节/历史量/schema数、阶段/总耗时、usage或null、HTTP/finishReason、逐步回执/核验数。禁key/header/body/思考/个人值/可枚举哈希；合成字段进程内/UI核对，导出stepId/谓词。独立审查只读本次引用。
- [ ] A1–A10/架构V1–V12逐项列通过/失败/未测/commit/证据，A6其他系统动作单列。任一原文失败则A9未通过，保成果报告；模拟/构建不替验收。提交最终证据。

<a id="coverage"></a>
## 覆盖、失败处理与交接

|需求/验收|步骤|
|---|---|
|R1/A1|S02、S10、S11|
|R2/A2|S09–S12|
|R3/A3|S03–S06、S11|
|R4/A4|S07–S08、S10–S12|
|R5/A5|S03–S06、S09、S12|
|R6/A6|S07、S10–S12|
|R7/A7|S01、S10–S12|
|R8/A8|S02–S09、S11–S12|
|R9/A9|S11–S12|
|R10/A10|S01、S08–S09、S11–S12|

各步产物/命令入证据；出界/换依赖须裁决。本轮不跑模型/设备。
