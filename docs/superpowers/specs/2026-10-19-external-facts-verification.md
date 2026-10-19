# 外部事实参考（2026-10-19）

状态：事实附录。核对日期 2026-10-19；来源为 GitHub REST API、厂商官方文档、本机 `keytool`/`apksigner` 实查。只保留实现与验收直接需要的事实；未核对项见 §6。

## 1. 核对范围

仓库与 Release、latest 规则、三家厂商的思考开关参数、本机签名与关键代码事实；不记录核对过程与工具细节。

## 2. 仓库与 Release

- 仓库 `lcsaff/callring` 公开（`private=false`），default_branch `main`。
- 正式 Release：tag `v0.2`，draft/prerelease=false，published 2026-10-19T03:49:30Z，target `8e38c66…`；`/releases/latest` 现返回 v0.2。
- 资产：`callring-0.2.apk`（33,570,356 字节，下载 1 次）、`SHA256SUMS.txt`（84 字节）；APK SHA-256 `103bfebc…8772`。
- 远端标签仅 `refs/tags/v0.2`（轻量，指向 `8e38c66…`）；本地无标签；无 `.github/`、`tools/` 无发布脚本。
- 速率限制：未认证 core 60/h；缺 `User-Agent` 返回 403；限流 403/429 带 `X-RateLimit-*`。

### 发行签名现状（本机实查）

- v0.2 证书 SHA-256 `ac2a448935603a5a42985969732c32109ef4980e8fb435e061d36886cf4062ee`；对应发行密钥已丢失。
- 本机 debug 证书：`C:\Users\GreatWall\.android\debug.keystore`，DN `CN=Android Debug`，SHA-256 `5a901cad57f5590483f362e4752ca8f4a56197340b71d2632ded0195bf8a24b4`，SHA-1 `4264850a8ca22e6e1cc33058cb4572db225ec8e3`。
- `app-debug.apk` 实测为该 debug 证书；`app-release-unsigned.apk` 未签名（`apksigner: DOES NOT VERIFY`）。
- 结论：两证书不同 → v0.2 不可被本机构建覆盖；同 debug 证书、versionCode 递增的包可覆盖本机 debug 安装。

## 3. latest 语义

- `make_latest`：draft/prerelease 不能设为 latest；新发布 Release 默认 true；`legacy` 按“创建日期 + 更高语义版本”判定。
- 发布流程须显式 `make_latest:"true"`，并在发布后核对 `/releases/latest` 的 tag。
- 数据点：v0.2 created 2026-10-15、published 2026-10-19，相差 4 天，不能按时间顺序推断 latest。

## 4. 厂商关闭思考参数（2026-10-19 官方文档）

| 厂商 × 协议 | 关闭 | 开启 | 约束 |
| --- | --- | --- | --- |
| Qwen × OpenAI | `enable_thinking=false`（+`preserve_thinking=false`） | `enable_thinking=true` | 仅混合思考模型；HTTP JSON 顶层 |
| DeepSeek × OpenAI | `thinking={"type":"disabled"}` | `thinking={"type":"enabled"}` | 思考模式不支持 temperature/presence/frequency |
| DeepSeek × Anthropic | 同上 | 同上 | 端点 `https://api.deepseek.com/anthropic`，认证 `x-api-key`，`budget_tokens` 忽略 |
| MiMo × OpenAI | `thinking={"type":"disabled"}` | `thinking={"type":"enabled"}` | 5 个模型默认开；temperature/top_p 强制 1.0/0.95；多轮工具须回传 `reasoning_content` |
| MiMo × Anthropic | 同上（body `thinking`） | 同上 | 端点 `api.xiaomimimo.com/anthropic/v1/messages`；认证 `api-key` 或 Bearer，**无 `x-api-key`** |

## 5. 代码事实（行号为 2026-10-19 快照，仅定位）

| 结论 | 证据 |
| --- | --- |
| 聊天仅 `QWEN_FAST` 下发关闭参数 | `CompatibleConversationClient.kt:31-32` |
| 分析/分段/合并/测试无思考参数 | `CompatibleAiClient.kt:81-108,112-127,150` |
| 能力解析只认 `qwen3.8-flash` + 官方 host | `ConversationCompatibility.kt:9-21` |
| 旧合同字段为全局单值，仅分析连接 | `AiSettingsApi.kt:16-24` |
| 持久化连接 JSON 仅 4 字段、conversation v1 | `AndroidRecordingStore.kt:201-210` |
| 投影/冲突键/指纹未含新字段 | `SafeProjection.kt:59,62`、`BusinessScopes.kt:24-25`、`ConversationService.kt:70,215` |
| AI 工具入参与补丁解析（含 `?compatibility`） | `ToolRegistry.kt:25`、`ToolExecutor.kt:148,166,203-205` |
| 工具操作契约（编号/名称）不可变 | `OperationContract.kt:15` |
| `AiClient` 端口只收连接配置 | `RecordingPorts.kt:17-23`、`AndroidAudioTranscriber.kt:14-19` |
| 录音批次开始时读一次 settings，后续复用 | `RecordingWorkflow.kt:73,94,100,104-114` |
| 聊天回复含思考内容时硬失败 | `CompatibleConversationClient.kt:91` |
| Anthropic 协议认证头为 `x-api-key` | `CompatibleConversationClient.kt:154`、`CompatibleAiClient.kt:217-218` |
| 设置页保存/草稿现状 | `SettingsComponents.kt:70`、`FormInteractions.kt`、`AiSettingsScreen.kt:50`、`RecordingScreens.kt:85` |
| 版本与签名 | `app/build.gradle`（versionCode 2 / versionName 0.2 / 无 signingConfigs） |
| 后端测试任务 | `backend/build.gradle:1`（`java-library` → `:backend:test`） |

## 6. 未核实项

- Play 政策原文；`setVisibleInDownloadsUi(false)` 在新系统的实际表现。
- GitHub 服务端 asset digest（以 `SHA256SUMS.txt` 为唯一来源）。
- 设备侧安装/下载/覆盖升级（未实测）；真实厂商请求（未执行，需凭据与网络）。
