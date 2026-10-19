# AI 思考模式设计（Qwen / DeepSeek / MiMo）

状态：完整设计方案，待评审；2026-10-19（第 2 版，按"只暴露开关、隐藏复杂度"重写）。本文只定义 AI 思考模式的开关语义、厂商/协议适配、快照与验收；界面布局见 [A](2026-10-19-compact-main-ui-design.md)，更新发布见 [C](2026-10-19-app-update-design.md)，外部事实见 [D](2026-10-19-external-facts-verification.md)。

行号说明：`文件:行` 为 2026-10-19 工作区快照，只用于定位；代码移动后以符号名和文件为准。阶段编号在本文件内独立，与 A、C 不对应。

## 1. 需求与范围

【决策】兼容范围固定为 **3 家厂商 × 2 种接口协议**，用户界面只出现一个"思考模式"开关（开/关），不出现供应商、兼容合同、参数名或字段位置等任何复杂度。

| 厂商 | 接口协议 | 模型族 | 说明 |
| --- | --- | --- | --- |
| Qwen | OpenAI 兼容 | `qwen3.8-flash`（含日期后缀）及后续明确列入的 Qwen 混合思考模型 | 走 DashScope 官方地址 |
| DeepSeek | OpenAI 兼容 | `deepseek-flash` 等 DeepSeek 思考模式模型 | 精确标识实施时补，先按 `deepseek-*` 族识别 |
| DeepSeek | Anthropic 兼容 | 同上 | 端点与认证头实现前核对（见 §3 备注） |
| MiMo | OpenAI 兼容 | `mimo-v2.6-flash`、`mimo-v2.6-pro`、`mimo-v2.6-pro-ultraspeed`、`mimo-v2.5-pro`、`mimo-v2.5` | 默认开启思考 |
| MiMo | Anthropic 兼容 | 同上 | `https://api.xiaomimimo.com/anthropic/v1/messages` |

【决策】默认 **关闭思考**。理由：避免额外 token 费用；避免 OpenAI 协议回答里带思考内容触发既有 `ai.provider_mode_mismatch` 硬失败；MiMo 在开启状态下的多轮工具调用必须回传 `reasoning_content`，默认关闭可让首版对话链路保持简单。用户主动打开后按 §6 的回传要求执行。

【决策】排除项：ASR（转写协议固定 OpenAI，永不注入思考参数）；OpenAI 官方 GPT 与 Anthropic 官方 Claude（本轮不需要，若以后加入按同一矩阵扩展，不改机制）；effort/思考预算档位（本期只做开/关）；自建更新服务（属 C）。

【决策】不新增"供应商/兼容合同"用户选项。厂商与模型族由**协议 + 模型标识 + 官方地址**在运行时派生（§5），不落库、不展示；旧配置里的 `ConversationCompatibility` 只用于迁移读取（§9）。

## 2. 现状与证据（2026-10-19 已核对）

| 入口 | 现状 | 证据 |
| --- | --- | --- |
| 聊天（含工具多轮） | 仅当派生档位为 `QWEN_FAST` 时写 `enable_thinking=false`、`preserve_thinking=false` | `app/.../platform/ai/CompatibleConversationClient.kt:31-32` |
| 短文本分析 / 长文本分段 / 分段合并 | 请求体只有 `model/messages/max_tokens`，无任何思考字段 | `app/.../platform/ai/CompatibleAiClient.kt:112-127`、`:81-108` |
| 分析连接测试 | 走 `AiClient.test` → `analyze`，同样无思考字段 | `CompatibleAiClient.kt:150` |
| 派生档位 | 只认精确 `qwen3.8-flash` + DashScope 官方 host，且只用于聊天与测试校验 | `backend/.../application/api/ConversationCompatibility.kt:5-21`；`app/.../platform/ai/AiSettingsService.kt:82` |
| 配置字段 | 全局单值 `AiSettingsView.compatibility`，仅对分析连接生效；无"思考开关" | `backend/.../application/api/AiSettingsApi.kt:16-21` |
| 持久化 | 连接 JSON 只有 protocol/url/key/model | `app/.../platform/ai/AndroidRecordingStore.kt:201-210` |
| 认证头 | OpenAI 协议用 `Authorization: Bearer`，Anthropic 协议用 `x-api-key` + `anthropic-version` | `CompatibleConversationClient.kt:153-154`、`CompatibleAiClient.kt:215-218` |
| 诊断 | `requestFlags` 只能装布尔，只抽取两个布尔键 | `ConversationCompatibility.kt:26`、`CompatibleConversationClient.kt:142` |
| 兜底硬失败 | OpenAI 协议回复含非空 `reasoning_content` 时 `fail("ai.provider_mode_mismatch")` | `CompatibleConversationClient.kt:91` |
| 保存方式 | AI/录音设置页有"保存设置"按钮与草稿/冲突提示 | `AiSettingsScreen.kt:50`、`RecordingScreens.kt:85` |

