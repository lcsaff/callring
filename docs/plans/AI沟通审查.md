# AI 沟通独立审查

日期：2026-10-18（Asia/Shanghai）。任务 ai-review，reviewer epoch 1。结论 **changes_required**：5 项必须修复，未修改产品代码，未使用子 Agent。原意见由主负责人处置；自动测试通过不等于 A1–A6 全部验收通过。

<a id="source"></a>
## 绑定与证据范围

- 源任务 ai-implementation，绑定 sourceEpoch=8；base `b2c3af79570d03a2040d3c1e02405f56821bb160`，target `c3a1ef9c4d4968b186fe3f9549dae0ee8c375624`，artifactDigest `70ad87dda077d3e1566c3991a476d25e92b48918a4f05efc37c8be1b732c763d`。session_context 与派发一致；审查检出 HEAD 等于 target。逐个 git rev-parse 校验全部 105 个文件 blob，无差异；关闭 core.quotePath 后比对 base..target 的完整差异清单，105 项完全一致。session_status 对 reviewer 仅返回自身任务，未把它误作源任务实时状态。
- 获批架构 blob `27888a84f68339d36c074ed587e04904d2df902a`，实现设计 `441bdcc39a2a15b905163424f672ab43c3411ed9`，实施计划 `3b7c9a3c901b8dc7b20a362488bc5c7c15c02759` 均与检出相同。已阅读三篇、README 全篇与 AI沟通验收。用户 Q18 补充将缺省 context 改为 **49152**，覆盖旧文档的 32768；不改历史批准 blob，也不改变全量与全部 schema 要求。
- 以下源码路径均相对本目标提交。定位前缀 B=`backend/src/main/kotlin/com/greatwall/callring/application/`，A=`app/src/main/kotlin/com/greatwall/callring/`，U=`ui/src/main/kotlin/com/greatwall/callring/ui/`。临时测试只位于 `C:/Users/GreatWall/AppData/Local/Temp/callring-review-epoch8/`（下称 E），通过 E/review.init.gradle 添加外部 test source。没有将临时测试加入产品或提交范围。XML、夹具和日志留存供主负责人复查；测试的通过表示断言准确复现现状，不能把“复现问题的测试通过”写成产品修复。

<a id="verification"></a>
## 实际运行

审查员运行，退出码均为 0：

```powershell
python tools/check_backend_boundaries.py
.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --quiet
.\gradlew.bat -I "$env:TEMP/callring-review-epoch8/review.init.gradle" :backend:test --tests "*Review*" :ui:testDebugUnitTest --tests "*Review*" :app:testDebugUnitTest --tests "*Review*" --quiet
```

基线 XML：backend 121、ui 196、app 132，合计 **449**，失败/错误/跳过均 0；边界脚本 PASS 47 JVM sources。Lint **0 error、10 warning**。基线 XML 保留在 E/baseline-backend、baseline-ui、baseline-app；APK 与 lint 报告位于正常 build 目录。临时注入移除后再运行正常三个测试任务，恢复正常构建的测试输入与报告。

独立临时测试 **10** 项，失败/错误/跳过均 0：ReviewColdStartTest 1（内含两 slot）、ReviewDirectoryViewTest 1、ReviewConversationTest 2、ReviewCommandEvidenceTest 2、ReviewRealDraftTest 1、ReviewVisibleFactsTest 3。最终 XML 保留 E/final-independent-app、final-independent-backend、final-independent-ui。首次 ReviewRealDraftTest 用虚拟时钟等待真实 IO 导致夹具超时；改为 runBlocking 的真实超时后通过，未改产品与断言，不将该夹具失败当产品发现。

已读取实际 XML 的关键输出：

