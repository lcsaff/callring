# AI 沟通独立复验

日期：2026-10-18（Asia/Shanghai）。任务 ai-recheck，reviewer epoch=1。结论 **passed**：AIR-001 至 AIR-005 逐项复验通过，本次审查范围未发现新的必须修复问题。原 ai-review 的 changes_required 结论及原报告保持不变。本结论供主负责人验收，不替代真实设备与服务验证。

<a id="binding"></a>
## 当前绑定与只读边界

- sourceTaskId=ai-implementation，sourceEpoch=9；reviewId=review-d6fed565cd212236ec16c7681ab1875b2f5edebcc736b76ce8ff9262a5b3d60f。
- baseCommit=b2c3af79570d03a2040d3c1e02405f56821bb160；targetCommit=91ea4d8b574712904e44872babc33eac98261829。
- sourceArtifactDigest=1706b4dd29bb6a8d2eae70b11fa19802bf8fa1af491d821eba081aed98a4f699。
- session_context 与派发标量一致，已 acknowledge 并取得 begin_round 回执：turnId=01a14f30-ef6a-7941-803b-cc18ccf959b6，checkpointId=checkpoint-222a51ad69d4ed732b7745d412cf7bfd9d8a743c127eae94fc85a8fc783519be。HEAD 等于 target。逐个验证112个成果 blob，全清单与 base..target 的112项差异路径完全一致；证据 binding-proof.json。
- 架构 blob=27888a84f68339d36c074ed587e04904d2df902a，实现设计=441bdcc39a2a15b905163424f672ab43c3411ed9，实施计划=3b7c9a3c901b8dc7b20a362488bc5c7c15c02759，历史批准文档未改。用户Q18将缺省预算改为49152，覆盖历史32768，未裁剪数据或schema。
- 原报告只读取得：git show 5925965774c170f7e8eab809effb5319111f3012:docs/plans/AI沟通审查.md。修正增量 c3a1ef9..91ea4d8 的27文件已与原五项意见核对。
- Git检出 C:/Users/GreatWall/.codex/worktrees/916e/callring 全程不写。目标通过 git archive 导出至 C:/Users/GreatWall/AppData/Local/Temp/callring-ai-recheck/source。全部构建、临时测试、XML与报告都在仓库外；没有产品代码修改、提交、子Agent、付费请求或生产安装。

<a id="verification"></a>
## 运行与计数

以下 E 指实际绝对目录 **C:/Users/GreatWall/AppData/Local/Temp/callring-ai-recheck**。命令工作目录 E/source。

```powershell
python tools/check_backend_boundaries.py
.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --quiet
.\gradlew.bat -I ../review.init.gradle :backend:test --tests '*.RecheckSendFenceTest' :ui:testDebugUnitTest --tests '*.RecheckProductionUiTest' :app:testDebugUnitTest --tests '*.RecheckDirectoryTest' --quiet
.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest --quiet
```

完整基线：backend=123、ui=202、app=135，合计 **460**；failure/error/skipped 全0。构建退出0，Lint **0错误、10警告**；边界 PASS: 48 JVM sources，v1 API合同一致。基线XML位于 E/baseline-backend、E/baseline-ui、E/baseline-app；baseline.log、boundaries.log、source/app/build/reports/lint-results-debug.xml 与 debug APK 留存。

审查员新写独立测试 **14**：RecheckSendFenceTest=3、RecheckProductionUiTest=10、RecheckDirectoryTest=1；failure/error/skipped 全0。最终精确类名选择，XML位于 E/final-independent-backend、E/final-independent-ui、E/final-independent-app，日志 independent-final.log。参数循环没有虚增JUnit数；460基线与14独立分别统计，后续恢复运行不再次累加。

review.init.gradle 仅为临时运行增加仓库外source与正式app编译类jar；实际Ui路由仍来自本次目标。真实AndroidRecordingStore、AiSettingsService、AndroidRecordingRuntime用于首配/确认；常规backend服务由既有ConversationFixtures的内存存储供给，未伪造业务目标。密文测试cipher可逆、系统provider/授权边界可控，不能证明真机Keystore或实际系统授权。独立夹具首轮发生classpath目录未被Android测试接入、缺少ports import的编译问题，修复外部夹具后运行通过；未据此报告产品问题。

