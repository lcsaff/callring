# T12 具名操作目录与数字分派删除实施证据

本片基于 `0330a83157d08f4feddccd8c8ff1b09e01d52380`，仅在 `C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring` 实施。主工作区用户改动未触碰。本说明记录候选实施和实际验证，不代替负责人规格与独立质量审查，不表示 T13–T15 已完成。未 stage、commit、push、递增版本或执行设备命令。

## 唯一目录与执行入口

新增 `backend/.../application/conversation/operation/{OperationId,OperationDefinition,OperationCatalog}.kt`。81项显式业务身份、工具名、O/X代码、legacyIndex、displayOrder、schema、描述、历史runtimeDefaults、读写/确认/执行/作用域/读回策略均在生产目录声明。OperationId无ordinal语义；协议数字只在显式声明、唯一性检查和外部导出中出现，displayOrder只用于发现排序。目录支持输入乱序并按身份查找；启动验证五类重复、缺定义/执行、写缺scope/确认/读回、非写缺或不匹配source/计算/目录/计划声明及组合策略不一致。

49写与32非写分别为48直接写加1组合写、29 SOURCE_READ加1 COMPUTATION、1 CATALOG_READ和1 IN_MEMORY_PLAN。`CALLERS_SAVE_PROFILES`明确为PROFILE_BATCH / PROFILE_CHILDREN / CHILD_PERSISTED_READBACK，回执是BATCH_CHILD_PERSISTED_READBACK。实际子项使用同一CALLERS_SAVE_PROFILE作用域、检查、命令与持久读回，没有为组合项伪造不执行的直接scope。

ToolRegistry只有全部81项definitions一个入口。Executor、Batch、ScopePlanner、Reader、Verifier、ConfirmationTargets、TaskPlanner与ConversationService统一使用OperationId或目录策略。实际批处理把同一注入catalog传给作用域/读回，TaskPlanner也用该catalog识别档案。ConversationRequest.tools直接改为OperationDefinition，未留类型别名或旧构造转发。O01–O72的dependsOn/写taskId自动schema字段通过其显式commandShape声明保持，extensions保留各自显式schema，运行时不按数字区别。

QueryService和documentWriteVerified均是必需能力。生产ConversationAssembly继续显式提供真实文档验证；测试显式供自己的真实验证器或拒绝验证。仅写测试提供有真实source注册且拒绝意外访问的测试专用能力。删除Executor整套只读UI业务分支及其零消费者page助手，全部source读取走唯一ScopedToolReader。没有nullable queries、legacyReads或旧UI只读路径。

## 实际覆盖与保全

OperationCatalogTest覆盖五种重复、缺项/执行、写scope/确认/verification、错误写执行策略、读source/receipt一致性、组合child策略、49/32细分与乱序发现/schema。ToolCompatibilityBaselineTest直接从81项生产目录导出所有策略和runtimeDefaults，与冻结golden逐字比较；不按数字补算、不把golden作为actual。golden SHA仍为 `7e9735f0718740ef8c19785d61866ef6beec1b26e869aa074f87472b913113a1`。

ToolBatchEngineTest以seed1201的乱序目录执行原批处理、档案、外观、任务、恢复、自己提交/读回失败等全部回归。app ToolBusinessIntegrationTest以seed1202运行原72项Registry→Executor→Batch→ScopePlanner/Verifier完整链；原UI只读API断言迁为生产scopedQueryApi适配、sourceQueries计数和逐操作D域source stamp证据。档案dirty merge、position/whitelist、CSV真实输出、录音导入/重试/删除、身份采纳和密钥复制业务断言保留。seed1203的新扩展测试执行X01–X09，包括发现零source、数据分页/get/聚合/全文、D03详情、计划、组合档案子回执和真实关联删除。

既有确认取消/目标变化、AI未授权、长文本fingerprint、collection scope、generation、自己提交继续、观察保存恢复、COMMITTED_UNVERIFIED与已提交不重放保护均沿原回归保留。T11的授权marker→锁外稳定status采样→最终目标unchanged fence及其它scope/cancel/foreground保护逻辑未改动。发现/闲聊零业务source回归保留。数据库schema、codec、领域算法、Android组件身份与O/X/D外部标识未改动。

## 删除实账与守卫

实际删除ToolDefinition及index、toolPurpose与purposes表、definitionsMutable.size+1、72+9双API、数字OperationContract/prepare/read/verify/confirmation签名与调用、ScopedToolReader.legacyReads、nullable/default queries与文档验证、旧Executor只读UI分支及page助手。全部test/debug/构造调用一并迁移，无typealias/兼容重载/第二执行路径。

架构guard新增35种数字分派子例，涵盖Executor/Batch/Scope/Confirm/Verifier/Reader/Catalog中的index、legacyIndex、ordinal、displayOrder和operationCode解析，并保留schema容量/显式元数据/唯一性/发现排序合法例。中文许可改为OperationCatalog中OperationDefinition的字面量具名description精确字段解析，额外中文、工具名中文、计算description及其它文件均被拒绝。无整文件豁免。current-api/current-api-baseline已更新当前入口说明；历史附录与冻结样本未改。

真实后续删除门仍包括T13的BackendStore初始化/fixtures与目录整理、T14的统一CI闭包、T15设备与最终验收，本片不提前关闭这些门。

## RED、准备失败与首轮门（修复前历史）

