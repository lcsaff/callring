# AI 思考模式（Qwen / DeepSeek / MiMo × OpenAI / Anthropic）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development 或 superpowers:executing-plans 按步执行；每步是一个可独立验证、可单独提交的单元。
> **Goal:** 在现有 AI 设置与请求链路上增加唯一的“思考模式”开关，按厂商×协议矩阵显式下发关闭/开启参数，开启态按协议回传思考内容，并把旧配置与录音侧安全迁移到策略快照。
> **Architecture:** 新建纯数据的 `ThinkingPolicy`/`RequestPolicySnapshot` 作为唯一真相，后端派生、客户端只消费；`AiConnection.thinkingMode` 持久化；录音侧通过端口签名改造接入（blocked）。
> **Tech Stack:** Kotlin 2.3 / Gradle 8.13 / JUnit4 + Robolectric + Compose 测试；不新增依赖。

状态：待实施（计划作者 plan-thinking，task-5，2026-10-19）。依据 spec B 第 2 版与 D 的证据；本文不重复设计理由，只给可执行步骤。

## 1. 范围与依赖

- 范围：能力矩阵与解析（5 个厂商×协议组合 + 未识别/不支持）、`thinkingMode` 数据模型与迁移、请求快照、两款客户端的参数/回传/认证头、投影/冲突键/会话指纹/工具 schema、录音侧端口（blocked）、UI 单开关（收口 A）、矩阵单测与真实请求模板。
- 不做：effort/预算档位、供应商选择器、ASR 注入思考参数、更新发布（P3）、主界面布局（P1）。真实请求**不在本计划任务内执行**。
- 依赖：[并行执行计划](2026-10-19-parallel-execution-plan.md) 的 Wave 1/2/3 与 G2/G3、M1/M3/M4 标记；spec [B](2026-10-19-ai-request-policy-design.md)、[A](2026-10-19-compact-main-ui-design.md) §7、[D](2026-10-19-external-facts-verification.md) §4/§5。
- 与 P1/P3 的并行关系：S0–S6 与 P1 的布局/档案/分页/非 AI 设置页、P3 的版本来源/更新契约/更新页**可并行**（文件不交叉）；S7 与 P1 串行（M1）；S8 与 P3 串行（M3）；S9 等 G2；S10/S11 是 V1/V3 验证门。
- 硬约束与标记：S2 纯增量字段先合入作为三线编译基线，共享文件（§5）Wave 1 冻结；标记沿用并行计划——`[并行]`=可并行、`[收口:Mx]`=需收口、`[gated:Gx]`=blocked、`[串行:Vx]`=验证类串行。

## 2. 前置事实（2026-10-19 工作区核对；`文件:行` 只用于定位，可能漂移）

