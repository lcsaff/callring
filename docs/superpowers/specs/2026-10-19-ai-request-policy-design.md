# AI 请求策略设计：思考关闭与共享请求策略

状态：完整设计方案，待评审；2026-10-19。本文只定义 AI 请求策略（关闭思考、合同选择、共享解析、不可变快照、拦截、迁移与验收），不写界面布局，也不写更新/发布流程。

同系列文档：[A 主界面紧凑布局、档案入口与设置重组](2026-10-19-compact-main-ui-design.md)（UI/设置）、[C 应用更新与发布设计](2026-10-19-app-update-design.md)、[D 外部事实核对证据（2026-10-19）](2026-10-19-external-facts-verification.md)。界面与发布细节分别以 A、C 为准，厂商与仓库事实以 D 为准。

行号说明：本文所有 `文件:行` 为 2026-10-19 工作区快照，只用于定位；代码移动后以符号名和文件为准。阶段编号在本文件内独立（0 为前置阻断项、1–3 为实施顺序），与 A、C 的编号不对应。

## 1. 范围与边界

- 覆盖：普通聊天（含工具多轮）、短文本分析、长文本分段分析、分段结果合并、分析连接测试。五者共用同一套“供应商/兼容合同 + 协议 + 精确模型”解析与关闭参数下发逻辑。“共享”指共享能力表、解析器、快照结构与错误语义，不是把两个客户端合并成一个类。
- 排除：ASR。录音转写协议固定 `OPENAI`（`backend/.../application/RecordingWorkflow.kt:42,89`），永远不注入文本模型的思考关闭参数。
- 不改变：凭据隔离、上下文与输出预算、脱敏规则、业务授权与回执边界；不做“仍然发送”放行开关；不新增 Responses 协议，也不把已有接口迁到另一套字段。
- 产品背景：v0.2 正式 Release 的唯一 APK 下载次数为 1（D §2，弱依据，不代表真实装机量），迁移可按“几乎无存量用户”设计，但仍必须兼容读取旧配置，不能假设空库。

## 2. 现状与证据（2026-10-19 已核对）

**聊天关了，分析和连接测试没关。** 聊天客户端只在解析结果为 `QWEN_FAST` 时写入关闭参数；分析客户端（含分段、合并和分析连接测试）的请求体只有 `model/messages/max_tokens`，没有任何关闭字段。

| 入口 | 现状 | 证据 |
| --- | --- | --- |
| 聊天（含工具多轮） | 仅 `QWEN_FAST` 写入 `enable_thinking=false`、`preserve_thinking=false` | `app/.../platform/ai/CompatibleConversationClient.kt:31-32` |
| 短文本分析 | 请求体只有 model/messages/max_tokens，无关闭参数 | `app/.../platform/ai/CompatibleAiClient.kt:112-127` |
| 长文本分段 / 分段合并 | 每段与每次合并都是模型请求，均无关闭参数 | `CompatibleAiClient.kt:81-90`（`CHUNK_CHARS=32_000`）、`:91-108` |
| 分析连接测试 | 走 `AiClient.test`，最终调用 `analyze`，仍无关闭参数 | `CompatibleAiClient.kt:150`、`:112-127` |
| 测试用哪个客户端 | 设置服务与分析/转写共用同一个 `CompatibleAiClient` | `app/.../platform/ai/AndroidRecordingRuntime.kt:31-33`、`:37-44` |
| 合同字段位置 | 全局单值 `AiSettingsView.compatibility`（`AiSettingsPatch.compatibility`），只作用于分析连接 | `backend/.../application/api/AiSettingsApi.kt:16-21`；`app/.../platform/ai/AiSettingsService.kt:20,58-65` |
| 复制连接 | 复制到 ANALYSIS 时把全局合同重置为 `AUTO`；复制到 TRANSCRIPTION 时强制 `OPENAI` | `AiSettingsService.kt:66-75`（`:73`） |
| 测试草稿 | `testDraft` 只把 `resolveConversationProfile` 当校验，结果被丢弃 | `AiSettingsService.kt:77-83`（`:79`） |
| 持久化 | 连接 JSON 只有 protocol/url/key/model，旧记录没有合同字段 | `AndroidRecordingStore.kt:201-203`；`conversation` 块见 `:204-210` |
| 字段同步缺口 | 投影、冲突键、会话连接变更检测三处未包含任何合同字段 | `SafeProjection.kt:59,61`；`BusinessScopes.kt:24-25`；`ConversationService.kt:70,215` |
| 端口装不下快照 | `AiClient` 三个方法只收 `AiConnection`；`RecordingStore` 只有 `settings()`；另有装饰器实现 | `RecordingPorts.kt:6-15,17-23`；`AndroidAudioTranscriber.kt:14-17` |
| 录音批次 | 每个归档处理开始时读一次 `store.settings()`，转写、分析、分段/合并共用 | `RecordingWorkflow.kt:40,73,88-100,104-114` |
| 诊断能力 | `requestFlags` 只能装布尔；客户端只抽取两个布尔键 | `ConversationCompatibility.kt:27`；`CompatibleConversationClient.kt:142` |
| 硬失败 | OpenAI 格式回复含非空 `reasoning_content` 时直接失败 | `CompatibleConversationClient.kt:91` |

