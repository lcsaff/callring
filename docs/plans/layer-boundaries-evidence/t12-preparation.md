# T12 具名操作与旧执行路径删除准备（非验收）

独立只读复核基于 db9b9a2。冻结81工具 golden 与 SHA pin 保持原样；具名目录须导出完全相同的外部数据。

## 同批替换的数字依赖

| 位置 | 当前行为依赖 |
|---|---|
| ToolRegistry / CapabilityCatalog | 注册长度产生 index、推导 O/X code、purposes[index-1]、默认描述和发现顺序 |
| OperationContract | 数字决定 receipt/sharedApi/读写覆盖，当前只覆盖72项 |
| ToolExecutor / ScopedToolReader | 73特例、数字读集合、target和业务when、legacyReads、queries nullable 两条执行路径 |
| OperationScopePlanner / ConfirmationTargets | 写操作集合、字段/存在性/引用/集合scope、权威确认目标 |
| ReceiptVerifier | 数字选择读回/reset/import子类和CSV/权限证明 |
| ToolBatchEngine | 恢复读回、外观计划、profile预检/effectScopes、批量展开、身份准备、task_prepare特例 |

当前未见生产 ordinal；新枚举也禁止借 ordinal 分派。TaskPlanner 的 TaskKind 保持语义类型，不额外引入数字映射。

## 最小结构与删除

OperationId 使用81个业务名称，OperationDefinition 显式保存 id/name/code/legacyIndex/displayOrder/schema/description/read-write/confirmation/receipt/execution/scope/verification 元数据，OperationCatalog 按名称/id查找并启动验证；发现按 displayOrder 排序。Executor/reader/planner/verifier 可保留内聚 when(OperationId)，不引入反射或巨型回调框架。

删除 ToolDefinition.index 行为入口、toolPurpose(index)、旧72项单独契约、数字集合及 overload。legacyIndex 只用于外部兼容数据导出/历史展示。O01–O72 原有 dependsOn、写工具 taskId 的自动字段规则与 extensions 的区别必须保留。

ConversationService 生产始终提供 QueryService，nullable queries 旧模式仅由测试使用：ConfirmationTargetTest、NumberDeletionToolTest、ToolOperationCoverageTest、ToolBusinessIntegrationTest 的原72项覆盖/确认测试。迁测试为真实适配/可计数假源的 QueryService，改必需参数并删除 read→UI API 重复分支；不为旧测试保留可选构造器。发现/闲聊仍验证零业务 source 查询。

## 链路保全

- 删除号码：profile/calls/archive/binding 集合scope、权威两次确认、三步骤共用 continueOwnCommits、录音→核心→绑定、D03/D16/D18读回。
- 采用身份：archive generation、name/organization长文本fingerprint、权威确认、profile dirty merge和字段读回。
- 导入录音：授权handle/foreground picker/generation；CSV DOCUMENT_READBACK；权限 VERIFIED 证据。保持 LAUNCHED/QUEUED/COMMITTED_UNVERIFIED 区别，读回失败不能重放已提交写。
- 批量档案：1–32子项、逐项业务id、预检不存在/唯一号码、部分失败和归组回执。
- scope已披露观察的保存/恢复、自有提交marker、continuation不可遗漏。

## 验收

随机重排定义输入后，schema/code/legacyIndex/描述默认值/确认/scope/执行/读回关联保持；发现顺序稳定。除元数据比较，还执行档案、quiet、appearance、删除、身份、task_prepare、批量、CSV/权限代表流程。

负例覆盖重复id/name/code/legacyIndex/displayOrder、缺81定义/执行/读回声明、写缺scope/确认、读缺source/纯计算声明。覆盖从72扩到81，业务测试按OperationId组织；baseline从生产目录导出，不在测试按数字重新计算策略掩盖遗漏。

最终无nullable queries、numeric overload、purposes[index-1]、definitionsMutable.size+1或双读取执行路径；架构负例禁止executor/planner/verifier/reader用legacyIndex/ordinal/数字范围选择行为，允许schema固定数值/容量/D/O/X协议数据。

现行 `check_backend_boundaries.has_presentation_literal` 仅精确允许 CapabilityCatalog 的 purposes 字符串列表、ToolRegistry 五参数数字 ToolDefinition 的描述。具名元数据迁走时，应同步改为实际唯一目录描述字段的精确解析与正反例；不要为了过门而把描述留在按索引取值的旧表，也不要整文件豁免中文字符串。该守卫更新可随T12进行，T14仍复核最终硬规则闭包。

## 0ce2011 独立只读目录补核

`/root/t12_catalog_audit` 未修改文件或运行Gradle。实际81项为49写/32非写：29 SOURCE_READ、1 COMPUTATION、1 CATALOG_READ、1 IN_MEMORY_PLAN。批量callers_saveProfiles为独立组合写，子项逐个走saveProfile scope/确认/读回，不应为凑覆盖给批量声明一个不会被执行的直接scope/verifier。目录须明确组合策略及BATCH_CHILD_PERSISTED_READBACK；CATALOG_READ/IN_MEMORY_PLAN目前也只由baseline测试补算，迁成实际生产元数据。

ToolCompatibilityBaselineTest目前按数字补算extensions的policy、confirmation、runtimeDefaults、discoveryOrder，迁后必须从生产目录直接导出，不能保留测试fallback或把golden读回当实际结果。冻结文本中的历史runtimeDefaults说明也原样保持，不能顺手修正字符串。schema自动dependsOn/taskId仅适用于原72项，extensions保持显式schema。更新current-api-baseline.md中definitions=72/allDefinitions=81的当前入口说明，历史冻结样本不改。

身份统一还须覆盖Batch按callers_saveProfile字符串进行批量展开/计数、TaskPlanner的profile计数、ConversationService的batch/discover phase判断。ConversationPorts.ConversationRequest仍引用ToolDefinition，改类型时同步消费者，不留typealias；旧Executor只读分支删除后page helper若零消费者一并删。

随机重排列表要注入实际Catalog→Registry→Executor→Batch→ScopePlanner/Verifier，不能仅重排后sort再比较元数据。app ToolBusinessIntegrationTest原72覆盖当前有意不提供queries并断言只读调用UI API，须改为真实source适配与域读取证据，不能给empty QueryService后删原业务断言。保留写用例为dirty merge所做的API读取。重点回归reset回执值、import source、CSV文档证明与capability VERIFIED、scope观察保存/恢复、自有profile effectScopes前缀、resume只读回不重放、no-op读回失败与已提交未验证的区别。

同一执行链的documentWriteVerified当前在ToolExecutor/ConversationService默认为false；实际ConversationAssembly已显式供真实ReadbackDocumentStore验证。迁必需queries时也去掉这个生产缺能力默认，全部构造器明确供能力，测试用自己的可计数验证器，CSV读回断言保持。真正不验证的测试分支可由测试显式拒绝，不能由生产默认吞掉装配遗漏。