- 全仓库 grep 无 `thinkingMode`/`ThinkingPolicy`/`RequestPolicySnapshot` 符号，也无 `ConversationMessage.reasoning` 字段；本计划是纯新增。唯一现有思考参数在 `CompatibleConversationClient.kt:31-32`，且仅 `QWEN_FAST`。
- 认证头与端点：OpenAI 发 `Authorization: Bearer`（`CompatibleConversationClient.kt:153`、`CompatibleAiClient.kt:215`）；Anthropic 发 `x-api-key` + `anthropic-version`（同文件 `:154`、`:217-218`），而 MiMo 文档要求 `api-key` 或 Bearer、**未适配**；端点是 `connection.endpoint(path)` 拼固定相对路径（`chat/completions`、`messages`），代码里没有 DeepSeek Anthropic 官方端点事实——两项都必须走 G3。
- 关闭态兜底：OpenAI 回复非空 `reasoning_content` → `ai.provider_mode_mismatch`（`CompatibleConversationClient.kt:91`）；Anthropic 未知块 → `ai.protocol_shape`（`:117`），开启态需放行 `thinking`/`redacted_thinking`。
- 回传载体：`ConversationMessage` 只有 text/toolCalls/receipts/blocks（`ConversationApi.kt:10`）；assistant 历史仅 `blocks` 保留 Anthropic 块（`ConversationService.kt:158`），`ContextAssembler.kt:18` 的 UI 投影会清空 blocks。开启态需扩字段并在 UI 投影置空。
- 持久化：连接 JSON 四个字段（`AndroidRecordingStore.kt:202-203`）；`conversation` 写 `version 1`（`:206`）、读强制 `version == 1`（`:209`）。`RecordingSettings.validate()` 对转录仅断言协议 OPENAI（`RecordingApi.kt:38`），未忽略 thinking 字段。
- 旧解析调用点：`RecordingApi.kt:37`、`AiSettingsService.kt:20-21,79`、`ConversationService.kt:70,108`、`CompatibleConversationClient.kt:31`；`AiConnectionView.resolvedProfile` 仍被 `AiRoutes.kt:25` 与 Debug 设备测试引用，只能分阶段删除。
- 贯穿层位置：投影 `SafeProjection.kt:59,61-62`；冲突键 `BusinessScopes.kt:24-25`；指纹与 `matches` `ConversationService.kt:70,215`；工具 schema `ToolRegistry.kt:25`、`ToolExecutor.kt:148,166,204-205`、`OperationScopePlanner.kt:40,80`。
- 录音侧：`AiClient` 三个方法只收 `AiConnection`（`RecordingPorts.kt:17-23`）；批次在 `RecordingWorkflow.kt:73` 取 settings 后多次使用；装饰器 `AndroidAudioTranscriber.kt:14-19`；装配 `AndroidRecordingRuntime.kt:31-44`。
- 真实请求 harness 已存在：`app/src/debug/kotlin/com/greatwall/callring/RealAiConversationDeviceTest.kt`（`:36-45,65,140` 引用 compatibility/resolvedProfile/requestFlags，必须同步改）。
- **代码事实冲突（记录，不改 spec）**：spec B §12 与 D §5 列出的 `CompatibleConversationClientTest` 在仓库不存在；实际对应 `ConversationProtocolTest.kt`、`ConversationCompatibilityTest.kt`、`ProviderProfileTest.kt`。另外 `resolvedProfile`/`ProviderProfile` 的展示仍需保留到 M1 收口，spec §9 的“展示废止”不能在本 Wave 提前执行。

## 3. 阶段与步骤总表

| 步骤 | 内容 | 主要文件 | 标记 | 门 |
| --- | --- | --- | --- | --- |
| S0 | Anthropic 认证头/端点核对 | 无代码（结论回写 S1/S5/S6） | `[gated:G3]` | G3 |
| S1 | 能力矩阵与解析 | 新增 `ThinkingPolicy.kt` + 两个新测试 | `[并行]` | — |
| S2 | 数据模型与迁移（先合入基线） | `RecordingApi`、`AiSettingsApi`、`AndroidRecordingStore`、`AiSettingsService` | `[并行]` | — |
| S3 | 策略快照与回传载体 | `ThinkingPolicy.kt`、`ConversationPorts/ConversationService/ContextAssembler/ConversationApi` | `[并行]` | S1/S2 |
| S4 | 贯穿层 | `SafeProjection`、`BusinessScopes`、`ToolRegistry/ToolExecutor/OperationScopePlanner` | `[并行]` | S2 |
| S5 | 聊天客户端 | `CompatibleConversationClient.kt` + app 测试 | `[并行]` | S0/S3 |
| S6 | 分析/转写客户端 | `CompatibleAiClient.kt` + app 测试 | `[并行]` | S0/S3 |
| S7 | UI 开关与文案收口 | `AiSettingsScreen/ViewModel`、`RecordingScreens/ViewModels`、`AiRoutes` | `[收口:M1]` | P1 |
| S8 | 图装配 | `BackendGraph.kt`（仅签名变化时） | `[收口:M3]` | P3 |
| S9 | 录音侧端口与批次快照 | `RecordingPorts/RecordingWorkflow/AndroidAudioTranscriber/AndroidRecordingRuntime` | `[gated:G2]` | G2 |
| S10 | 矩阵测试与全量验收 | 测试文件 + 命令 | `[串行:V1]` | 全部 |
| S11 | 真实请求留证 | 执行记录（本任务不执行） | `[串行:V3]` | 密钥/发布前 |

