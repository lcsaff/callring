# AI 沟通整改实施与验收证据

2026-10-19，任务 `ai-v2-implementation`，epoch 7。本文是实施交接，最终验收与独立审查由主负责人登记。

## 已验证的结果

正式设备 `192.168.10.149:5555`、API 36、1080×2400，包名 `com.greatwall.callring`，版本 0.2/code 2。只使用同签名 `install -r -t`；未卸载、清数据、导出密钥或重复新增批次。签名证书 SHA-256 为 `5a901cad57f5590483f362e4752ca8f4a56197340b71d2632ded0195bf8a24b4`。

真实模型为 `qwen3.8-flash / OPENAI / QWEN_FAST`，使用正式应用已有凭据。原兼容设置 AUTO 实际解析为 STANDARD，验收前通过原设置 API 单独改为 QWEN_FAST；地址、密钥和原预算 context=1048576/output=16384 均保留。新安装缺省预算仍为 49152/4096。

|实际输入|结果|HTTP 请求|源查询|工作量|已核验写入|请求字节合计|HTTP 耗时合计|
|---|---|---:|---:|---:|---:|---:|---:|
|你好|COMPLETED|1|0|0|0|4023|2384 ms|
|添加 10 个随机号码档案|COMPLETED|4|40|52|10|38719|15518 ms|
|修改深色模式|COMPLETED|5|3|7|1|48418|7270 ms|

耗时合计为各 HTTP exchange 的计量之和，不是整段 UI 验收耗时。所有实际请求 HTTP 200，每次顶层 `enable_thinking=false`、`preserve_thinking=false`。provider 返回的 usage/cache 数值逐请求保留；未返回的 cache-write/reasoning 项为 null。未使用模拟 client、预制 tool_calls 或测试生成的号码。

普通聊天的 coreStateLoads/completeCaptures/sourceQueries/classifications/runtimeProbes 增量全部为 0。新增任务前后源集合差集恰 10，合法且唯一、姓名有合成标识，原档案全部不变；十条 COMMITTED/VERIFIED 回执由实际源读回确认。外观从原业务 API 设置的 LIGHT，经真实模型与原 UiEdits 保存为 DARK。force-stop 后十条记录及 DARK 保留，对话历史不跨进程保存。最新 APK 的只读检查另确认筛选列表和编辑页显示本次合成资料，未点击保存或新增。

真实付费执行的精确提交/APK 摘要见 [device/build.json](ai-rectification-evidence/device/build.json)，提交为 `346e896`。其后补齐导出/播放核验与验收入口；最新 APK 只运行持久化与 UI 只读检查，摘要见 [persistence-build.json](ai-rectification-evidence/device/persistence-build.json)，没有再执行新增原文。

## 自动检查

`python -u tools/verify_backend.py` 完整通过：backend **182**、app **159**、UI **207**，失败/错误/跳过均为 0。Debug、Release、AndroidTest、backendQa 构建和 app/UI Lint 通过。边界策略另外 2 个测试通过，`git diff --check` 通过。APK 摘要及大小见 [local-verification.json](ai-rectification-evidence/local-verification.json)。backendQa 仅构建，此轮未向正式库运行旧全套测试。

原 72 项业务 API/校验/保存调用机器覆盖见 [operation-coverage.json](ai-rectification-evidence/operation-coverage.json)。系统、媒体边界使用 Robolectric，不能代替真机权限或媒体验收。导出另用实际 provider 重新打开并逐字节核对；播放/停止另核对实际 runtime 的 callId/state，错误目标不通过。QUEUED/LAUNCHED 均保留 PENDING，不冒称完成。

## A1–A10 对照

|验收项|实施证据与当前结论|
|---|---|
|A1 设置|通过自动测试：字段 patch、Keep/Replace/Clear、旧预算/AUTO、同字段冲突、双向复制和目标变化取消；真机保留原预算与密钥。|
|A2 对话入口|通过 UI 自动测试：四导航、窄/宽屏及动态状态；真机对话、键盘与触摸发送通过。|
|A3 全来源|D01–D21 均有生产源适配；SQL分页/全文/聚合、SAF撤权/701文件、归档大记录/重开/中断/坏payload测试通过。未在真实进程写中强杀；只声明线程中断与关闭重开证据。|
|A4 原业务一致|72 项生产 API 集成及范围并发测试通过；真实 AI 写入、重启读回、筛选列表及详情刷新通过。其余真实系统动作见 A6。|
|A5 按需与容量|真实闲聊一次请求/零业务读取；目录遍历、源完整性、码点全文、活动协议配对、100轮/2MiB历史及请求容量测试通过。|
|A6 系统前台边界|一次性票据、拒绝/取消/过期/宿主替换/迟到重复/目标变更的自动测试通过。真实系统授权等动作尚未逐项验收，见下一表；不能据两条模型任务宣布全通过。|
|A7 UI/Markdown|批准的 multiplatform-markdown-renderer 0.39.2 core+m3，JDK17 编译/D8/Lint、Markdown/字号/触控/IME自动测试通过；真实浅深色及键盘截图通过。0.40.2 不兼容证据见 dependencies.md。|
|A8 执行可靠性|10单批/8+2/多批、32/33、换ID去重/同ID改参、提交未知/读回失败不重放、超额写入零执行、依赖、有限修复、前台门禁及范围冲突测试通过；真实10条闭合。|
|A9 两条原文|实际模型、生产图、Activity、原凭据与真实存储结果通过，唯一新增批次持久防重；付费执行证据有下述诊断缺项，保留原始限制交审查。|
|A10 轻量框架取舍|保留轻量循环，真实两条任务已完成；没有引入 Koog，没有编造框架性能对比或旧 APK 大小基线。|