结论：聊天只是"碰巧在 Qwen 快速档下关了"，分析侧从未关闭，MiMo/DeepSeek 完全没有适配，且没有可供用户切换的开关。

## 3. 能力矩阵（已核实 2026-10-19，依据见 D §4）

本客户端**两种状态都显式下发参数**，不依赖服务端默认值，避免"默认开/默认关"随模型变化。

| 厂商 × 协议 | 模型 | 关闭思考 | 开启思考 | 约束与依据 |
| --- | --- | --- | --- | --- |
| Qwen × OpenAI | `qwen3.8-flash`（含日期后缀）及表内 Qwen 混合思考模型 | `enable_thinking=false`，同时 `preserve_thinking=false` | `enable_thinking=true` | 混合思考模型可开关；"仅思考"模型不可用（不在本轮范围）；HTTP JSON 放顶层字段，SDK 的 `extra_body` 只是传参机制 |
| Qwen × Anthropic | — | 不支持 | 不支持 | 官方 DashScope 无 Anthropic 兼容端点；该组合不出现在可选范围 |
| DeepSeek × OpenAI | `deepseek-*` 思考模式模型（精确标识实施时补） | `thinking={"type":"disabled"}` | `thinking={"type":"enabled"}` | 同一供应商的 OpenAI/Anthropic/Responses 三格式字段不同；思考模式不支持 `temperature`/`presence_penalty`/`frequency_penalty` |
| DeepSeek × Anthropic | 同上 | `thinking={"type":"disabled"}`（Anthropic 格式） | `thinking={"type":"enabled"}` | **已核实 2026-10-19**：端点 `https://api.deepseek.com/anthropic`（`ANTHROPIC_BASE_URL`），认证头 `x-api-key`，`thinking` 字段受支持（`budget_tokens` 被忽略，正好符合本期只做开关） |
| MiMo × OpenAI | `mimo-v2.6-flash`、`mimo-v2.6-pro`、`mimo-v2.6-pro-ultraspeed`、`mimo-v2.5-pro`、`mimo-v2.5` | `thinking={"type":"disabled"}`（顶层字段；SDK 用 `extra_body`） | `thinking={"type":"enabled"}` | 上述模型默认开启思考；思考模式下 `temperature`/`top_p` 被强制为 1.0/0.95；多轮工具调用必须回传 `reasoning_content`，否则 400 |
| MiMo × Anthropic | 同上 | `thinking={"type":"disabled"}` | `thinking={"type":"enabled"}` | 端点 `https://api.xiaomimimo.com/anthropic/v1/messages`；**已核实 2026-10-19**：认证只文档化 `api-key: $MIMO_API_KEY` 与 `Bearer` 两种，**没有 `x-api-key`** → 现有 Anthropic 客户端必须按厂商切换认证头（MiMo 用 `api-key`，DeepSeek 用 `x-api-key`） |
| 未入表模型（含其他厂商） | — | 开关禁用 | 开关禁用 | 提示"当前仅支持 Qwen / DeepSeek / MiMo"；请求不注入任何思考参数，保持现状行为 |

【决策】矩阵内没有"不可关闭"的模型；因此四态收敛为三态：**可开关 / 不支持切换（开关禁用）/ 未识别（开关禁用）**。若后续加入仅思考模型（如某些 QwQ 类），按"开关禁用 + 说明"处理，不提供"强制关闭"。