结论：聊天侧只是“碰巧在 QWEN_FAST 下关了”，分析侧从未关闭；合同字段既不是“每连接”的，也不能表达四态能力；端口、工具、投影与变更检测都没有承接新字段的位置。

## 3. 四态能力与供应商事实

能力解析必须区分四种状态，**“未确认”“不可关闭”都不是“已关闭”**；隐藏思考文字或返回里没有思考文字都不能证明关闭，只有策略判定可关闭且请求实际带上了关闭参数、服务端未拒绝，才可记为已关闭：

- `EXPLICIT_DISABLE`：可显式关闭，参数明确。
- `NO_THINKING_BY_DEFAULT`：文档确认无需开启思考（省略参数即可，不能伪造关闭参数）。
- `NOT_DISABLEABLE`：文档确认不可关闭，必须拦截。
- `UNCONFIRMED`：无已核实行或组合冲突，必须拦截。

静态能力表载体：`backend/.../application/api/ThinkingCapability.kt`（新增），纯数据 + 纯函数解析器，供 backend 与两个客户端共享。每行至少含：`contract × protocol × modelPattern`、四态、关闭参数（路径+值）、约束、`verifiedAt`、文档 URL、“已核实（文档+日期）”“已实测（真实请求）”两列。模型匹配只允许精确标识或表内受控正则，禁止“模型名前缀/URL 子串像某厂商”之类推断。