```text
REAL_DEFAULT_PROOF bytes=29158 estimate=37194 tools=72 domains=21 callers=1 archives=1 http=1
COLD_START_PROOF slot=ANALYSIS page=VALIDATION dialog=VALIDATION combined=SUCCESS
COLD_START_PROOF slot=TRANSCRIPTION page=VALIDATION dialog=VALIDATION combined=SUCCESS
TARGET_PROOF beforeSend=https://old.example/v1 · old; configured=https://new.example/v1
PREMATURE_TEXT_PROOF pendingVisible=true actualReceipt=CANCELLED successTextRemains=true
REAL_VM_PROOF clean=7 dirtyMerge=9/22 sameField=CONFLICT persisted=40 draft=30
COMMIT_FAILURE_PROOF status=COMMITTED evidence=true postCommitError=schedule.failed
ADOPTION_PROOF boundUiReadWriteGap=CONFLICT for profile and recording revisions; no overwrite
D13_PROOF sameViewForDifferentRoots=true data={"recordingEnabled":false,"automaticAnalysis":false,"captureMode":"SYSTEM_ONLY","folderConfigured":true,"revision":2}
```

第一行来自真实 SQLite/录音/偏好装配和假 HttpURLConnection：预算检查通过之后实际打开 outputStream 一次，保留短转写尾哨兵和完整 21 域/72 schema。不是纯 DTO 大小推算。缺省 49152、旧缺字段读取 49152、显式 32768 保留均有真实回归，原容量红项没有被删除或裁剪规避。

<a id="air-001"></a>
## AIR-001 [P1] 首次配置没有可成功保存 URL、model 和密钥的界面路径

requirementIds=[R1,R4]；acceptanceIds=[A1,A4]；blocking=true；verificationRequired=true；independentRecheckRequired=true。

**触发与影响：** 两组连接全空的新安装，在 AI 页面填写 URL/model 后保存会因没有 key 校验失败；先去“本地编辑密钥”保存又因持久化 URL/model 仍为空而失败。两种顺序均不能创建第一组有效连接，因此新用户无法开始沟通或配置转写。已有完整配置的 fixture 能通过密钥替换测试，不能覆盖该路径。

**定位：** `U/app/AiRoutes.kt:17` 只传 api 与 slot 给 CredentialDialog，不传页面草稿；`U/feature/ai/AiSettingsViewModel.kt:23` 只提交非敏感连接补丁；`U/feature/ai/CredentialDialog.kt:28` 从持久化读取基准，`:50–56` 在 Replace 时用空 ConnectionPatch 调 patchCombined。`B/api/RecordingApi.kt:37–38` 对任何非空连接要求完整 validate，`A/platform/ai/AiSettingsService.kt:74` 与 AndroidRecordingStore.updateSettings 实际执行该校验。

**证据：** E/app/ReviewColdStartTest.kt 的 `bothVisibleSavePathsRejectFirstConnectionButAtomicFullInputWorks` 使用正式 AndroidRecordingStore、AiSettingsService，ANALYSIS/TRANSCRIPTION 各验证两种 UI 请求均返回 VALIDATION、存储仍等于 RecordingSettings()；同一服务同时传入有效 URL/model 补丁与 Replace(key) 立即成功。最小请求为 `patchAi(base, connectionPatch)` 与 `patchCombined(recBase, Keep, base, emptyPatch, Replace(key))`，二者均失败；将 connectionPatch 与 Replace 合并即成功。没有外部网络。

**必需修正与验证：** 在本地凭据保存事务中组合当前页面的连接草稿与密钥，并保留原完整校验、基准冲突和不出聊天边界。通过实际页面从空存储分别创建两组连接；覆盖取消、失败保草稿、保存后 UI 状态、旧密钥保留/替换以及另一页并发变更。独立重验首次配置和密钥出站边界。

<a id="air-002"></a>
## AIR-002 [P2] 切换连接后，发送前仍告知旧服务目标

requirementIds=[R2,R4]；acceptanceIds=[A2,A4]；blocking=true；verificationRequired=true；independentRecheckRequired=true。

**触发与影响：** 已经发过一轮后更改分析 URL/model；当前请求正确停止，但 ConversationState.target 未清理。VM 已收到新目标时，页面仍优先显示旧 state.target。用户在旧服务标签下点击发送，完整业务文本直接发送到新目标，标签只在 runTurn 启动后改变，未满足 ARCH#authorization/Q15 和实现设计 conversation 的“下一发送告知新目标”。空闲时改连接同样存在。