## 4. 开关语义与请求构造

【决策】"思考模式"开关只有两态：**关（默认）/ 开**。关闭态 = 显式下发关闭参数；开启态 = 显式下发开启参数。两种状态都按下表构造，不用"省略参数"表达关闭（Anthropic 旧模型"省略即不思考"不适用于本轮三家）。

| 协议 | 放置位置 | 认证头 | 备注 |
| --- | --- | --- | --- |
| OpenAI | 请求体顶层字段（`enable_thinking` 或 `thinking`） | `Authorization: Bearer` | `extra_body` 只是 SDK 机制，HTTP JSON 按顶层发送 |
| Anthropic | 请求体顶层 `thinking: {"type": ...}` | 现有 `x-api-key`；MiMo 需 `api-key` 或 Bearer（实现前定稿） | 保留 `anthropic-version` |

【决策】思考模式为**开启**时，不发送 `temperature`、`top_p`、`presence_penalty`、`frequency_penalty`：DeepSeek 思考模式不支持这些字段，MiMo 会强制改写，发送只会造成误导；关闭态维持现状（当前客户端本就不发送这些字段）。

【决策】本期不使用 effort/思考预算参数（`reasoning_effort`、`thinking_budget`、`output_config.effort`、`reasoning.effort` 一律不发），矩阵里也不需要记录它们。若以后要做强度控制，另立设计，不扩展本开关。

## 5. 厂商与模型识别（运行时派生）

解析顺序固定，输出"可开关 / 开关禁用 + 原因"：

1. 协议：连接表单已有的 `OPENAI` / `ANTHROPIC`。
2. 模型族：按模型标识前缀匹配 `qwen*` / `deepseek*` / `mimo*`；表内精确模型优先，族内未列出的具体模型按族规则处理并在诊断中标注"族级匹配"。
3. 官方地址白名单（用于区分"官方直连"与"自定义网关"）：`dashscope.aliyuncs.com`、`dashscope-intl.aliyuncs.com`、`dashscope-us.aliyuncs.com`（Qwen）、`api.deepseek.com`（DeepSeek）、`api.xiaomimimo.com`（MiMo），要求 https、无 userinfo、端口 -1/443。
4. 判定：
   - 官方直连 + 协议受支持 → 按矩阵开关，正常可用；
   - 自定义网关 + 模型族可识别 → 开关可用，但 UI 用一行说明标注"自定义网关：参数可能不被执行"；
   - 模型族无法识别，或协议与厂商组合不在矩阵内（如 Qwen × Anthropic） → **开关禁用**并显示具体原因。
5. 不持久化厂商或合同；`resolvedProfile`/`ProviderProfile` 的既有展示与快速档语义在本设计中废止（§9 迁移）。

## 6. 开启路径的协议要求（本轮新增重点）

【决策】开启思考后，必须按协议回传思考内容，否则多轮工具调用会 400 或上下文失真。

| 协议/厂商 | 要求 |
| --- | --- |
| OpenAI 协议 + MiMo | 后续轮次的 assistant 消息（尤其含 tool_calls 的）必须原样回传 `reasoning_content`；缺少即 400 |
| OpenAI 协议 + DeepSeek | `reasoning_content` 会被服务端忽略，回传与否不影响正确性；统一策略仍保留回传，减少分叉 |
| OpenAI 协议 + Qwen | `preserve_thinking=false`（关闭态现有行为）保持；开启态不发送该字段，思考内容按服务端返回处理 |
| Anthropic 协议（MiMo/DeepSeek） | 含 `tool_use` 的 assistant 回合必须原样回传 `thinking` / `redacted_thinking` 块，顺序与原文一致 |

【决策】客户端调整：`CompatibleConversationClient.decode` 目前对非空 `reasoning_content` 直接 `ai.provider_mode_mismatch`。改为：**开启态**保留并回传（不报错）；**关闭态**保留该兜底错误（说明厂商未按参数关闭），错误文案指向"换模型或换协议"，并禁止自动重试。分析客户端在开启态也不得丢弃思考内容（不参与入库，但需计入输出 token 预算）。