| 合同 × 协议 | 精确模型（表内规则） | 四态 | 关闭参数 | 约束与依据 | 已核实（文档+日期） | 已实测（真实请求） |
| --- | --- | --- | --- | --- | --- | --- |
| QWEN × OPENAI | `qwen3.8-flash` 或 `qwen3.8-flash-YYYY-MM-DD` | 可显式关闭 | HTTP JSON 顶层 `enable_thinking=false` | 混合思考模型可关；“仅思考”模型不可套用；SDK 的 `extra_body` 只是传参机制 | 已核实 2026-10-19（D §4） | 未实测（发布前两次验收补测） |
| QWEN × OPENAI | 表内未列出的其他 Qwen 模型 | 无法确认 | 无 | 不把单模型规则外推到整个供应商 | — | 未实测 |
| QWEN × ANTHROPIC | 任意 | 无法确认 | 无 | 无已核实组合行 | — | 未实测 |
| DEEPSEEK × OPENAI | DeepSeek 思考模式模型（实施时补精确标识） | 可显式关闭 | 顶层 `thinking={"type":"disabled"}` | 思考模式不支持 temperature/presence_penalty/frequency_penalty，策略不得额外加这些字段 | 已核实 2026-10-19（D §4） | 未实测 |
| DEEPSEEK × OPENAI（Responses 格式） | 任意 | 无法确认（本期无该协议） | 无 | 本期协议枚举无 RESPONSES，`reasoning.effort=none` 不落地，也不做字段迁移 | — | 未实测 |
| DEEPSEEK × ANTHROPIC | 任意 | 无法确认 | 无 | 合同与协议冲突：不得静默回退成“Anthropic 省略参数即不思考” | — | 未实测 |
| ANTHROPIC × ANTHROPIC | 新版默认思考模型（Opus 5 单独成行） | 可显式关闭 | 顶层 `thinking={"type":"disabled"}` | 新版默认开思考，可禁用 | 已核实 2026-10-19（D §4） | 未实测 |
| ANTHROPIC × ANTHROPIC | Opus 5 | 可显式关闭 | 同上 | effort 为 `xhigh/max` 时禁用会 400；本期不发送 effort，若网关默认落在该档则按参数被拒处理，不重试 | 已核实 2026-10-19 | 未实测 |
| ANTHROPIC × ANTHROPIC | 旧版非默认思考模型 | 文档确认无需开启思考 | 省略 `thinking` | 省略即不思考；精确模型标识实施时补录 | 已核实 2026-10-19 | 未实测 |
| OPENAI × OPENAI | 文档支持 `none` 的精确模型 | 可显式关闭 | Chat Completions 顶层 `reasoning_effort="none"` | `none` 才是关闭，`minimal/low` 不是；字段位置按端点，不混用 | 已核实 2026-10-19（D §4） | 未实测 |
| OPENAI × OPENAI | GPT-6 Astra | 不可关闭 | 无 | 不支持 `none`，返回 400，必须拦截 | 已核实 2026-10-19 | 未实测 |
| OPENAI × OPENAI | GPT-6.1 Sol | 不可关闭 | 无 | 不支持 `none/minimal`，必须拦截 | 已核实 2026-10-19 | 未实测 |
| OPENAI × ANTHROPIC | 任意 | 无法确认 | 无 | 无已核实组合行 | — | 未实测 |

`preserve_thinking` 结论：**不进入共享策略最小集合**。仅当合同为 `QWEN` 且模型行已核实可关闭时，与 `enable_thinking` 在同一处、同一来源一起解析下发（Qwen 侧保留该字段以免思考内容回传导致策略误判）；该例外必须写进能力表对应行与注释，其他合同一律不下发。

## 4. 合同字段与数据模型决策

**决策：合同放进分析连接的 `AiConnection`（每连接一份），仅 ANALYSIS 槽位展示与生效；TRANSCRIPTION 不展示、不生效，恒为未确认。** 连接表单中的合同选择独立于协议选择；自定义 URL 可选择 Qwen/DeepSeek/Anthropic/OpenAI 兼容合同，也可保持未确认。