真实系统动作分项：

|动作|真机状态|自动证据|
|---|---|---|
|真实 Activity 宿主、输入、发送、LIGHT→DARK|通过|正式设备原文任务/截图|
|权限授予/拒绝与应用设置返回|未测|票据与权威状态回读自动测试通过|
|SAF 选择目录、导入录音/铃声、导出文件|未测|真实 provider/原业务 API 与读回自动测试通过|
|桌面 pin、小组件、悬浮窗与来电预览|未测|真实命令接口与前台票据自动测试；LAUNCHED 不算已完成|
|真实设备音频播放/停止|未测|Robolectric MediaPlayer 实际 target/state 核验通过|
|凭据编辑、双向复制和付费连接测试确认|未测|patch/复制/确认目标/安全投影自动测试通过；未在真机改密钥|

## V1–V12 与审查入口

|架构检查|证据|
|---|---|
|V1|AiSettingsService、CompatibilityCopy、UI 设置回归|
|V2|UI Conversation 测试及 device/*keyboard.png、light-before.png、dark-after.png|
|V3|真实 idleCounterDelta 全0、CapabilityCatalogTest|
|V4|SqliteScopedRead、ScopedAggregate、RecordingScopedQuery、ArchiveProjectionMigration、ScopedDirectory 测试|
|V5|operation-coverage.json、ToolBusinessIntegrationTest、ReceiptVerifierTest；真机 synthetic-ui.json|
|V6|ToolBatchEngineTest、StepLedgerTest、TaskPlannerTest、ConversationRepairTest|
|V7|schema 安全字段反馈、有限修复2/步4/轮、冲突/取消/未知提交不自动修复测试|
|V8|ConversationProtocolTest 的 null/旧式/截断/调用数/HTTP分类矩阵|
|V9|FAST多轮自动回归和真实10次HTTP的两false标志|
|V10|ScopedRevision、ScopePlanner、共享保存原子merge与后台发布测试|
|V11|ForegroundActionService/ConfirmationTarget/secret哨兵测试；其余真实授权未测|
|V12|真实请求/响应字节、usage/cache/耗时及本地构建边界；历史/容量测试通过。原付费运行未导出 schema/history 数量和逐步 receipt ID，见下文。|

付费运行记录了实际聚合核验数，未导出每步 receipt ID、schema 数量与上下文消息数；这几个历史字段明确保留 null/缺失，没有从推测补造。最新实现已补每次实际发送字节中的 schema/context 数量计量及逐步无业务值回执导出，单测验证；本次不为补历史诊断重新新增十条资料。业务实值只在设备内部重启 journal 和受控合成 UI 中，JSON 诊断仅保留步骤序号/谓词。独立审查可运行 `--persistence-only`，无需付费、不会新增资料。

配置、前台窗口、发送点击的早期失败记录均保留 `config-block*`、`window-block*`、`send-block*`，发生时会话 IDLE、批次未开始。唯一新增任务成功后，持久 journal 阻止再次启动。敏感窗口覆盖的截图不交付；重启截图仅复制本应用 Activity surface。

建议独立审查优先核对：归档派生索引与进度边界、StepLedger 的别名/冲突/数量保护、原业务范围发布、所有外部动作的 PENDING 状态，以及付费运行诊断缺项是否满足最终 A9/V12 标准。本文不代替独立审查结论。

## 证据索引

- [真实模型闭合指标](ai-rectification-evidence/device/real-model.json)、[实际 runner 结果](ai-rectification-evidence/device/real-model.log)
- [重启持久化](ai-rectification-evidence/device/persistence.json)、[只读 UI 检查](ai-rectification-evidence/device/synthetic-ui.json)
- [合成档案筛选列表](ai-rectification-evidence/device/synthetic-list.png)、[合成档案详情](ai-rectification-evidence/device/synthetic-editor.png)
- [实施历史](ai-rectification-evidence/implementation-progress.md)、[依赖裁决](ai-rectification-evidence/dependencies.md)

## Epoch 8 独立审查整改

PR001–PR009 的实现、逐项验证及限制见 [整改证据](ai-rectification-evidence/review-corrections/README.md)。原 epoch 7 真机运行和 APK 绑定不变；本轮只做不发模型请求、不写业务值的真机界面及持久化复验。

PR009 的原始 step ID、schema/history 数量和完整阶段时序无法可靠恢复，见 [历史诊断与最小待决项](ai-rectification-evidence/review-corrections/historical-diagnostics.md)；新增采集的合成验证不能代替真实 provider 证据。A6 尚未实测项目保持原限制，等待负责人组织新的独立复验和验收。

[模型兼容边界](ai-rectification-evidence/review-corrections/compatibility.md) 说明 FAST 更换模型的恢复提示及 STANDARD/DeepSeek 思考回放限制。