【决策】上下文与预算：回传的思考内容计入输出预算与请求体积；预算不足时按既有规则停止，不静默截断。

## 7. 不可变策略快照

发起前生成 `RequestPolicySnapshot`（纯数据、不可变），同批次只读快照、不重读全局配置：

| 字段 | 要求 |
| --- | --- |
| 开关状态 | `thinkingMode = ON/OFF`（用户显式选择） |
| 派生结果 | 厂商、模型族、协议、是否官方直连、关闭/开启参数与放置位置、判定（可用/禁用原因） |
| 配置修订号 | `aiConfig` 修订号 |
| 凭据 | 只存受控引用（槽位+修订号/内存句柄），不序列化密钥；日志/诊断/验收脱敏 |
| 预算 | 上下文与输出预算（含回传思考内容的口径） |
| 脱敏版本 | `SecretRedactor` 规则版本 |

三处消费点不变：聊天（含工具多轮）、连接测试、每次录音分析批次（批次内转写/分析/分段/合并共用同一快照）。**连接测试不再使用"草稿"**：设置即时生效后没有草稿概念，测试使用当前已保存配置（见 §8）。

## 8. 即时生效（与 A 联动，取代"保存设置"）

【决策】设置页不再有"保存设置"按钮，AI/录音/思考开关的每次改动立即发起 patch 并落库；请求时的快照取自最新已保存配置。

- 布尔/单选/下拉类字段：切换即提交，提交期间禁用该控件，成功后用返回值刷新基线。
- 数值文本字段（上下文预算、输出预留）：停止输入 500ms 或失焦且解析合法时提交；非法中间值不落库，就地显示字段错误；提交失败保留输入并显示原因。
- 密钥：仍通过"本地编辑密钥"对话框一次性确认提交（不能逐字符写库）。
- 冲突：每次 patch 携带 `base` 修订号；冲突时刷新当前值并提示"已更新，请重新修改"，不覆盖他人写入。
- 取消路径：设置页不再有"放弃未保存修改"对话框，返回即离开（已生效的改动不回滚）；需要撤销时用户重新修改。
- 连接测试：使用当前生效配置，测试期间修改设置不影响本次请求目标（快照）。

## 9. 旧配置迁移

【决策】只补一个字段，不引入合同库、不清空用户数据。

- `AiConnection`（分析连接）新增 `thinkingMode: ENABLED | DISABLED`，默认 `DISABLED`；转录连接不参与思考（ASR 恒定不注入），保存时忽略该字段。
- 旧 `conversationCompatibility`：不再持久化、不再参与解析；读取旧值只用于迁移判断——`QWEN_FAST` 且模型为 Qwen 族 → `thinkingMode=DISABLED`（等价于原来关闭）；`AUTO`/`STANDARD` → `thinkingMode=DISABLED`（新默认）。迁移不阻塞保存、不清空地址/模型/密钥。
- `ProviderProfile`/`resolvedProfile` 与 `ConversationCompatibility` 的运行时语义废止（不再参与思考判定）；但**本轮不删除字段**：`AiRoutes.kt:25` 与 `app/src/debug/.../RealAiConversationDeviceTest.kt` 仍引用 `resolvedProfile`/`compatibility`/`requestFlags`，删除动作推迟到 M1 收口后单独变更；`testDraft` 同样保留到 M1，届时改用 `testSlot` 并删除契约。
- 持久化：连接 JSON 增 `thinkingMode`；`conversation` 块版本升为 2，读取 1 时按上表补默认值。

## 10. 错误语义

| 错误码 | 触发时机 | 可重试 | 处理 |
| --- | --- | --- | --- |
| `ai.thinking_unsupported` | 模型/协议组合在矩阵外，或用户尝试强制切换 | 否 | 开关禁用，说明"当前仅支持 Qwen/DeepSeek/MiMo 的对应协议" |
| `ai.thinking_parameter_rejected` | 服务端 400 / 未知字段 / 参数组合非法 | 否 | 明确错误；禁止删参重试、禁止降 effort、禁止换模型或换协议静默重试 |
| `ai.provider_mode_mismatch` | **关闭态**下服务端仍返回思考内容 | 否 | 提示"该模型/网关未按预期关闭思考"，建议换模型或换协议；不自动重试 |
| `ai.model_unrecognized` | 模型族无法识别 | 否 | 开关禁用并说明；请求仍可发送（不注入参数），但界面标注"未识别厂商" |