## 4. 步骤详述

### S0 `[gated:G3]` Anthropic 认证头与 DeepSeek 端点核对
- 动作：核对 MiMo Anthropic 头（`api-key` 还是 Bearer）、`https://api.xiaomimimo.com/anthropic/v1/messages` 可用性、DeepSeek Anthropic 兼容端点与认证头；给“通过/不通过 + 依据 URL + 日期”，不通过则在 S1 删除该组合并给“改用 OpenAI 协议”提示。
- 涉及文件：无代码改动；结论写入实施记录/PR 描述（spec 不改）。
- 验收：厂商文档原文可引用；两项结论明确；不通过组合时 S1 返回 `ai.thinking_unsupported` 且 UI 有替代协议提示。
- 完成定义 / 并行：G3 关闭或明确 blocked，影响面回写 S1/S5/S6 / `[gated:G3]`，不阻塞其余组合。

### S1 `[并行]` 能力矩阵与解析（`backend/.../application/api/ThinkingPolicy.kt`）
- 动作：新增 `ThinkingMode { ENABLED, DISABLED }`、`ThinkingSupport { AVAILABLE, CUSTOM_GATEWAY, UNSUPPORTED, UNRECOGNIZED }` 与“厂商/族/协议/官方直连/参数名+值+位置”矩阵；解析顺序固定为协议→模型族（`qwen*`/`deepseek*`/`mimo*`，表内精确模型优先）→官方 host 白名单（https、无 userinfo、端口 -1/443）→三态。5 个支持组合：Qwen×OpenAI、DeepSeek×OpenAI、DeepSeek×Anthropic、MiMo×OpenAI、MiMo×Anthropic；Qwen×Anthropic 明确不支持；未识别模型给 `ai.model_unrecognized`。
- 涉及文件：新建 `backend/src/main/kotlin/com/greatwall/callring/application/api/ThinkingPolicy.kt`；新建测试 `backend/src/test/kotlin/com/greatwall/callring/ThinkingPolicyMatrixTest.kt`、`ThinkingPolicyResolverTest.kt`；`ConversationCompatibility.kt` 仅加“语义废止/迁移读取”注释，不删枚举。
- 验收：`.\gradlew.bat :backend:test --tests "com.greatwall.callring.ThinkingPolicyMatrixTest" --tests "com.greatwall.callring.ThinkingPolicyResolverTest"` 全绿；断言每组合的字段名/位置/值、关闭态 `preserve_thinking` 规则、伪造子域与代理→自定义网关、未识别→禁用、Qwen×Anthropic→禁用并提示改用 OpenAI 协议。
- 完成定义 / 并行：矩阵与 spec §3/§5 逐格一致，纯函数、无网络、无密钥 / `[并行]`，只改 backend 新文件与测试。

### S2 `[并行，先合入基线]` 数据模型与迁移
- 动作：`AiConnection` 增 `thinkingMode: ThinkingMode = DISABLED`；`RecordingSettings.validate()` 补“转录连接忽略 thinkingMode”的语义（转录不因 thinking 失败、也不注入）；`AiConnectionView` 增 `thinkingMode` 与派生的 `thinkingSupport`/`thinkingNotice`；`ConnectionPatch` 增 `thinkingMode`，`AiSettingsPatch` 只对 analysis 生效；`AiSettingsService` 的 view/patch/copy 携带字段（复制到分析时 thinkingMode 重置 DISABLED，不静默开启）；`AndroidRecordingStore` 连接 JSON 增字段、`conversation` 升 v2 且不再写 compatibility，读取接受 v1/v2，旧值 `QWEN_FAST`/`AUTO`/`STANDARD` 一律映射 `DISABLED`，缺字段默认 `DISABLED`，url/model/key 不丢。
- 涉及文件：`backend/.../api/RecordingApi.kt`、`AiSettingsApi.kt`（纯增量字段，additive）；`app/.../platform/ai/AiSettingsService.kt`、`AndroidRecordingStore.kt`；测试 `app/src/test/.../AndroidRecordingStoreTest.kt`、`AiSettingsServiceTest.kt`。
- 验收：`.\gradlew.bat :app:testDebugUnitTest --tests "com.greatwall.callring.platform.ai.AndroidRecordingStoreTest" --tests "com.greatwall.callring.platform.ai.AiSettingsServiceTest"` 全绿；断言 v1 JSON（含/不含 compatibility）读取为 DISABLED、ENABLED 往返、旧数据 url/model/key 不变、转录 thinking 不参与校验、v2 写出不含 compatibility。
- 完成定义 / 并行：纯增量、带默认值、其余模块可编译；旧数据首次读取补默认、下次保存写 v2，不建表不清数据 / `[并行]`，作为三线编译基线单独小提交先合入；不改 UI 冻结文件。