- 数据模型：`AiConnection` 增加 `thinkingContract`（`QWEN/DEEPSEEK/ANTHROPIC/OPENAI/UNCONFIRMED`）与 `contractSource`（`USER_SELECTED/OFFICIAL_DIRECT_CONFIRMED/MIGRATED_LEGACY/UNCONFIRMED`）；`AiConnection`（`backend/.../application/api/RecordingApi.kt:7-22`）与 `AiConnectionView`（`AiSettingsApi.kt:16-21`）同步，补丁与工具 schema 同步。合同表示“用户选择的请求兼容方式”，来源必须区分：`USER_SELECTED` 只在用户显式选择时写入，不代表网关保证执行；`OFFICIAL_DIRECT_CONFIRMED` 只由 §5 的官方直连推断产生；`MIGRATED_LEGACY` 只由 §8 迁移产生；`UNCONFIRMED` 表示无可信合同。
- 转录槽位保存时把合同与来源归一为 `UNCONFIRMED`，`RecordingSettings.validate()` 增加“转录连接合同必须未确认”的断言，从数据层保证 ASR 永不受影响。
- 理由一：换分析连接不应继承旧合同。合同描述的是“这条连接 + 这个模型”的兼容方式；若做成全局，换连接后必须重新校验并清理，却仍可能把旧结论套到新连接上。
- 理由二：复制连接时合同随连接复制后按模型重新校验。现有复制仅支持跨槽位；复制到 ANALYSIS 若模型不匹配已核实行，则保留用户选择但判决为未确认；复制到 TRANSCRIPTION 时合同重置为未确认，协议仍强制 `OPENAI`（沿用 `AiSettingsService.kt:66-75` 的既有语义）。
- 理由三：旧的全局 `AiSettingsView.compatibility` 只保留用于迁移读取与降级回写，运行时不再参与解析；标记 deprecated，下一版本连同投影与 UI 一起删除。
- 若改为全局，需额外改动：`RecordingSettings` 顶层 + 持久化 `conversation` 块、`AiSettingsView/AiSettingsPatch` 顶层、`copyConnection` 的全局重算、`ConversationService.matches/fingerprint`、`BusinessScopes` 全局键、`ToolRegistry/ToolExecutor/OperationScopePlanner` 顶层参数、UI 单处展示；且无法表达“同一合同对 A 连接有效、对 B 连接未确认”，因此不采用。

## 5. 解析与优先级

解析顺序固定，输出一个判决（可发送/拦截）加可追溯来源：

1. 合同（显式选择或迁移结果）确定参数字族候选；`contractSource` 区分“用户指定”与“官方直连已确认”，两者都记录，不合并成布尔。
2. 协议（`OPENAI/ANTHROPIC`）确定字段承载位置与请求结构。
3. 在静态表中按 `(contract, protocol, 精确模型)` 匹配；无行即 `UNCONFIRMED`。
4. 合同与协议冲突（如 DeepSeek 合同 + ANTHROPIC 协议）：仍按合同确定候选族并记录 `conflict=true` 与来源，但组合没有已核实行时判决未确认并拦截，**不得回退到协议默认语义**，也不得因冲突而删合同。
5. 未选合同（旧 `AUTO`）：只允许复用现有官方直连推断——精确 `qwen3.8-flash`（含日期后缀）+ https + 无 userinfo + 端口 -1/443 + dashscope 官方 host（`ConversationCompatibility.kt:9-21`）→ Qwen 合同、来源 `OFFICIAL_DIRECT_CONFIRMED`；其余未确认。推断只用于识别官方直连，不能推广到自定义网关。
6. `resolvedProfile`（`ProviderProfile`）继续用于既有展示与网关兼容方式判断，不承担“是否关闭思考”的判定，两者不得互相替代。

## 6. 不可变策略快照

发起请求前生成 `RequestPolicySnapshot`（纯数据、不可变）；生成后本批次只读快照，不得再次读取可变全局配置或重新解析：

| 字段 | 要求 |
| --- | --- |
| 连接目标/合同/模型/协议 | 快照当次实际使用的值；含 `contractSource`、`fieldSource`、`conflict` |
| 关闭参数与依据 | 参数路径+值；四态判决；表 `verifiedAt`、`documentUrl` |
| 配置修订号 | `aiConfig` 修订号（`AiSettingsService.kt:19-21` 的 revision 键） |
| 凭据 | 只存受控引用（槽位+修订号/内存句柄），不序列化密钥；日志、诊断、验收记录一律脱敏 |
| 预算 | 上下文与输出预算（`ConversationBudget`） |
| 脱敏版本 | `SecretRedactor` 规则版本号，便于核对参数摘要与脱敏未退化 |