**定位：** `U/feature/conversation/ConversationScreen.kt:22` 收集最新配置，`:32` 却优先 state.target；`B/conversation/ConversationService.kt:156–171` 连接变化只清 history/发布 STOPPED，`:77` 要到下一 runTurn 才更新 target。

**证据：** E/backend/ReviewConversationTest.kt 的 `stoppedTargetRemainsOldUntilNextRequestIsAlreadyStarting` 让真实 ConversationService 首轮网络挂起，改配置并发布 aiConfig；STOPPED 时断言旧 target 仍存在，第二轮 exchange 实际拿到 `https://new.example/v1`。E/ui/ReviewVisibleFactsTest.kt 的 `stoppedScreenShowsOldEndpointEvenWhenLatestConfiguredTargetIsNew` 用真实 Compose 屏幕确认配置已新而标签仍旧。

**必需修正与验证：** 区分本轮发送目标与下一发送目标，在允许再次发送前显示最新目标。验证请求中和空闲时的 URL/model/protocol 变化，以及停止、返回页面、新会话；首个新目标请求发出之前 UI 必须已告知新目标，历史清理和同轮不转发仍成立。此项涉及全文外发告知，需独立重验。

<a id="air-003"></a>
## AIR-003 [P2] 工具确认前与取消后，未验证的成功文字仍作为普通 AI 回复展示

requirementIds=[R2,R4]；acceptanceIds=[A2,A4]；blocking=true；verificationRequired=true；independentRecheckRequired=false。

**触发与影响：** 合法 ToolBatch 同时携带“录音已删除”和 recordings_delete。执行尚在等待前台确认、没有任何收据时，用户已看到普通 AI 卡片声称删除完成；取消后该文字仍保留，与本地 CANCELLED 收据相矛盾。实际业务未误执行，问题是结果展示。ARCH#api 明确提前“成功”无效，计划 P14 要求非完成不显示成功；仅有 system prompt 不能保证模型遵守。

**定位：** `B/conversation/ConversationService.kt:105` 在工具执行前把 ToolBatch.text 加入可见历史，`:110` publish 暴露；`U/feature/conversation/ConversationScreen.kt:37` 不区分 toolCalls 的未验证文字与最终回复，直接展示。

**证据：** E/backend/ReviewConversationTest.kt 的 `assistantSuccessTextIsVisibleWhileDeleteHasNoReceiptAndCanStillBeCancelled` 运行真实服务和前台桥，等待确认时成功文字可见、receipts 为空，随后系统取消得到 CANCELLED，原文字仍在。E/ui/ReviewVisibleFactsTest.kt 的 `waitingScreenDisplaysUnverifiedModelSuccessAsOrdinaryAiMessage` 确认真实 Compose 展示该文字。

**必需修正与验证：** 对带工具的模型文字明确标注尚未验证/待执行，或延迟作为结果展示；不要用字符串猜测“成功”，协议历史仍保持完整。验证等待、拒绝、取消、失败、部分提交和完成，保证实际收据决定操作结论。本项不改变提交逻辑，普通复验即可，无须单设独立重验。

<a id="air-004"></a>
## AIR-004 [P2] AI 前台确认缺少用户可核对的实际目标

requirementIds=[R4,R6]；acceptanceIds=[A4,A6]；blocking=true；verificationRequired=true；independentRecheckRequired=true。

**触发与影响：** 从沟通调用删除职位/请假/录音、采纳身份或测试连接时，弹窗只说“所选职位”“本次录音”或“已保存的服务”，不显示真实职位名、请假区间、归档号码/时间、自述字段或测试 slot/host/model。模型选择错误 ID 时，用户无法在可信本地确认中辨认错误，仍可能批准删除/覆盖或付费测试。原页面虽部分弹窗也用泛称，但用户已从具体条目/归档详情发起；聊天入口没有这个可信选择上下文。不是样式要求。