### S3 `[并行]` 策略快照与思考回传载体
- 动作：在 `ThinkingPolicy.kt` 定义不可变 `RequestPolicySnapshot`（开关、厂商/族/协议/官方直连、参数与位置摘要、`aiConfig` 修订号、凭据受控引用 slot+revision、预算、脱敏版本、判定/原因）；`ConversationRequest.compatibility` 改为 `policy`；聊天 turn 开始取一次快照并在多轮工具调用中复用，不重读 settings；连接测试用已保存配置的快照；`ConversationMessage` 增 `reasoning`（wire 保留、UI 投影置空），`ConversationReply.Final/ToolBatch` 增 `reasoning`，`ContextAssembler` 透传并在 `ui` 投影与 `finish` 摘要丢弃；`matches`/`connectionFingerprint` 纳入 `thinkingMode`，`sendTo` 目标对比也纳入。
- 涉及文件：`backend/.../api/ThinkingPolicy.kt`、`backend/.../ports/ConversationPorts.kt`、`backend/.../api/ConversationApi.kt`、`backend/.../conversation/ConversationService.kt`、`ContextAssembler.kt`；测试 `ConversationServiceTest.kt`、`ContextAssemblerTest.kt`。
- 验收：`.\gradlew.bat :backend:test --tests "com.greatwall.callring.ConversationServiceTest" --tests "com.greatwall.callring.ContextAssemblerTest"` 全绿；断言 turn 中途改配置不影响本 turn 请求、指纹变化触发 `ai.connection_changed`、reasoning 不进 UI 消息与摘要且不丢 wire。
- 完成定义 / 并行：同一 turn 只读一次快照；快照不含明文密钥；思考内容计入预算口径 / `[并行]`，依赖 S1/S2。

### S4 `[并行]` 贯穿层：投影、冲突键、指纹、工具 schema
- 动作：`SafeProjection` 增 `thinkingMode`/`thinkingSupport`/`thinkingNotice`；`BusinessScopes` 增 `ai:<slot>:thinkingMode`（M1 前暂留 `ai:compatibility`）；`ConversationService` 的 `matches`/指纹用新字段；`ToolRegistry.kt:25` 的 `ai` shape 增 `?thinkingMode`、`ToolExecutor.kt:148,166,203-205` 的 AI 补丁解析、`OperationScopePlanner.kt:40,80` 增加冲突键与复制计划范围（M1 前保留 `?compatibility`）。
- 涉及文件：`backend/.../SafeProjection.kt`、`BusinessScopes.kt`、`conversation/ToolRegistry.kt`、`ToolExecutor.kt`、`OperationScopePlanner.kt`；测试 `ToolRegistryTest.kt`、`ToolOperationCoverageTest.kt`、`OperationScopePlannerTest.kt`、`DraftMergeTest.kt`。
- 验收：`.\gradlew.bat :backend:test --tests "com.greatwall.callring.Tool*" --tests "com.greatwall.callring.OperationScopePlannerTest" --tests "com.greatwall.callring.DraftMergeTest"` 全绿；断言 schema 有 `?thinkingMode` 枚举、ai 补丁可写、冲突键包含新键、指纹随开关变化。
- 完成定义 / 并行：投影/冲突/工具三层都可见新字段；旧 compatibility 仅保留到 M1 / `[并行]`，依赖 S2。