<a id="air-001"></a>
## CHECK-AIR-001 — passed

原意见 AIR-001；R1/R4；A1/A4；independentRequired=true。

源码：ui/.../app/AiRoutes.kt:16、feature/ai/AiSettingsViewModel.kt:19、CredentialDialog.kt:53–60 将页面连接草稿与本地密钥合并到同一 patchCombined；credentialSaved仅重基准所存slot，另一slot/预算草稿保留。正式服务仍在AndroidRecordingStore.updateSettings的发布锁/偏好锁内做完整校验，密钥替换核对aiConfig版本。

独立实际页面：analysisFirstInstallThroughActualRouteAndStorage、transcriptionFirstInstallThroughActualRouteAndStorage 从正式空存储分别输入URL/model、打开本地密钥框。取消不写；注入一次存储失败后明文框和页面草稿仍存在，再次保存成功。弹窗打开后通过正式API改变录音开关，原子首配保留其最新值。落盘URL/model/key正确、另一slot空值不受影响，保存AI设置按钮回到无dirty状态。完整21域请求编码与安全视图均无密钥哨兵，密钥只用于连接对象/认证边界。

changedAiConfigurationRejectsLocalCredentialWithoutLosingDraft 在弹窗期间通过正式API改变另一slot模型，再点保存：CONFLICT、不替换旧密钥；取消后保留本页draft-model。基线AiSettingsServiceTest另外覆盖Keep/Replace、保留其他slot与预算草稿、显式旧预算、清除校验；CredentialDialogTest覆盖重建不恢复明文。

证据：E/independent/ui/RecheckProductionUiTest.kt；E/final-independent-ui/TEST-com.greatwall.callring.ui.app.RecheckProductionUiTest.xml，RECHECK_COLD两行、RECHECK_CREDENTIAL；E/baseline-app/TEST-com.greatwall.callring.platform.ai.AiSettingsServiceTest.xml。

<a id="air-002"></a>
## CHECK-AIR-002 — passed

原意见 AIR-002；R2/R4；A2/A4；independentRequired=true。

源码：AiRoutes.kt:24–27 捕获本次composition已显示的普通不可变连接值，显示与send使用同值。ConversationScreen展示下一发送的protocol/URL/model，busy时才另列本轮已发送目标。ConversationService.sendTo:45 与runTurn:78两次匹配已告知目标，原ensureActive/连接fingerprint取消继续生效。

独立 routeSendsExactlyRenderedDestinationEvenWithoutRecomposition 获取旧页面真实Compose点击回调，配置与VM feed换为新protocol/URL/model后，在新帧重组前触发旧回调：传出的仍是旧已显示值，被目标校验拒绝并保留输入；新标签显示后再次发送传出新值。这个UI边界使用受控sendTo记录器；正式服务的拒绝由下一独立用例验证。

staleDisclosureAndChangesDuringCaptureNeverReachHttpAcrossProtocolUrlModel 对三字段分别验证旧告知立即拒绝、捕获快照窗口改变连接后零exchange、重新告知新目标才exchange=1；newSession清空历史。activeRequestsStopAndClearHistoryForEveryDestinationFieldChange 对三字段分别验证请求中变更停止、client.cancel调用、历史清空。基线ConversationReviewUiTest核对IDLE/STOPPED/COMPLETED/FAILED最新标签与未知目标禁发；RenderedDestinationTest覆盖VM新值不能替代旧composition告知。

证据：E/independent/backend/RecheckSendFenceTest.kt、E/independent/ui/RecheckProductionUiTest.kt；E/final-independent-backend/TEST-com.greatwall.callring.RecheckSendFenceTest.xml 的RECHECK_SEND/RECHECK_ACTIVE；上述UI XML的RECHECK_RENDER_WINDOW。

<a id="air-003"></a>
## CHECK-AIR-003 — passed

原意见 AIR-003；R2/R4；A2/A4；independentRequired=false（仍新增独立用例）。

源码：ConversationScreen.kt:41按toolCalls非空标注“AI操作建议（未经验证，以操作回执为准）”，不猜测文字是否含成功。ReceiptCard逐个展示实际状态；协议历史与工具配对未删改。

