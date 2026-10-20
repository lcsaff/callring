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