三处消费点：聊天（`ConversationService` 发起回合时生成，随 `ConversationRequest` 进入 `CompatibleConversationClient.encode`）、连接测试（`AiSettingsService.testSlot/testDraft` 用当前已保存配置或草稿生成）、每次录音分析批次（`RecordingWorkflow.process` 在 `:73` 只读一次生成，转写/分析/分段/合并共用）。快照不可变不代表必须持久化密钥；快照可带摘要用于诊断关联，但摘要不得含明文密钥。

连接变更沿用既有规则：`ConversationService.kt:48-51,68-71,215` 的监视与指纹在合同/来源变化时同样取消进行中的会话并返回 `ai.connection_changed`；测试草稿使用草稿快照，中途保存设置不改变本次测试目标；录音批次一旦带快照开始，本批次内设置变化不改变参数，但用户主动取消或连接冲突仍按既有取消规则处理。

**录音侧端口是前置依赖**：需要封存 `AiClient`（含 `AndroidAudioTranscriber` 装饰器，`RecordingPorts.kt:17-23`、`AndroidAudioTranscriber.kt:14-17`）与 `RecordingStore` 的设置快照路径（`RecordingPorts.kt:6-15`），让方法签名接收快照而不是重读全局配置。正式名称、契约、前置提交 SHA 留待原工程师提供，见 §11。

## 7. 拦截、后台行为与错误语义

- 交互请求（聊天发送、连接测试）判定为不可关闭或无法确认时：**不进入发送**，返回可操作错误 `ai.thinking_not_closable` / `ai.thinking_unconfirmed`（`VALIDATION`，字段指向 `analysis.contract` 或 `analysis.model`），UI 给出“去 AI 设置选择合同/修复模型”的入口；本期不提供“仍然发送”。
- 后台自动分析判定被拦截时：不发送模型请求，归档进入 `FAILED`，写入固定错误码（同上，另加 `ai.thinking_contract_mismatch`），`nextAttemptAt=Long.MAX_VALUE` 且不自动重试；保留录音、已得转写与已有分析，不清理数据。用户修复配置后走既有 `RecordingWorkflow.retry`（`:36-45`）手动重试。后台不弹确认框、不绕过策略。
- 参数被服务端拒绝（400/未知字段/参数组合非法）：返回明确错误 `ai.thinking_parameter_rejected`（新增 `AiFailureKind.THINKING_POLICY`），禁止删参数重试、禁止降 effort、禁止隐藏输出、禁止换模型后静默重试。聊天侧在 `CompatibleConversationClient.exchange` 的非 2xx 分支与 `CompatibleAiClient.request`（`:200-244`）都需要读取有界错误体做分类，错误文本不得回显密钥。
- `ai.provider_mode_mismatch` 与新错误的边界：策略判定不可关闭/未确认时**根本不会发送**，因此该错误只在“策略判定可关闭、请求已带关闭参数，但服务端仍返回思考内容”时出现（OpenAI 格式非空 `reasoning_content`；Anthropic 格式出现 `thinking/redacted_thinking` 内容块）。聊天与短/长分析统一为同一语义：`PROVIDER_MODE`，不可重试，提示“合同/网关未按预期关闭”，不删除参数、不静默重试。两个错误不会同时出现：拦截错误发生在发送前，该错误发生在成功响应解码时；分析客户端当前不检查回复思考内容，实施时补上同一判定，Anthropic 非文本块由 `ai.protocol_shape` 改判该错误（仅当本次确实下发了关闭参数）。
- `AiFailureKind.PROVIDER_MODE` 与新增 `THINKING_POLICY` 都不可重试；后台因此自然落到 `Long.MAX_VALUE`，与 `RecordingWorkflow.kt:104-114` 的退避规则一致（`retryable && attempts<4` 不成立）。