独立实际服务 actualToolBatchWaitCancelAndPartialCommitPreserveVerifiableReceipts：模型携带“全部执行成功”与删除工具，等待时保留toolCalls证据；取消后删除未发生、CANCELLED回执留下；若先有一项真实设置提交，再取消第二项，则保留COMMITTED+CANCELLED并停止后续请求。实际UI actualReceiptsAndUnverifiedLabelsRemainDistinctForPartialCommit 逐项覆盖等待、取消、拒绝、失败、未知、提交及COMMITTED/FAILED/SKIPPED混合结果，文字一直带未经验证标签、回执各自显示。基线完整会话/协议测试仍覆盖失败/超限后保已提交结果及余项跳过。

证据：E/final-independent-backend/TEST-com.greatwall.callring.RecheckSendFenceTest.xml 的RECHECK_BATCH两行；E/final-independent-ui/TEST-com.greatwall.callring.ui.app.RecheckProductionUiTest.xml 的RECHECK_RECEIPT；对应外部源码。

<a id="air-004"></a>
## CHECK-AIR-004 — passed

原意见 AIR-004；R4/R6；A4/A6；independentRequired=true。

源码：ConfirmationTargets.kt从bound实际API解析职位、请假、归档/自述、连接；ToolExecutor.confirm在读取后、确认后都核对command版本，具体写/测试亦使用bound API。ForegroundActionService在beginLaunch/deliver校验真实command。ForegroundActionHost的ConfirmationSummary展示本地目标，缺证据禁用确认，不采信模型标签。

独立五个JUnit逐一覆盖O20/O32/O53/O57/O63：production ToolExecutor→真实backend设置/档案API或正式Android录音/AI服务→真实ForegroundActionService→Compose ForegroundActionDialogs。分别看到真实职位名、请假备注、服务模型、归档号码/ID、旧姓名→自述姓名。错误ID/slot直接FAILED且无pending；取消后版本和目标不变；确认期间分别改职位、请假、模型、归档generation/分析，点击旧确认后FAILED；删除、身份替换、付费测试均未执行；迟到VERIFIED不被接受，密钥不显示。

基线ConfirmationSummaryTest还验证所有摘要字段和模型伪标签/缺证据；ToolBusinessIntegrationTest的正式SQLite、录音库、偏好、API运行覆盖取消/目标变化，并以all72Tools...覆盖有效确认后的实际业务落点。独立确认测试未安装系统应用或发出真实付费测试。

证据：E/final-independent-ui/TEST-com.greatwall.callring.ui.app.RecheckProductionUiTest.xml 中五条RECHECK_CONFIRM；E/independent/ui/RecheckProductionUiTest.kt的五项confirmation测试；E/baseline-app/TEST-com.greatwall.callring.platform.ai.ToolBusinessIntegrationTest.xml；E/baseline-ui/TEST-com.greatwall.callring.ui.app.ConfirmationSummaryTest.xml。

<a id="air-005"></a>
## CHECK-AIR-005 — passed

原意见 AIR-005；R3；A3；independentRequired=false（仍新增独立用例）。

源码：SystemRecordingSource.describe:12实际读取tree根document显示名及持久READ状态，未配置NOT_REQUIRED、撤权DENIED、查询失败UNKNOWN；只允许安全叶子名，URI/路径式名称变null。AiSettingsService在发布锁外调用provider；RecordingSettingsView与SafeProjection输出folderDisplayName/folderAccess，CompleteSnapshotAssembly的D13接正式服务，D17的完整遍历与失败阻断保留。

独立 directoryMetadataFailureAndPrivacyAreIndependentOfEmptyFileList 用可控ContentProvider+正式服务/存储验证未配置、两个不同但均为空目录、撤权、SecurityException、provider异常、null cursor、Unix私有路径/URI/Windows路径式名称。两个目录的根显示名不同；错误状态明确；safeValue工具安全结果及包含21域和72schema的完整请求无URI、私有路径、provider错误原文或密钥。此独立请求的其它20域为合成空域，只用于编码边界；真实21域生产装配由完整基线CompleteSnapshotAssemblyTest另行验证，不混为同一证据。测试授权函数为可控边界，生产默认仍查真实persistedUriPermissions。