**定位：** `B/conversation/ToolExecutor.kt:48` 只把模型 arguments 和 operation 交前台；O20/O32/O53/O57/O63 路由均采用该确认。`U/app/ForegroundActionHost.kt:150–158` 只按操作名生成固定通用文字，不加载或展示权威目标。ARCH#authorization/Q15 要求保留“具体删除/采纳/测试确认”。

**证据：** E/ui/ReviewVisibleFactsTest.kt 的 `deleteConfirmationOmitsRealTargetNameAndId` 传入实际 ticket 参数 id=37，真实 ForegroundActionDialogs 仅显示泛称，连 ID 也不出现。逐分支代码核对其余四种确认同样不展示目标。测试连接的开销告知存在，但目标辨认缺失。

**必需修正与验证：** 本地从真实 API 解析目标，展示足以辨认的安全摘要；采纳显示将替换的自述值，测试显示 slot 与实际 host/model。绑定确认时实体/连接版本，目标变化不得复用旧确认，不能信任模型传入名称。逐一测试 O20/O32/O53/O57/O63 的目标显示、错误 ID、取消、确认期间目标变化，以及实际操作落点；此项涉及破坏性/付费操作，需独立重验。

<a id="air-005"></a>
## AIR-005 [P2] D13 遗漏已选录音目录的安全显示信息

requirementIds=[R3]；acceptanceIds=[A3]；blocking=true；verificationRequired=true；independentRecheckRequired=false。

**触发与影响：** 目录已配置时，AI 只能看到 folderConfigured=true，无法回答当前到底选了哪个录音目录。两个不同且均为空的已授权目录，其 D17 均为空文件数组，D13 除 revision 外完全相同；没有任何域提供根目录显示名。实现设计 settings 明确约定“目录显示/授权状态”，架构 D13 为目录授权安全视图，不能把所有配置读取简化成“是否配置”。

**定位：** `B/api/AiSettingsApi.kt:18` DTO 只有 folderConfigured；`A/platform/ai/AiSettingsService.kt:21` 只判断 systemFolder!=null；`B/SafeProjection.kt:62` 同样只输出布尔值。`A/bootstrap/CompleteSnapshotAssembly.kt:32–38` 的 D17 只投影文件，授权失败会阻断（这点正确），不提供根目录名称或显式访问状态。

**证据：** E/app/ReviewDirectoryViewTest.kt 的 `selectedRootHasNoHumanReadableIdentityOrAccessInD13View` 使用正式存储/服务，切换两个根目录后将 revision 归零比较得到完全相同 DTO；安全 JSON 的字段集合固定为五项，未包含目录显示或访问字段。全快照装配代码排除了其它根目录信息来源。

**必需修正与验证：** 补充经真实系统查询得到的目录显示名和访问状态（未知要明示），空目录也保留根目录显示信息；不要求暴露可调用 URI、完整路径或模型可指定的资源定位。验证未配置、正常授权空目录、两个目录切换、撤权、查询失败，并断言全请求与工具结果无 URI/私有路径。局部 DTO/投影扩展，普通复验即可。

<a id="coverage"></a>
## A1–A6、D01–D21 与 O01–O72 核对

|验收|独立核对及结论|
|---|---|
|A1|拆页导航、旧命名空间/加密配置、两页补丁和冲突路径存在；AiSettingsServiceTest/AndroidRecordingStoreTest 通过；首次配置 AIR-001 阻断。|
|A2|宽窄共用四入口、会话状态/错误/9种回执、busy/stop/newSession 测试通过；服务标签 AIR-002 与未经验证文字 AIR-003 未满足。|
|A3|21域完整构造与 SQL/录音源全量读取、失败阻断、密钥脱敏已有真实证据；D13 的 AIR-005 尚缺字段。|
|A4|72项真实路由已逐行与矩阵/API核对，存储、校验、通知及版本接入存在；真实 VM 合并/冲突通过；AIR-001/002/003/004 仍须修正。|
|A5|49152默认、小数据首轮HTTP=1、全schema、输出/会话/JSON预算、源/请求/响应限额、工具后超限保提交、400上下文与一般错误区分通过；未发现必须修复项。真实服务容量未测。|
|A6|前台 lease、稳定requestId、重复/迟到失效、取消/拒绝、真实probe和 SAF 平台路径代码已核对，自动测试通过；具体确认 AIR-004 尚缺；真机系统交互未测。|