### S5 `[并行]` 聊天客户端：参数、认证头、回传、兜底
- 动作：`encode` 按快照下发——Qwen×OpenAI 顶层 `enable_thinking`（关闭态同时 `preserve_thinking=false`，开启态不发 preserve）；DeepSeek/MiMo×OpenAI 顶层 `thinking:{type:enabled|disabled}`；Anthropic 协议 body 顶层 `thinking` 对象；两态都不发 `temperature/top_p/presence_penalty/frequency_penalty`；Anthropic 认证头按厂商适配（S0 结论）；`decode` 开启态保留 `reasoning_content` 并回传 assistant 历史（DeepSeek 服务端忽略、仍统一回传），Anthropic 按原顺序保留 `thinking`/`redacted_thinking` 块，关闭态保留非空 reasoning 的 `ai.provider_mode_mismatch`；400/未知字段映射 `ai.thinking_parameter_rejected`（不删参、不换模型/协议重试）；未支持组合预检 `ai.thinking_unsupported`；`ExchangeMetrics` 增脱敏 `policy` 诊断，两个既有 requestFlags 布尔键不变。
- 涉及文件：`app/.../platform/ai/CompatibleConversationClient.kt`、`backend/.../api/ConversationCompatibility.kt`（诊断结构）；测试 `app/src/test/.../ConversationProtocolTest.kt`、`ConversationCompatibilityTest.kt`、`ProviderProfileTest.kt`（改造）。
- 验收：`.\gradlew.bat :app:testDebugUnitTest --tests "com.greatwall.callring.platform.ai.Conversation*" --tests "com.greatwall.callring.platform.ai.ProviderProfileTest"` 全绿；断言五组合字段位置/值、认证头、开启态 reasoning 原样回传且不泄漏到错误、关闭态仍报 mismatch、400 不重试。
- 完成定义 / 并行：客户端不再自行判断厂商（只消费快照），错误路径可被 UI 文案识别 / `[并行]`，依赖 S0/S3。

### S6 `[并行]` 分析/转写客户端：注入范围与认证头
- 动作：`analyzePart`/分段/合并/分析连接测试按快照注入与聊天相同的协议字段；`transcribe` 永不注入（请求体保持现状，仅 `model` 与音频 multipart）；Anthropic 分析响应允许 `thinking` 块但只解析 `text`，思考内容不入库、`usage.reasoningOutput` 计入输出预算与诊断；认证头与 S5 共用同一适配逻辑；分析连接测试使用已保存配置。
- 涉及文件：`app/.../platform/ai/CompatibleAiClient.kt`；测试 `app/src/test/.../CompatibleAiClientTest.kt`。
- 验收：`.\gradlew.bat :app:testDebugUnitTest --tests "com.greatwall.callring.platform.ai.CompatibleAiClientTest"` 全绿；断言分析请求含开关字段、分段与合并一致、转写请求体与现状逐字节一致、Anthropic thinking 块不进入 `CallAnalysis`。
- 完成定义 / 并行：ASR 路径零思考参数（矩阵 #12 的代码级保证）；分析侧关闭态行为不变 / `[并行]`，依赖 S0/S3。