证据：E/independent/app/RecheckDirectoryTest.kt；E/final-independent-app/TEST-com.greatwall.callring.platform.ai.RecheckDirectoryTest.xml 的RECHECK_DIRECTORY；E/baseline-app/TEST-com.greatwall.callring.platform.ai.RecordingFolderViewTest.xml；正式CompleteSnapshotAssemblyTest。

<a id="regression"></a>
## 六项验收回归

|验收|本轮核对结果及实际证据|
|---|---|
|A1|拆页导航、原密文/旧配置、字段合并及密钥规则保留；AIR-001独立实际页面通过；AiSettingsServiceTest、AndroidRecordingStoreTest、DraftMergeTest通过。|
|A2|宽窄四入口共用同一tabs，ConversationNavigationTest通过；会话全部阶段/回执与错误、停止/新会话通过；AIR-002/003修正通过。|
|A3|21域全量source、分页外档案/审计/转写尾、源失败阻断及脱敏通过；AIR-005目录补充通过。CoreSnapshotSourceTest、RecordingSnapshotSourceTest、CoreSnapshotProjectionTest、CompleteSnapshotAssemblyTest保留。|
|A4|72个严格schema唯一且与矩阵/ToolExecutor分支/API一致；实际业务集成72条唯一TOOL_PROOF，非法额外字段均FAILED且不改版本；共用保存、通知、字段冲突与版本验证通过。AIR-001/002/003/004通过。|
|A5|默认49152、旧缺省/显式32768、完整全文/会话/schema/输出预算和源/请求保护保留；服务端上下文拒绝明确停止，未静默裁剪。真实小数据生产装配经预算后实际假HTTP=1。|
|A6|前台宿主一次票据、真实probe路径、取消/拒绝、迟到/重复、旋转与锁序测试通过；AIR-004具体目标与旧确认失效通过；真机仍是下述边界。|

本轮完整基线实际输出：REAL_DEFAULT_PROOF bytes=29219 estimate=37261 tools=72 domains=21 callers=1 archives=1 http=1。来自正式BackendGraph/SQLite/独立录音库与假HttpURLConnection；尾哨兵和密钥断言均在发出前验证，未删除原容量回归或裁剪72工具。72条TOOL_PROOF是一个JUnit内的循环行为证据，没有计成72个测试。

发布/并发核对：BusinessRevisionCoordinator与AndroidRecordingStore仍在短发布锁内合并/校验/入账，源读/目录provider与网络不包在长锁内；SharedBusinessCommands身份采纳沿用旧校验。PublicationAdaptersTest、RecordingPublicationTest、DraftMergeTest、RecordingCommandContextTest、BusinessRevisionCoordinatorTest、ForegroundLockOrderTest均在460基线通过；独立前台目标变化与捕获窗口测试进一步验证版本拒绝。

<a id="boundaries"></a>
## 剩余真实环境边界

没有把模拟结果当作设备证据：真实Keystore、系统权限/角色、SAF/tree持久授权与CSV、pin/overlay、SIM/厂商ROM录音、蓝牙/免提/息屏/重启/后台限制仍未实测。真实模型工具兼容性、费用、实际容量和错误格式亦未发请求验证。保持原设备offline边界，本轮未再次连接或安装生产包。以上不是本次五项修正的新缺陷，也不据自动测试宣称真机/服务全部验收。

本次无新增必须修复意见。最终是否采纳与验收、归档TEMP报告及只读HEAD检查点由主负责人处理。

<a id="evidence"></a>
## 证据封存

移除临时init后正常三个测试任务退出0，最终XML重新计数为123/202/135，共460，failure/error/skipped全0；正常报告输入已恢复。readonly-proof.json记录最终HEAD仍为91ea4d8b574712904e44872babc33eac98261829、Git status为空及diff --check=0。regression-proof.json核对72条唯一TOOL_PROOF，并保存实际构建APK大小/哈希。

123项证据的路径、大小与SHA-256清单位于 C:/Users/GreatWall/AppData/Local/Temp/callring-ai-recheck/evidence-manifest.json，SHA-256=341693af518fbe11d5bbae0c0ddaa325820ef7106a49918685fc8fd116111dc3。包含基线/独立XML、临时测试源码及init、正式编译类jar、目标source.zip、日志、绑定和只读证明、APK、Lint。报告自身哈希在session_report的evidenceRefs另列，避免自引用。