|域|源码与真实证据/边界|
|---|---|
|D01|CoreSnapshotProjection 全部号码档案，CoreSnapshotSourceTest 实际251档案，非当前页。|
|D02|全部职位与我的职位，含 inactive；安全投影 PositionSettings。|
|D03|全部 RawCallRecord，显式保留 hidden、link、account、原始事实及 legacy 字段。|
|D04|共用 visible/detail 投影，去重视图与 D03 原始事实分开。|
|D05|全部统计区间、每号码全时段及滚动统计；CoreSnapshotProjectionTest 极值时间戳通过。|
|D06|当前与历史 schedules、当前工作状态/下个边界；无页面日期过滤。|
|D07|全部缓存年份、真实 holidays 与 makeupDates。|
|D08|全部 date overrides 及 windows。|
|D09|全部请假、版本、按同一采样时间的状态。|
|D10|评分、quiet、lastQuietMode、统计/overlay 设置。|
|D11|真实偏好外观；PublicationAdaptersTest 提交通知。|
|D12|真实铃声配置、播放状态；不投私有音频路径。|
|D13|录音安全配置已接源；AIR-005 遗漏目录显示/访问信息。|
|D14|两slot URL/model/protocol/keyConfigured 与预算，旧预算兼容。|
|D15|独立录音 events/links/deleted，含负ID；RecordingSnapshotSourceTest 保统计停用时记录。|
|D16|归档完整转写/分析/状态/代次/重试字段，尾哨兵保留，音频路径排除。|
|D17|授权目录完整稳定枚举与本地句柄；DirectoryEnumeratorTest 覆盖500/501、层数、变化、循环，失败不假空。|
|D18|全部持久绑定与实测 installed IDs；不只当前组件。|
|D19|全部 Capability（含麦克风）、运行/播放实测；未知不伪grant。|
|D20|现存全部 diagnostics_v2 与 legacy；26条与尾文本、坏行失败测试通过，未沿用UI take(18)。|
|D21|captureFull 同 SQL 事务全部 calendar_audit；审计尾与 pendingAudits 清空后仍可读测试通过。|

O01–O72：ToolRegistry 固定72唯一ID/name/schema；逐个核对 ToolExecutor 的 1..72 分支与架构矩阵，均有真实 API 路由。`ToolBusinessIntegrationTest.all72ToolsInvokeProductionBusinessApisAndInvalidSchemaNeverMutates` 使用正式 backend/SQLite/偏好/录音/媒体适配器，XML 包含 **72 条唯一 TOOL_PROOF**，每个工具有效请求实际调用对应接口、额外非法参数返回 FAILED 且版本不变。不能将这一项 JUnit 虚增为72个测试；也不能把非法 schema 用例当成全部业务校验的覆盖。

|逐项范围|实际核对的共用路径与补充验证|
|---|---|
|O01–O06|号码校验/home/callers读API，保显式分页，不用作全量源。|
|O07–O10|档案/白名单/报表/CSV；原号码/职位校验，CSV实际写入及防注入测试。|
|O11–O17|summary/统计/overlay/quiet读写，原权限检查、quiet模式记忆与校验。|
|O18–O24|职位/我的职位/工作时间/重置，原IN_USE/时区/版本；O20确认见AIR-004。|
|O25–O32|日历/同步/导入/指定日期/请假，真实JSON限制、审计/重算；网络/文档读取在发布锁外；O32见AIR-004。|
|O33–O37|评分/外观原验证及补丁；DraftMergeTest、真实VM同步；显式null清外观测试。|
|O38–O43|原铃声配置/导入/还原/预览/停止；媒体20MiB/可播放、代次；预览仅LAUNCHED。|
|O44–O49|运行/诊断/通话同步/绑定；同步仅QUEUED，真实绑定保存。|
|O50–O54|归档/安全设置/组合patch/测试/详情；O53具体目标见AIR-004，首配见AIR-001。|
|O55–O61|录音身份解析/系统句柄或SAF导入/retry/delete/play/stop/tree/目录；256MiB、迟到导入不得复活、真实持久授权检查；O57见AIR-004。|
|O62–O66|全绑定/身份采纳/安全AI配置/补丁/本地密钥；只自述非空姓名单位，位置与白名单保留；O63见AIR-004，O66冷启动见AIR-001。|
|O67–O72|权限/应用设置/pin/overlay/真实call预览/本地slot复制；probe判授权、pin/overlay仅启动、复制版本与转写OPENAI约束。|