### S7 `[收口:M1]` UI 开关与错误码（与 A 串行）
- 动作：按 A §7.6 在“内容分析与沟通”组只放一个“思考模式”开关 + 一行状态说明（可用 / 自定义网关：参数可能不被执行 / 当前模型不支持切换），无供应商/合同/参数文案，默认关；`AiSettingsViewModel` 改单字段即时 patch（携 base 修订号、busy 禁用、冲突刷新并提示“已更新，请重新修改”），删除 save/dirty 草稿与 `testDraft` 契约（连接测试统一走 `testSlot`）；`aiErrorText`/`recordingMessage` 接 `ai.thinking_unsupported`、`ai.thinking_parameter_rejected`、`ai.model_unrecognized` 并改写 `provider_mode_mismatch` 文案；删除“高级兼容”块与 `compatibility` 运行时字段（视图/补丁/请求/工具 schema/投影/冲突键/服务映射），同步 `AiRoutes` 参数与 Debug 设备测试。
- 涉及文件：`ui/.../feature/ai/AiSettingsScreen.kt`、`AiSettingsViewModel.kt`、`ui/.../feature/recording/RecordingScreens.kt`、`RecordingViewModels.kt`、`ui/.../app/AiRoutes.kt`、`ui/src/sharedTest/.../UiFakeApi.kt`（M4）；改造 `AiCompatibilityUiTest`/`AiCompatibilityComposeTest`，新增思考开关/单字段 patch 测试（放 `ui/src/test/.../feature/ai/`，与 P1 S13 的 AI VM 测试目录一致）。
- 验收：`.\gradlew.bat :ui:testDebugUnitTest` 全绿；断言仅一个开关、无供应商/参数文案、三种禁用原因各一行、切换即 patch（其他字段 `Keep`）、冲突不覆盖、错误码命中文案。
- 完成定义 / 并行：A 的布局与 P2 语义同提交合入，旧兼容块与草稿保存彻底消失；本步是**收口步骤，与 A 串行**（`[收口:M1]`），Wave 1 不得提交，由 M1 owner 一次合并。

### S8 `[收口:M3]` BackendGraph 装配
- 动作：仅当 `ConversationService` 构造参数/快照来源或录音侧端口装配变化时，更新 `BackendGraph.kt` 的 `conversationDelegate` 与 `AndroidRecordingRuntime` 装配；与 P3 的更新 API 装配合并为 M3；Wave 1 不改该文件。
- 涉及文件：`app/.../bootstrap/BackendGraph.kt`。
- 验收：`.\gradlew.bat :app:assembleDebug` 成功；真实对话与连接测试仍走真实客户端。
- 完成定义 / 并行：装配点唯一、无重复构造；P3 合并后一次全量验证通过 / `[收口:M3]`，与 P3 串行。

### S9 `[gated:G2]` 录音侧端口、批次快照与装饰器
- 动作：`AiClient` 的 `analyze/test` 接收策略快照（`transcribe` 不变，转写不读开关）；`RecordingWorkflow.process` 在批次开头取一次快照并传给转写/分析/后续调用，策略禁用时不发请求并落固定错误（`ai.thinking_unsupported`）；`AndroidAudioTranscriber` 透传新签名；`AndroidRecordingRuntime` 更新装配；按 spec §13 记录前置提交占位。
- 涉及文件：`backend/.../RecordingPorts.kt`、`backend/.../RecordingWorkflow.kt`、`app/.../platform/ai/AndroidAudioTranscriber.kt`、`app/.../platform/ai/AndroidRecordingRuntime.kt`；测试 `backend/src/test/.../RecordingWorkflowTest.kt`。
- 验收：解封后 `.\gradlew.bat :backend:test --tests "com.greatwall.callring.RecordingWorkflowTest"` 全绿；断言批次内配置变更不改变本次请求、禁用策略不产生网络调用且错误固定、转写路径无思考参数。
- 完成定义 / 并行：前置端口提交已合入并以正式名称/契约封存；未解封只做签名设计评审，不得标记完成 / `[gated:G2]`，解封条件见 §8。

### S10 `[串行:V1]` 矩阵测试补齐与全量验收
- 动作：新增 `ThinkingPolicyMatrixTest`（矩阵中的参数形态）与 `ThinkingPolicyResolverTest`（识别/白名单/三态）；更新受影响的既有测试（`ConversationProtocolTest`、`ConversationCompatibilityTest`、`ProviderProfileTest`、`CompatibleAiClientTest`、`AiSettingsServiceTest`、`AndroidRecordingStoreTest`、`ConversationServiceTest`、`ContextAssemblerTest`、`ToolRegistryTest`、`ToolOperationCoverageTest`、`OperationScopePlannerTest`、`DraftMergeTest` 与 ui AI 测试）；按 §6 逐项映射。
- 涉及文件：上述测试文件（新增两个 backend 测试类）。
- 验收：运行 §9 全量命令全部通过；矩阵 12 项逐项有断言或明确归入 S7/S9 的 UI/端口用例。
- 完成定义 / 并行：无跳过、无 TODO 断言；失败项记录而不是注释掉 / `[串行:V1]`，所有实现步骤合入后执行。

