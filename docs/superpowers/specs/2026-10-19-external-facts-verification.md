# 外部事实核对证据（2026-10-19）

状态：**证据附录，不是设计规范**。核对时间 2026-10-19 15:00–17:10（Asia/Shanghai）。本文只记录"核对过什么、怎么核对、结论是什么"，供同批三份设计文档与后续发布流程引用，不重复原始数据。

事实分级：

- **已核实**：有接口返回、页面原文或代码实现作为依据。
- **推论**：由已核实事实推出，标注依据；不得当作独立事实引用。
- **未知**：本轮无法核对，集中在 §6。

厂商模型能力有保鲜期，本文有效期以核对日期 2026-10-19 为准；模型迭代后必须重核再改实现。

引用关系：

| 文档 | 引用本文 |
| --- | --- |
| `2026-10-19-compact-main-ui-design.md`（UI/设置） | §5 代码事实 |
| `2026-10-19-ai-request-policy-design.md`（AI 请求策略） | §4 厂商能力、§5 代码事实 |
| `2026-10-19-app-update-design.md`（更新与发布） | §2 仓库与 Release、§3 latest 语义、§5 代码事实 |

## 1. 核对方法与限制

- GitHub：HTTPS 直连 `api.github.com`（PowerShell `Invoke-WebRequest`），请求头 `User-Agent: callring-review`、`Accept: application/vnd.github+json`；Release 说明用 `https://github.com/lcsaff/callring/releases/tag/v0.2` 页面文本交叉复核。
- 厂商文档：抓取页面后去标签取正文，按关键词取上下文；长页面未逐字通读，结论只基于已取到的原文片段。
- 本轮限制：
  - 会话内网页抓取工具对 `api.github.com` 报"解析到非公网 IP"不可用，改用 PowerShell HTTPS 直连，不影响结论。
  - 未认证 REST 配额 60 次/小时；核对末尾配额用尽（403 `rate limit exceeded`），§2 数字均取自首次成功响应。
  - 资产的服务端 `digest` 字段本轮未取得；APK 摘要以 Release 内 `SHA256SUMS.txt` 为准。
  - 未执行任何设备侧安装、下载或升级验证。
- 一处已修正的采集错误（记录以备复核）：Release 说明折行显示时证书指纹字段曾丢失 1 个字符；以 Release 页面文本与 API body 双处复核后，采用 64 位完整值 `ac2a448935603a5a42985969732c32109ef4980e8fb435e061d36886cf4062ee`。引用本文件时以该值为准。

## 2. 仓库与正式 Release（已核实）

仓库（`GET /repos/lcsaff/callring`）：

| 字段 | 值 |
| --- | --- |
| full_name | `lcsaff/callring` |
| private / visibility | `false` / `public`（**公开仓库**，无需内置令牌） |
| default_branch | `main` |
| created_at | 2026-10-19T03:39:09Z |
| pushed_at | 2026-10-19T03:49:30Z |
| language / license | Kotlin / `null` |

正式 Release（`GET /releases/416883257`，且 `/releases/latest` 返回同一条）：

| 字段 | 值 |
| --- | --- |
| id | `416883257` |
| tag_name / name | `v0.2` / `Callring v0.2` |
| draft / prerelease | `false` / `false` |
| created_at | 2026-10-15T07:24:02Z |
| published_at | 2026-10-19T03:49:30Z |
| target_commitish | `8e38c66cfe73991dff72a9ea36085e5bd62e575b`（= `main`、= 远端 `v0.2` 标签指向） |
| html_url | https://github.com/lcsaff/callring/releases/tag/v0.2 |

结论：**线上最高版本已存在**——`versionCode 2` / `versionName 0.2`，由正式 Release 发布；`0.3.0` / `versionCode 3` 的前提（已发布最高为 2）成立。

资产（同一 Release）：

| 名称 | 大小（字节） | 类型 | 下载次数 |
| --- | --- | --- | --- |
| `callring-0.2.apk` | 33,570,356 | `application/vnd.android.package-archive` | 1 |
| `SHA256SUMS.txt` | 84 | `text/plain; charset=utf-8` | 0 |