| 错误码 | 触发时机 | 可重试 | 处理 |
| --- | --- | --- | --- |
| `ai.thinking_not_closable` | 发送前，四态为不可关闭 | 否 | 交互返回可操作错误；后台 FAILED 且不自动重试 |
| `ai.thinking_unconfirmed` | 发送前，无已核实行/组合冲突 | 否 | 同上，提示选择合同或补精确模型 |
| `ai.thinking_contract_mismatch` | 发送前，合同与连接/模型明显不匹配 | 否 | 同上，字段指向合同 |
| `ai.thinking_parameter_rejected` | 服务端 400/未知字段/组合非法 | 否 | 明确错误，不删参、不降级、不换模型重试 |
| `ai.provider_mode_mismatch` | 已发送关闭参数但回复仍含思考内容 | 否 | 提示合同/网关未生效，人工修复后重新发起 |

## 8. 旧配置迁移

迁移只补合同字段，不阻塞保存、不清空地址/模型/密钥；读时兼容、写时落盘（惰性迁移），保存只提交实际改动字段：

| 旧值（全局 `conversationCompatibility`） | 迁移结果 | 来源 |
| --- | --- | --- |
| `QWEN_FAST` | 合同 `QWEN`；模型不匹配 `qwen3.8-flash`（含日期后缀）则 `UNCONFIRMED` | `MIGRATED_LEGACY` |
| `AUTO` | 沿用现有推断：精确 Qwen 模型 + 官方 host → 合同 `QWEN`，否则 `UNCONFIRMED` | `OFFICIAL_DIRECT_CONFIRMED` / `UNCONFIRMED` |
| `STANDARD` | `UNCONFIRMED` | `UNCONFIRMED` |

持久化改为每连接记录 `contract/contractSource`，`conversation` 块版本升为 2；读取 1 时按上表计算，读取 2 时直接使用；旧全局 `compatibility` 仍原样回写一个版本周期供降级，运行时不再读取。迁移实现落在 `RecordingJson`（`AndroidRecordingStore.kt:201-210`），读取 `settings()`（`:118-121`）即可得到已迁移的 `RecordingSettings`。迁移后不保证“能保存”等于“能发送”，未确认仍按 §7 拦截。

## 9. 诊断与验收

诊断结构升级：保留 `ExchangeMetrics.requestFlags: Map<String,Boolean>` 及两个既有布尔键，避免破坏现有断言（`ConversationProtocolTest.kt:25`、`ToolBusinessIntegrationTest.kt:198`）；新增 `policy: RequestPolicyTrace?`，含合同/来源/四态判决/参数摘要（参数名与规范化值，已脱敏）/`verifiedAt`/快照修订号/脱敏版本。`requestFlags` 由快照派生，两个客户端不再各自维护供应商判断。`requestFlags` 只装布尔的问题由此解决，不需要把对象硬塞进布尔映射。

离线回归矩阵（每行一个测试主题）：

| # | 场景 | 期望 |
| --- | --- | --- |
| 1 | 两客户端策略一致 | 同一连接/合同/模型下，聊天与分析生成相同关闭参数与判决 |
| 2 | 测试草稿快照 | 测试用草稿合同与已保存密钥；测试中途保存设置不改变本次请求 |
| 3 | 录音批次快照 | 批次内转写/分析/分段/合并参数一致；中途改设置不影响本批次 |
| 4 | 配置中途变化 | 合同/来源变化触发既有取消与 `ai.connection_changed`，目标指纹包含新字段 |
| 5 | 旧配置迁移 | 三种旧值映射正确；保存不丢 url/model/key；不阻塞保存 |
| 6 | 合同选择 | 用户选择合同 → `USER_SELECTED`；清除 → 未确认 |
| 7 | 拦截 | 不可关闭/未确认在发送前失败，无网络请求 |
| 8 | 参数被拒 | 400 归类 `ai.thinking_parameter_rejected`，不删参、不降级、不切换模型重试 |
| 9 | `ai.provider_mode_mismatch` | 仅“已解析可关闭但服务端仍思考”时出现；与拦截错误互斥 |
| 10 | 预算 | 超预算仍在发送前失败，快照预算生效 |
| 11 | 脱敏 | 参数摘要/诊断/日志不含密钥；脱敏版本记录可核对 |
| 12 | ASR 不受影响 | 转写请求体与现状逐字节等价（multipart 不变），合同字段不进入 ASR |
| 13 | requestFlags/参数摘要 | 两个既有布尔键不变，新 trace 可核对合同与参数 |
| 14 | 静态表自检 | 每行有 `verifiedAt` 与文档 URL；未确认/不可关闭行没有关闭参数；“未确认不得标记为已关闭” |