### S11 `[串行:V3]` 真实请求留证（本任务不执行）
- 动作：按 §7 模板执行 Qwen 关闭态聊天 + 连接测试；MiMo/DeepSeek 开启态各一条（要标“已实测”需每家各一条）；用 `RealAiConversationDeviceTest` 或等价设备 harness，核对实际请求字段、状态码、usage 与 reasoning 回传；不在本任务内执行、不写死密钥。
- 涉及文件：仅执行记录（证据路径 + 脱敏请求摘要）。
- 验收：每条记录含日期、提交 SHA、连接（协议/模型/host）、开关、关键字段、HTTP 状态、`finish_reason`/usage、回传检查、结论；未执行的家保持“已核实（文档+日期）、未实测”。
- 完成定义 / 并行：发布前 V3 关闭，且真实请求前 V1 必须全绿 / `[串行:V3]`，不与 V1/V2 并行。

## 5. 共享文件收口清单（Wave 1 一律冻结，不得提交）

| 文件 | Wave 1 owner | 收口 | P2 在本步骤要做的事 |
| --- | --- | --- | --- |
| `ui/.../feature/ai/AiSettingsScreen.kt` | 无（冻结） | M1（P1 改结构） | 提供开关位置契约、状态说明与错误码文案，不单独改 |
| `ui/.../feature/ai/AiSettingsViewModel.kt` | 无（冻结） | M1（并行计划指定 P2 owner 改单字段 patch） | 单字段 patch、冲突刷新；双快照由 P1 提供 |
| `ui/.../feature/recording/RecordingScreens.kt`、`RecordingViewModels.kt` | 无（冻结） | M1 | 仅 `recordingMessage` 新错误码；自动分析迁移归 P1 |
| `ui/src/sharedTest/.../UiFakeApi.kt` | 无（冻结） | M4（三方合并） | 不提交；测试用各自临时 fake |
| `app/.../bootstrap/BackendGraph.kt` | 无（冻结） | M3（P2+P3） | 仅在签名变化时改装配，与 P3 串行 |
| `ui/.../app/AiRoutes.kt` | 无（随 M1） | M1 | 回调签名与思考开关接线一并在 M1 改 |

## 6. 矩阵测试清单（spec §11）

1–2. 参数形态与默认态：三家×两协议×两态 → S1 `ThinkingPolicyMatrixTest` + S5/S6 请求体断言；新配置默认 DISABLED 且首次请求即下发关闭参数 → S2 store 默认 + S5。
3–4. 未知模型与不支持组合：未识别 → S1 resolver 禁用 + `ai.model_unrecognized`；Qwen×Anthropic（及 G3 不通过时的 Anthropic 组合）→ S1 禁用 + “改用 OpenAI 协议”。
5–6. 开启态回传与关闭态兜底：MiMo OpenAI/Anthropic、DeepSeek 原样回传 → S5 `decode` + `ConversationService` 历史断言；关闭态仍返回思考内容 → S5 `ai.provider_mode_mismatch`，不重试（改造 `ProviderProfileTest`）。
7–8. 参数被拒与即时生效：400 → S5 `ai.thinking_parameter_rejected`，不删参/不换模型重试；开关改动立即 patch、busy 禁用、冲突不覆盖 → S7 ui VM 测试。
9–10. 快照稳定与迁移：turn 中改配置不影响本次请求 → S3 `ConversationServiceTest`；v1/v2 读取、三种旧值→DISABLED、不丢 url/model/key → S2 `AndroidRecordingStoreTest`。
11–12. 脱敏预算与 ASR：S3/S5 诊断不含密钥且输出预算含思考内容；转写请求体逐字节回归 → S6。