下载地址形如 `https://github.com/lcsaff/callring/releases/download/v0.2/callring-0.2.apk`。

`SHA256SUMS.txt` 全文：

```text
103bfebcc4ed8d326d5d48e9dc3b3c893a203e897cc62a456598a05c88278772  callring-0.2.apk
```

Release 说明原文摘录（API `body` 与页面文本一致）：

- 基于 `main` 分支提交 `8e38c66cfe73991dff72a9ea36085e5bd62e575b` 构建。
- 版本 0.2（versionCode 2）；包名 `com.greatwall.callring`；最低系统 Android 10（API 29）。
- 正式 Release 构建，**使用独立发行密钥签名**；签名证书 SHA-256：`ac2a448935603a5a42985969732c32109ef4980e8fb435e061d36886cf4062ee`。
- 此版本与原 debug 安装包签名不同，**不能直接覆盖 debug 包**；卸载会丢失本地数据；**后续发行版将沿用本次发行签名**。
- 此版本未添加应用内自动检查更新功能。
- 验证：Release 构建成功；后端 66 项、应用 66 项、UI 111 项测试通过（共 243 项）；Release lint 无错误；APK 签名与 zipalign 校验通过。

标签与本地状态：

- 远端标签仅 `refs/tags/v0.2`，`git ls-remote --tags` 直接指向 `8e38c66…`，是**轻量标签**（无 annotated tag 对象）。
- 本地工作区 `git tag` 无输出；原设计稿"本地未发现 Git 标签"只对**本地**成立，对远端不成立。
- 仓库无 `.github/`；`tools/` 现有 4 个 Python 校验脚本（`check_backend_boundaries.py`、`verify_backend.py`、`verify_ui.py`、`generate_melody.py`），没有发布/签名脚本。

速率限制观测：

- 未认证 `core.limit = 60`/小时；首次成功调用时 `remaining = 40`、`used = 20`。
- **不带 `User-Agent` 的请求返回 403**（此前"403 限流"判断的成因之一）；补自定义 UA 后同一接口返回 200 并正常取数。
- 核对末尾配额用尽，后续查询返回 403 `API rate limit exceeded`。更新客户端必须显式发送 UA，并把 403 分成"UA/权限"与"限流"两类反馈。

## 3. latest 语义（已核实，GitHub REST 文档 create/update release）

文档原文要点：

- `make_latest`：指定该 release 是否设为仓库 latest；**draft 与 prerelease 不能被设为 latest**；**新发布的 release 默认为 `true`**；`legacy` 表示按"创建日期 + 更高语义版本"确定 latest。
- `/releases/latest` 返回被标记为 latest 的正式 release（本轮实测返回 `v0.2`）。

应用约束（推论，依据上述原文与数据点）：

- 不能凭"最后发布"或"最大版本号"的直觉判断 latest：`legacy` 会改成按创建日期排序。
- 发布流程应**显式 PATCH `make_latest: "true"`**，并在发布后核对 `/releases/latest` 返回的 tag 等于本次 tag，避免依赖默认行为。
- 数据点：`v0.2` 的 `created_at`（10-15）与 `published_at`（10-19）相差 4 天，说明创建时间 ≠ 发布时间，用时间顺序推断 latest 不可靠。

## 4. 关闭思考参数（已核实，2026-10-19）

兼容范围（2026-10-19 产品决定，见 B §1）：本轮只需 **Qwen、DeepSeek、MiMo** 三家，接口协议为 **OpenAI 兼容与 Anthropic 兼容**两种；OpenAI 官方 GPT 与 Anthropic 官方 Claude 暂不在范围，其文档仅作为协议语义参考保留在 §4 表中。