## 11. 诊断与验收

诊断：保留 `ExchangeMetrics.requestFlags: Map<String,Boolean>`（两个既有布尔键不变），新增 `policy: RequestPolicyTrace?`，含开关状态、派生厂商/族/协议/官方直连、参数摘要（已脱敏）、`verifiedAt`、快照修订号、脱敏版本。分析客户端与聊天客户端不再各自判断厂商。

离线回归矩阵：

| # | 场景 | 期望 |
| --- | --- | --- |
| 1 | 三家 × 两协议 × 开关两态 | 参数名/位置/值完全符合 §3、§4 |
| 2 | 默认态 | 新配置 `thinkingMode=DISABLED`，首次请求即下发关闭参数 |
| 3 | 未知模型 | 开关禁用 + `ai.model_unrecognized`，请求不注入参数 |
| 4 | 组合不支持（Qwen×Anthropic） | 开关禁用，提示改用 OpenAI 协议 |
| 5 | 开启态回传（MiMo OpenAI / MiMo Anthropic / DeepSeek） | `reasoning_content` / thinking 块原样回传；缺失即 400 的用例被覆盖 |
| 6 | 关闭态仍返回思考内容 | `ai.provider_mode_mismatch`，不重试 |
| 7 | 参数被拒 | `ai.thinking_parameter_rejected`，不删参、不换模型重试 |
| 8 | 即时生效 | 每次开关/字段变更立即 patch；防抖只对数值字段生效；冲突提示且不覆盖 |
| 9 | 快照稳定 | 测试/聊天/录音批次内配置变更不影响本次请求 |
| 10 | 迁移 | `QWEN_FAST`/`AUTO`/`STANDARD` 三种旧值映射为 `DISABLED`，不丢 url/model/key |
| 11 | 脱敏与预算 | 摘要有参数无密钥；思考内容计入输出预算 |
| 12 | ASR 不受影响 | 转写请求体与现状一致，不注入思考字段 |

真实请求（发布前）口径明确：**必做两次 Qwen**（关闭态只读聊天 + 连接测试）作为基线；MiMo、DeepSeek 若要标"已实测"，各自开启态再各做一条（合计最多 4 条）。未做真实验证的组合一律标"已核实（文档+日期）、未实测"，不得据文档写成已实测。

## 12. 代码落点