<a id="refuted"></a>
## 已否证候选与回归判断

1. **旧表单覆盖候选已修复。** DraftEditor.refresh:34–36 判断 draft==baseline 时替换；SettingsViewModels 的 Quiet/Work/Score 均传 saveWithBase=api.edits。真实 Score VM + 正式 CallringBackend/ChangeFeed 的 ReviewRealDraftTest 实证 clean 5→7 刷新；脏 repeatMinutes=22 时外部权重改9，保存保9/22；同字段外部40、本地30时CONFLICT且存储40、草稿30。其它两类复用同一 DraftEditor，保存均在 UiEditsService 基准合并并生成受guard保护的上下文，没有仅工具保护而旧UI绕过的问题。
2. **身份采纳候选在生产入口已保护。** SharedBusinessCommands 本身为分步读写，但 BackendGraph.kt:150 在UI入口先捕获完整版本并注入 boundApi；`:153–158` 将同context传入 callers，最终 BackendContext.mutate 锁内guard，录音CAS也推进同coordinator。ReviewCommandEvidenceTest 在读后分别模拟资料变更与录音版本变化，均返回CONFLICT，不覆盖白名单/姓名。未把单独未绑定的测试替身当成生产UI。
3. **提交后异常证据丢失候选已修复。** ToolExecutor.failure:169 查询 coordinator.committed，有证据返回COMMITTED并保error/commitRevision。ReviewCommandEvidenceTest 真实业务先提交quiet，随后注入API失败，仍保存NON_WORK并回COMMITTED+证据+后续错误。ConversationService 的取消捕获也查提交证据。
4. **锁序与长操作：** ForegroundActionService.hasHost 为volatile无反向锁；ForegroundLockOrderTest 两线程栅栏通过；ConversationServiceTest 网络等待中仍可登记来电，旧响应不能写。录音import/媒体prepare、目录枚举、HTTP、日历fetch在短发布锁外，最终版本/代次guard；RecordingCommandContextTest 删除无需等待导入准备且迟到导入不能复活归档。未发现新增必须修复的来电锁序问题。
5. **README外发说明：** 全篇已核对，录音分析只发对应录音/转写，主动沟通明确发送完整业务文字、配置与运行状态；没有残留“不会上传全部通话历史”的相反承诺。449项与未实测边界分别说明，未把模拟授权/模型能力写成实机通过。

<a id="limits"></a>
## 必须保留的验证边界

审查员实际 `adb devices -l`：`192.168.10.149:5555 offline`。未安装生产包、未清数据、未自动解锁、未调用真实付费服务。权限/角色、SAF音频/日历/CSV/tree、旋转系统结果、桌面pin安装、overlay显示仍需在线设备复验；Robolectric/测试宿主不是设备授权证据。

真实模型工具能力/上下文错误格式、服务端真实容量、真实SIM双方录音、听筒/免提/蓝牙、锁屏与30分钟后台、重启及厂商省电限制仍未实测。该边界本身没有被列为产品缺陷，但不能报告这些项目已通过。

AIR-001–005 都必须按验收ID复验；001/002/004 因首次凭据保存、全文外发告知、破坏性/付费确认风险要求独立重验；003/005要求修正者提供针对性自动/界面证据。复验须绑定修正后的源epoch和完整成果清单，保留本次原始结论。