| 供应商 | 文档 | 关键原文 | 对本项目的约束 |
| --- | --- | --- | --- |
| Qwen | https://help.aliyun.com/zh/model-studio/deep-thinking | "百炼深度思考模型分为两种模式：混合思考模式：可按请求开启或关闭思考…设为 true：模型先思考再回复；设为 false：模型直接回复"；"`thinking_budget`…适用于 Qwen3.8、Qwen3.7…；其中 kimi-k3 不支持该参数" | 只有"混合思考模式"模型可关；`enable_thinking=false` 有效；另有 `thinking_budget` 但不在本期范围；SDK 用 `extra_body`，HTTP JSON 放顶层字段 |
| DeepSeek | https://api-docs.deepseek.com/guides/thinking_mode/ | "Thinking Mode Toggle：OpenAI 格式 `{"thinking": {"type": "enabled/disabled"}}`；Responses 格式 `{"reasoning": {"effort": "none/low/high/max"}}`（none disables thinking mode）"；"Thinking mode does not support the temperature, presence_penalty, or frequency_penalty parameters" | 同一供应商**按协议不同字段不同**；Anthropic 兼容端点另有一套；不能只按供应商名硬编码一种字段 |
| Anthropic | https://platform.claude.com/docs/en/build-with-claude/thinking | "thinking is on by default, you can turn it off: `thinking = {"type": "disabled"}`"；"Claude Opus 5 also has thinking on by default and accepts `thinking:{type:"disabled"}` at effort high or below. **At xhigh or max effort, thinking cannot be turned off: requests that combine… return a 400 error**" | 新版模型默认开思考，可显式 `disabled`；存在**参数组合限制**；旧模型是"省略参数即不思考"；隐藏 thinking 输出 ≠ 关闭 |
| OpenAI | https://developers.openai.com/api/docs/guides/reasoning | "The `reasoning.effort` parameter… Supported values are model-dependent and can include none, minimal, low, medium, high, xhigh, and max"；"**GPT-6 Astra does not support none**…returns HTTP 400"；"**GPT-6.1 Sol does not support none or minimal** and defaults to medium" | `none` 才是关闭，`minimal`/`low` 不是；可用性按**精确模型**；Responses 用 `reasoning.effort`、Chat Completions 用 `reasoning_effort`，字段位置不能混用 |
| MiMo | https://mimo.mi.com/docs/en-US/quick-start/usage-guide/text-generation/deep-thinking ；https://mimo.mi.com/docs/zh-CN/api/chat/anthropic-api | "Currently, the `mimo-v2.6-flash`、`mimo-v2.6-pro`、`mimo-v2.6-pro-ultraspeed`、`mimo-v2.5-pro` and `mimo-v2.5` models are supported"；"Set the `thinking.type` parameter… enabled / disabled"；"Enabled by default: mimo-v2.6-flash…"；"In deep thinking… `temperature` and `top_p`… forced to 1.0 and 0.95"；"When deep thinking is enabled… historical conversations contain tool calls, the assistant responses… must completely pass back the `reasoning_content` field, otherwise the API will return a 400 error"；Anthropic 兼容端点 `https://api.xiaomimimo.com/anthropic/v1/messages`，认证支持 `api-key: $MIMO_API_KEY` 或 Bearer | 开启/关闭都靠 `thinking.type`；OpenAI 兼容端走 JSON 顶层（SDK 用 `extra_body`），Anthropic 兼容端同样用 body 里的 `thinking` 对象；**现有 Anthropic 客户端发的是 `x-api-key`，与 MiMo 文档的 `api-key` 头不同，实现前必须核对**；开启态必须回传 `reasoning_content`，且不得发送 temperature/top_p |

三条通用结论：

1. 关闭方式由 **供应商 × 接口协议 × 精确模型**共同决定，缺一不可。
2. "不可关闭"与"未核实"必须区分展示，二者都不等于"已关闭"。
3. 能力表必须记录**核实日期与文档链接**，否则无法判断是否过期。

## 5. 代码事实（已核实，供三份 spec 引用）

行号说明：下表行号是 2026-10-19 工作区快照，只用于定位；代码移动后以符号名和文件为准。AI 策略文档在撰写时逐文件复核过行号，个别行与本表相差数行不影响结论。