真实请求最低两次 Qwen（发布前执行，本次文档合并不发外部请求）：只读聊天（固定无敏感问题）+ 分析连接测试（固定合成文本），记录客户端版本、配置修订号、合同/模型/协议、脱敏参数摘要、时间与结果。除 Qwen 外的供应商与模型一律标注“已核实（文档+日期）、未实测”。

## 10. 完整代码落点表

| 范围 | 文件（现行路径） | 改动 |
| --- | --- | --- |
| 能力表与解析器 | `backend/.../application/api/ThinkingCapability.kt`（新增） | 四态、静态表、`resolveThinkingPolicy`、约束与来源 |
| 旧解析器 | `backend/.../application/api/ConversationCompatibility.kt:5-21` | 保留用于迁移/展示兼容；`resolveConversationProfile` 不再决定是否关闭 |
| 诊断模型 | `backend/.../application/api/ConversationCompatibility.kt:23-27` | `ExchangeMetrics` 增策略 trace；`AiFailureKind` 增 `THINKING_POLICY`（`requestFlags` 类型不变） |
| 连接模型 | `backend/.../application/api/RecordingApi.kt:7-22,25-40` | `AiConnection` 增合同/来源；`RecordingSettings.validate()` 增转录合同断言 |
| 设置视图 | `backend/.../application/api/AiSettingsApi.kt:16-21` | `AiConnectionView/AiSettingsView/ConnectionPatch/AiSettingsPatch` 增字段；旧全局字段 deprecated |
| 投影 | `backend/.../application/SafeProjection.kt:59,61` | 序列化新字段（转录恒未确认） |
| 冲突键 | `backend/.../application/BusinessScopes.kt:24-25` | 增加 `ai:<slot>:contract`、`ai:<slot>:contractSource` 键 |
| 会话变更检测 | `backend/.../application/conversation/ConversationService.kt:70,215`；`:48-51` | `matches` 与指纹纳入合同/来源/判决；取消逻辑沿用 |
| 会话请求 | `backend/.../application/ports/ConversationPorts.kt:6` | `ConversationRequest` 携带策略快照（聊天侧可在前置提交前完成） |
| 工具层 | `conversation/ToolRegistry.kt:25`；`ToolExecutor.kt:148,166,203-205`；`OperationScopePlanner.kt:37-42,80` | 分析连接 schema/补丁/作用域键增合同；旧 `?compatibility` 保留一版 |
| 聊天客户端 | `app/.../platform/ai/CompatibleConversationClient.kt:31-32,91,108-121,142,181` | 只消费快照；不再本地判断；新增 trace 与思考内容边界 |
| 分析/转写客户端 | `app/.../platform/ai/CompatibleAiClient.kt:43-75,81-90,91-108,112-127,150,200-244` | 分析/分段/合并/测试按快照注入关闭参数；400 分类；思考内容判定；转写永不注入 |
| 设置服务 | `app/.../platform/ai/AiSettingsService.kt:20-21,51-57,58-65,66-75,77-83,84-96` | 视图/补丁/复制/测试草稿携带合同；复制后按模型重校验；测试使用快照 |
| 持久化与迁移 | `app/.../platform/ai/AndroidRecordingStore.kt:118-121,201-210` | 连接 JSON 增字段；`conversation` v2；旧记录惰性迁移与降级回写 |
| 端口（前置提交） | `backend/.../application/RecordingPorts.kt:6-15,17-23` | `RecordingStore` 设置快照路径；`AiClient` 方法接收快照 |
| 装饰器 | `app/.../platform/ai/AndroidAudioTranscriber.kt:14-17` | 透传新签名，转写不读合同 |
| 批次执行 | `backend/.../application/RecordingWorkflow.kt:40,42,73,88-100,104-114` | 批次开头取一次快照；送入 transcribe/analyze；被拦截时 FAILED+固定码+不重试 |
| 装配 | `app/.../platform/ai/AndroidRecordingRuntime.kt:31-33,37-44` | 连接测试与录音工作流装配快照来源 |
| UI 字段与交互 | `ui/.../feature/ai/AiSettingsViewModel.kt:24,42-43,49-50`；`feature/ai/AiSettingsScreen.kt:30-41,88` | 仅分析槽展示合同/来源/判决；未确认/不可关闭显示原因与修复入口；文案更新到新错误码；不加“仍然发送” |
| 测试（现有文件） | `app/src/test/.../platform/ai/`：`AiSettingsServiceTest.kt`、`CompatibleAiClientTest.kt`、`ConversationProtocolTest.kt`、`ConversationCompatibilityTest.kt`、`ProviderProfileTest.kt`、`AndroidRecordingStoreTest.kt`、`ToolBusinessIntegrationTest.kt`、`RecordingSnapshotSourceTest.kt`；`backend/src/test/.../`：`ConversationServiceTest.kt`、`RecordingWorkflowTest.kt`、`ToolRegistryTest.kt`、`ToolOperationCoverageTest.kt`、`DraftMergeTest.kt`、`OperationScopePlannerTest.kt` | 扩展回归矩阵；表自检与解析器测试新增 `ThinkingCapabilityTableTest.kt`、`ThinkingPolicyResolverTest.kt` |