- `t12-red-operation-coverage.log`：生产契约81项要求在旧72项实现上产生3 tests / 1 assertion failure。
- `t12-red-catalog-validation.log`：目录一致性负例产生6 tests / 2 assertion failures，之后实现验证通过。
- `t12-red-numeric-guard.log`：35个数字分派子例在旧guard下均未拒绝。
- `t12-red-catalog-description-guard.log`：新唯一目录的合法description在旧guard下被误拒绝，随后精确许可通过。
- Kotlin签名/调用迁移期间的编译准备失败和目录测试缺import单列在summary.preparationFailures，未充当行为RED。首次baseline失败的根因是历史runtimeDefaults中allDefinitions文本被迁移脚本误改，已原样恢复并由golden比较确认。

修复前首次统一完整门为任务要求的10个Gradle目标，日志 `build/qa/layer-boundaries/t12-full-gate.log`，实际exit0，BUILD SUCCESSFUL in 1m18s，155 tasks / 15 executed / 140 up-to-date。本门backend360为UP-TO-DATE（预冻结刚实际执行360项），UI312为UP-TO-DATE（T12没有UI源码变动，保留原XML时间戳），app332为EXECUTED；全部0 failure/error/skipped。contracts为NO-SOURCE。合计1004项XML结果，不声称1004项本门全重跑。assembleDebug和backendQa Kotlin实际执行，UI AndroidTest编译UP-TO-DATE、app AndroidTest Kotlin NO-SOURCE。lint实际任务与既有warning/hint数量逐项见summary，不宣称零警告。设备未运行。

两个Python guard与23/5项正反例均exit0，120个JVM生产源；git diff --check与untracked EOF/尾空白检查通过。本次app/build/qa的43份安全JSON复制到 `build/qa/layer-boundaries/t12-fixtures/`，每份路径/字节/SHA记录在summary，原文件保留。

## 初次候选冻结（修复前历史）

37个相对BASE变动或untracked的.kt/.java文件按sorted相对路径+LF+rawbytes（删除用DELETED）+LF聚合：`9911ca8272e7e12a38ad253abf8931e6c47f6164e1841295b9d9c5004469ac59`。逐文件manifest为 `build/qa/layer-boundaries/t12-source-candidate.json`，该JSON文件SHA为 `55eefebadf4d8c8cdc9f6190c218afc099727481cc1c933e7c039ff1adbbecef`。完整门后重算相同。实际XML按WindowsPath对象排序（Windows路径组件不区分大小写），再使用相对路径p.as_posix()+LF+原始XML字节+LF聚合、日志/lint/fixture hashes、任务状态、退出码与时间戳见 `t12-verification-summary.json`。

源码冻结后仅补本证据文档与summary；如负责人审查要求修复，须重新记录候选hash和相应验证。规格与质量结论由负责人后续记录。


## 独立审查删除尾巴修复（候选二）

负责人及独立质量指出OperationContract data class和ToolRegistry.operationContracts()只有覆盖测试消费者，生产无引用，属于迁移后未删除的旧projection。rg复核一致后，删除整个OperationContract.kt文件与两声明，真实ReceiptPolicy枚举迁入operation/ReceiptPolicy.kt，OperationDefinition/Catalog真实imports同步；覆盖测试直接验证唯一ToolRegistry.definitions，不留alias/forwarder。新增架构删除负例先产生24 tests / 2 assertion failures，之后24项通过。定向ToolOperationCoverageTest、OperationCatalogTest、ToolCompatibilityBaselineTest共10项通过。

首次37源路径hash与首次完整门作为修复前历史证据保留在t12-source-candidate-before-projection-review.json / t12-verification-summary-before-projection-review.json / t12-full-gate.log。当前候选改为38路径（包括旧OperationContract.kt的DELETED项），源码hash `86729fd82a84ee046f799a92646db274d583b677eecd649e9b6aa5f27ee94fc4`。修后完整门另存t12-full-gate-after-review.log；最终实际状态和计数以更新summary为准，修复前门不冒充修后验证。


## 修复后最终验证与审查

修后统一完整门实际exit0，`t12-full-gate-after-review.log`：BUILD SUCCESSFUL in 1m8s，155 tasks / 16 executed / 139 up-to-date。backend360与app332均本门EXECUTED，UI312 UP-TO-DATE，contracts NO-SOURCE，全部0 failure/error/skipped。assemble与backendQa Kotlin EXECUTED；UI AndroidTest Kotlin UP-TO-DATE，app AndroidTest Kotlin NO-SOURCE。24/5项架构正反例与双guard通过，120JVM源；最终diff/未跟踪文件空白检查通过，未执行设备。43份当前JSON安全副本在t12-fixtures，首次副本另保存在t12-fixtures-before-projection-review，原始文件均未删改。

当前38路径源码聚合仍为 `86729fd82a84ee046f799a92646db274d583b677eecd649e9b6aa5f27ee94fc4`，manifest SHA `eae254901a26efabb84a5a2a61075135c9a2f586d26e8cb1debba78d6d80e000`。修后backend/UI/app XML聚合分别 `8fb18d43436ef59b95b6a58ae17cea8ef269a717f9eb97b5b666a1b92cd55181` / `309b62e48f7949b9113b3682febd164c19b48731a4e622775ba0b8fe060e6acf` / `164fe08053009228bdb2eec160fa7c6d4500ca46b1307f95d995265108d8257e`，采用上文明确的WindowsPath排序及路径/rawbytes算法。最终gate日志SHA `94fb162200dd6ce43eaac7ef24661c115fe32201297f66837398daf58ef575a4`。详细任务状态、XML路径/时间戳、lint与43份fixture哈希均在最新summary。

负责人已通知规格delta与独立质量delta最终PASS，唯一旧projection问题关闭。当前源码不再变更，Gradle所有权交回负责人，由负责人记录review/checkpoint与后续T13a；本片未commit/push/version/device。