| # | 结论 | 证据 |
| --- | --- | --- |
| 1 | 四个主页面 = 首页/档案/沟通/设置 | `ui/.../app/CallringApp.kt:52` |
| 2 | 全部页面共用 `ScreenFrame` 的 TopAppBar | `ui/.../designsystem/components/SharedComponents.kt:35-73` |
| 3 | 档案页正文含"按号码查看报表""通话录音"两个按钮 | `ui/.../feature/callers/CallerListScreen.kt:33-34` |
| 4 | 档案搜索只遍历已保存档案，无档案号码搜不到 | `backend/.../application/CallersService.kt:14-29` |
| 5 | 外部 `report` 深链对无档案号码回退到 `Lookup` | `ui/.../app/CallringActivity.kt:85` |
| 6 | 深链 target 含 `lookup`/`recording-archives` | `ui/.../app/CallringActivity.kt:116-140`、`ui/.../app/UiDestination.kt:7-35` |
| 7 | `checkNumber` 是同步接口，返回 normalized/validProfileNumber/matchesRule | `backend/.../application/api/CallringUiApi.kt:25,186-188` |
| 8 | 分页组件被档案页与号码报表页共用；内部 pageIndex 0 基、显示 +1 | `ui/.../feature/shared/FeatureFeedback.kt:32-36`、`CallerListScreen.kt:42`、`ReportScreen.kt:68` |
| 9 | 设置首页现有 6 张卡片，"录音与 AI"是其中一张 | `ui/.../feature/settings/SettingsOverviewScreen.kt:20-47` |
| 10 | AI 设置页是单列滚动、含重复长说明、预算在页面底部 | `ui/.../feature/ai/AiSettingsScreen.kt:28,33,34-40` |
| 11 | AI 页 VM 只加载 AI 视图；录音视图由另一个 VM 加载 | `ui/.../feature/ai/AiSettingsViewModel.kt:8-52`、`:54-83` |
| 12 | "录音就绪后自动分析"开关当前在录音设置页 | `ui/.../feature/recording/RecordingScreens.kt:76-77` |
| 13 | `patchCombined` 已存在（录音+AI 同事务） | `backend/.../application/api/AiSettingsApi.kt:24`、`app/.../platform/ai/AiSettingsService.kt:84-92` |
| 14 | 只有聊天客户端关闭思考，且仅 `QWEN_FAST` | `app/.../platform/ai/CompatibleConversationClient.kt:32` |
| 15 | 能力解析只认精确 `qwen3.8-flash` + dashscope 官方 host | `backend/.../application/api/ConversationCompatibility.kt:5-21` |
| 16 | 短文本/长文本分段/合并/分析连接测试均不注入任何关闭参数 | `app/.../platform/ai/CompatibleAiClient.kt:112-127`、`:81-105`、`:150` |
| 17 | 合同字段是全局单值、仅对分析连接生效；复制到分析会重置为 AUTO | `backend/.../application/api/AiSettingsApi.kt:16-24`、`AiSettingsService.kt:20,70-80` |
| 18 | 持久化的连接 JSON 只有 protocol/url/key/model（旧记录缺合同字段） | `app/.../platform/ai/AndroidRecordingStore.kt:202-203` |
| 19 | 冲突键/投影/会话连接变更检测三处需要同步新字段 | `BusinessScopes.kt:24-25`、`SafeProjection.kt:59,62`、`ConversationService.kt:70,215` |
| 20 | AI 工具入参 shape 与补丁解析需同步新字段 | `ToolRegistry.kt:25-26`、`ToolExecutor.kt:148,166,203-205` |
| 21 | 请求指标 `requestFlags` 只能装布尔，装不下对象/字符串参数 | `ConversationCompatibility.kt:26`、`CompatibleConversationClient.kt:142` |
| 22 | 回复含 `reasoning_content` 会硬失败 `ai.provider_mode_mismatch` | `CompatibleConversationClient.kt:91` |
| 23 | `AiClient` 端口三个方法只收 `AiConnection`，装不下策略快照；另有装饰器实现 | `RecordingPorts.kt:17-23`、`AndroidAudioTranscriber.kt:14-19` |
| 24 | 录音处理在批次开始时读一次 settings，转写/分析/合并共用 | `RecordingWorkflow.kt:29,42,73,89,94,100,104-114` |
| 25 | 版本信息硬编码在 build.gradle，无 signingConfigs | `app/build.gradle`（applicationId/minSdk 29/targetSdk 33/versionCode 2/versionName '0.2'/backendQa 后缀） |
| 26 | app 模块无任何 `androidx.*` 依赖，FileProvider 需显式新增依赖 | `app/build.gradle` 依赖块；`app/src` 无 `import androidx` |
| 27 | Manifest 无 `REQUEST_INSTALL_PACKAGES`、无 FileProvider | `app/src/main/AndroidManifest.xml` |
| 28 | 后端是 `java-library`，单元测试任务为 `:backend:test` | `backend/build.gradle` |
| 29 | 构建链：Gradle 8.13 / AGP 8.13.2 / Kotlin 2.3.21 / compileSdk 36 | `gradle/wrapper/gradle-wrapper.properties`、根 `build.gradle` |
| 30 | 渠道现状：无 CI 工作流、无发布脚本、Release 说明为手工填写 | §2 资产与仓库文件清单 |