`...` 代表各模块现有的 `src/main/kotlin/com/greatwall/callring` 包路径。

## 11. 分阶段实施与 blocked 项

**阶段 0（前置提交，blocked-by）：原工程师封存录音两端口。** 必须封存 `AiClient`（含 `AndroidAudioTranscriber` 装饰器）与 `RecordingStore` 的设置快照路径，记录正式名称、契约、前置提交 SHA、测试结果与最终封存版本。本文占位：

```text
前置提交 SHA：待填
前置提交正式端口名称/契约：待填
最终封存版本：待填
```

**该阶段 blocked-by 前置未完成。** 前置未到位时，只允许先做“共享能力表 + 解析器 + 迁移 + 聊天侧 + 连接测试 + UI 字段”这一条线；录音侧接入与验收不得标记完成，不能把临时端口版本宣称为最终集成。

- 阶段 1（不依赖前置）：能力表与解析器、合同字段数据模型、迁移、聊天侧、连接测试、UI 字段；跑矩阵 1/2/5/6/7/8/9/10/11/13。
- 阶段 2（blocked-by 阶段 0）：录音侧端口接入、批次快照、后台拦截与不重试；跑矩阵 3/12 与端到端录音回归。
- 阶段 3（发布前）：离线矩阵全绿后，执行两次 Qwen 真实请求并记录 §9 字段；其余供应商维持“已核实、未实测”。无凭据/网络时列明未完成项，不得将发布验收标记为通过。

## 12. 外部依据

- Qwen 深度思考：https://help.aliyun.com/zh/model-studio/deep-thinking
- DeepSeek 思考模式：https://api-docs.deepseek.com/guides/thinking_mode/
- Anthropic 关闭思考：https://platform.claude.com/docs/en/build-with-claude/thinking#turning-thinking-off
- OpenAI 推理能力：https://developers.openai.com/api/docs/guides/reasoning#reasoning-effort
- 仓库与 Release、厂商原文摘录与代码事实：见 D《外部事实核对证据（2026-10-19）》§2、§4、§5。