## 7. 真实请求记录模板（执行者填写；本任务不执行）

| # | 日期/提交 | 连接（协议/模型/host） | 开关 | 关键请求字段（脱敏） | HTTP/usage | 回传检查 | 结论 | 证据路径 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 待填 | OpenAI / qwen3.8-flash / dashscope 官方 | 关 | `enable_thinking=false`、`preserve_thinking=false`、无 temperature/top_p | 待填 | 不适用 | 待填 | 待填 |
| 2 | 待填 | 同 #1（连接测试） | 关 | 同上（分析/测试请求） | 待填 | 不适用；不得发送通话内容 | 待填 | 待填 |
| 3 | 待填 | MiMo 或 DeepSeek / 对应模型 | 开 | `thinking={type:enabled}`（各协议各自位置）、无 temperature/top_p | 待填 | `reasoning_content`/thinking 块按原顺序回传 | 待填 | 待填 |

约束：一次发布最多两次真实请求（Qwen 关闭态聊天 + 连接测试）；MiMo/DeepSeek 的开启态各需一条才能标“已实测”，未实测的家必须保持“未实测”。任何记录不得包含密钥、完整请求 URL 或通话内容。

## 8. blocked 项与解封条件

- **G2 录音端口前置提交（S9）**：blocked。占位：`前置提交 SHA：待填`、`前置提交正式端口名称/契约：待填`、`最终封存版本：待填`。解封条件：前置提交合入集成分支并提供正式名称、契约、SHA、测试结果与封存版本；未解封前 S9 只做设计评审，不得标记完成，其余步骤照常验收。
- **G3 Anthropic 核对（S0）**：不通过则从矩阵移除 MiMo/DeepSeek 对应 Anthropic 组合，UI 提示改用 OpenAI 协议；其余组合不受影响。
- **M3 BackendGraph**：与 P3 串行；P3 未合入前不得改该文件。
- **V3 真实请求**：需付费密钥、真实网络与发布前窗口；未执行不得写“已实测”。

## 9. 验收命令

```powershell
.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug
```

分步命令见各步骤；`backend` 是 `java-library`（测试任务 `:backend:test`，`backend/build.gradle:1`），`ui`/`app` 为 Android 模块（`:ui:testDebugUnitTest`/`:app:testDebugUnitTest`）。设备仪器测试（`:ui:connectedDebugAndroidTest`）、真实请求与安装覆盖升级不在本计划执行范围内。

## 10. 风险

- MiMo 只接受 `api-key`/Bearer 时，现有 `x-api-key` 会导致 401；没有 G3 不得并行实现 Anthropic 分支。
- DeepSeek Anthropic 端点若不支持，强制实现会产生不可用组合；必须由 S0 删除而非运行时猜测。
- 思考回传放大请求体积与上下文成本：S3 的预算口径未落地前，长对话可能提前触顶 `ai.context_budget_exceeded`。
- 提前删除 `compatibility`/`resolvedProfile` 会打断 Wave 1 冻结的 UI 与 Debug 设备测试（删除必须与 M1 同提交）；真实请求与设备 harness 若未执行，文档只能标“已核实、未实测”，不得用离线单测替代。

## 11. 完成定义（DoD）

- S0–S6 的单元测试与 S10 全量命令全绿；S7/S8 收口提交单独全量通过；S9 在 G2 解封后通过，否则明确列为 blocked。
- 矩阵 12 项逐项有断言并通过；5 个支持组合与未识别/Qwen×Anthropic 的 UI 禁用原因可见。
- `transcribe` 请求体与现状一致；开启态回传字段与顺序有测试；关闭态 `ai.provider_mode_mismatch` 保留且不重试。
- v1 配置读取、三种旧值到 DISABLED 的映射、url/model/key 不丢失均有测试，无数据清空、无新表；与 A/C 的共享文件只在 M1/M3/M4 由单一 owner 改动，最终 diff 无跨 track 冲突，真实请求记录已填或明确标“未实测”。