## 6. 未知与不可核对项

1. **v0.2 发行 keystore 是否仍在维护者手中**：仓库无私钥、`app/build.gradle` 无 signingConfigs，本机密钥库不在核对范围。若丢失，已安装的 0.2 无法应用内覆盖升级，只能另立迁移方案。
2. **装机量**：`callring-0.2.apk` 下载次数为 1 是公开计数，不能据此推断真实安装量或用户数；只能作为"当前无规模用户压力"的弱依据。
3. **Play 政策原文**：`REQUEST_INSTALL_PACKAGES` 的上架限制未核对官方政策条款，任何"不能上架"的表述都需按政策原文复核。
4. **设备侧行为**：本轮未安装、未下载、未升级；所有下载/安装/覆盖升级结论均为设计目标，不是实测结果。
5. **厂商能力时效**：核对日期为 2026-10-19，模型或接口迭代后需重核；`qwen3.8-flash` 等精确模型标识的可用性也需在实施时复核。
6. **历史 Release/标签是否被删除**：仅能看到当前远端状态，无法判断是否曾删除过其它标签或 Release。
7. **GitHub 服务端 asset digest**：本轮未取得，摘要以 `SHA256SUMS.txt` 为唯一来源。

## 7. 复现方式

```powershell
# 仓库与 latest（必须带 UA，否则 403）
$h = @{ "User-Agent"="callring-review"; "Accept"="application/vnd.github+json" }
(Invoke-WebRequest "https://api.github.com/repos/lcsaff/callring" -Headers $h -UseBasicParsing).Content
(Invoke-WebRequest "https://api.github.com/repos/lcsaff/callring/releases/latest" -Headers $h -UseBasicParsing).Content
# 标签
git ls-remote --tags origin
# 摘要文本
(Invoke-WebRequest "https://github.com/lcsaff/callring/releases/download/v0.2/SHA256SUMS.txt" -Headers @{ "User-Agent"="callring-review" } -UseBasicParsing).Content
```

厂商文档：Qwen `help.aliyun.com/zh/model-studio/deep-thinking`、DeepSeek `api-docs.deepseek.com/guides/thinking_mode/`、Anthropic `platform.claude.com/docs/en/build-with-claude/thinking`、OpenAI `developers.openai.com/api/docs/guides/reasoning`。

## 8. 更新规则

- 本文任何数字被后续核对推翻时，先改本文件并在下方追加变更记录，再同步受影响的 spec。
- 发布新版本后，§2 的 Release 表需要整套更新（id/tag/published_at/资产/摘要），`/releases/latest` 必须重新核对。

变更记录：

- 2026-10-19 初版：核对仓库、Release v0.2、四家厂商关闭思考参数、30 条代码事实；修正证书指纹采集错误；确认 `make_latest` 语义与 403 成因。
- 2026-10-19 第 2 版：兼容范围收敛为 Qwen/DeepSeek/MiMo × OpenAI/Anthropic 协议；新增 MiMo 关闭思考参数、默认状态、temperature/top_p 强制值、`reasoning_content` 回传要求与 Anthropic 兼容端点/认证头事实；补充“设置页保存按钮与草稿态”代码事实。