| 范围 | 位置（现行路径） | 改动 |
| --- | --- | --- |
| 能力矩阵与解析 | 新增 `backend/.../application/api/ThinkingPolicy.kt` | 三家 × 两协议矩阵、模型族识别、开关参数与放置位置、判定与依据 |
| 旧解析器 | `backend/.../application/api/ConversationCompatibility.kt:5-21` | 语义废止，仅保留迁移读取 |
| 连接模型 | `backend/.../application/api/RecordingApi.kt:7-40` | `AiConnection` 增 `thinkingMode`；`validate()` 增转录忽略断言 |
| 设置视图 | `backend/.../application/api/AiSettingsApi.kt:16-26` | `AiConnectionView/ConnectionPatch/AiSettingsPatch` 增开关字段；删除/废弃 `compatibility` |
| 投影与冲突键 | `SafeProjection.kt:59,62`、`BusinessScopes.kt:24-25` | 序列化与 `ai:<slot>:thinkingMode` 键 |
| 会话变更检测 | `conversation/ConversationService.kt:70,215` | 指纹与 `matches` 纳入开关状态 |
| 工具层 | `conversation/ToolRegistry.kt:25`、`ToolExecutor.kt:148,166,203-205`、`OperationScopePlanner.kt` | AI 连接 schema/补丁增开关字段，删除 `?compatibility` |
| 聊天客户端 | `app/.../platform/ai/CompatibleConversationClient.kt:31-32,91,142,153-154` | 按快照下发开关参数；开启态回传 reasoning；关闭态保留兜底错误；MiMo/DeepSeek Anthropic 认证头 |
| 分析/转写客户端 | `app/.../platform/ai/CompatibleAiClient.kt:43-75,81-108,112-127,150,215-218` | 分析/分段/合并/测试按快照注入；转写永不注入；Anthropic 认证头适配 |
| 设置服务 | `app/.../platform/ai/AiSettingsService.kt:20,51-92` | 视图/补丁/复制/测试携带开关；无草稿语义 |
| 持久化与迁移 | `app/.../platform/ai/AndroidRecordingStore.kt:118-121,201-210` | 连接 JSON 增 `thinkingMode`；`conversation` v2 + 旧值映射 |
| 端口（前置提交） | `backend/.../application/RecordingPorts.kt:6-15,17-23` | `AiClient` 方法接收策略快照（录音侧，blocked） |
| 装饰器 | `app/.../platform/ai/AndroidAudioTranscriber.kt:14-17` | 透传新签名，转写不读开关 |
| 批次执行 | `backend/.../application/RecordingWorkflow.kt:40,42,73,88-100,104-114` | 批次开头取一次快照；被禁用时不发送并落固定错误 |
| UI | `ui/.../feature/ai/AiSettingsScreen.kt`、`AiSettingsViewModel.kt`、`ui/.../feature/recording/RecordingScreens.kt` | 只放"思考模式"开关与原因说明；删除保存按钮与草稿态；错误文案接新码 |
| 测试 | `app/src/test/.../platform/ai/`（实际存在：`CompatibleAiClientTest`、`AiSettingsServiceTest`、`AndroidRecordingStoreTest`、`ConversationProtocolTest`、`ConversationCompatibilityTest`、`ProviderProfileTest`、`ToolBusinessIntegrationTest`、`RecordingSnapshotSourceTest`）；`app/src/debug/.../RealAiConversationDeviceTest.kt`（引用 compatibility/resolvedProfile/requestFlags，需同步改）；`backend/src/test/.../`（`ConversationServiceTest`、`RecordingWorkflowTest`、`ToolRegistryTest`、`ToolOperationCoverageTest`、`DraftMergeTest`、`OperationScopePlannerTest`） | 按 §11 矩阵扩展；新增 `ThinkingPolicyMatrixTest`、`ThinkingPolicyResolverTest`；不存在的 `CompatibleConversationClientTest` 不写入计划 |

`...` 代表各模块现有的 `src/main/kotlin/com/greatwall/callring` 包路径。

## 13. 分阶段与端口改造

录音侧端口由**本设计自己改**，不再等待外部"前置提交"：要改的是 `AiClient`（`analyze/test` 接收策略快照，`transcribe` 不变）与 `RecordingStore` 的设置快照读取路径，`AndroidAudioTranscriber` 透传新签名。先落端口签名与单测，再接入录音批次；不存在"等别人先提一版接口"的阻塞。

- **阶段 1：** 能力矩阵与解析、`thinkingMode` 数据模型、迁移、聊天与连接测试、Anthropic 认证头按厂商适配。
- **阶段 2：** 端口签名改造 → 录音批次快照 → 被禁用时不发送并落固定错误。
- **阶段 3（发布前）：** 离线矩阵全绿 + 真实请求（基线 2 条 Qwen；MiMo/DeepSeek 开启态各一条才能标"已实测"）。

若确有外部负责人在同一时间修改这两个接口，才需要其提供正式名称/契约/SHA 并以外部版本为准；否则一律按本设计执行，不设 blocked 占位。

## 14. 外部依据

- MiMo 深度思考：https://mimo.mi.com/docs/en-US/quick-start/usage-guide/text-generation/deep-thinking
- MiMo Anthropic 兼容：https://mimo.mi.com/docs/zh-CN/api/chat/anthropic-api
- DeepSeek 思考模式：https://api-docs.deepseek.com/guides/thinking_mode/
- Qwen 深度思考：https://help.aliyun.com/zh/model-studio/deep-thinking
- 原始摘录与代码事实：见 D §4、§5。
