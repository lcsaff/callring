# 并行执行计划（P1 设置即时生效 / P2 思考模式 / P3 应用更新）

状态：执行编排，2026-10-19。本文不重复三份实施计划的步骤，只规定**哪些能并行、哪些必须串行、共享文件归谁、按什么顺序合并**。三份实施计划：

- P1：[设置即时生效与主界面](2026-10-19-settings-immediate-apply-plan.md)（对应 spec A）
- P2：[AI 思考模式（Qwen/DeepSeek/MiMo × OpenAI/Anthropic）](2026-10-19-thinking-mode-plan.md)（对应 spec B）
- P3：[应用更新与发布](2026-10-19-app-update-plan.md)（对应 spec C）

事实依据见 [D](2026-10-19-external-facts-verification.md)。

## 1. 结论

三条线**可以并行开工**，但不能"各派一人、最后直接合并"：

- **可并行**：P1 的布局/档案/分页/非 AI 设置页；P2 的能力矩阵、数据模型、迁移、两客户端、会话与工具层；P3 的版本来源、更新契约、平台实现、更新页、发布脚本。三者文件级互斥。
- **必须串行**：3 个收口点（AI 表单、设置入口、BackendGraph）与 4 个验证/阻塞点（keystore、录音端口、真实请求、设备覆盖升级）。
- **必须先做**：P2 的 backend 数据模型（`AiConnection.thinkingMode` 等纯增量字段）要先合入，作为另外两条线的编译基线。

## 2. 三条 track 与写作用域

| Track | 分支 | 主要写作用域（目录/文件） | 与其他 track 的交叉 |
| --- | --- | --- | --- |
| P1 | `feat/settings-immediate-apply` | `ui/.../app/CallringApp.kt`、`ui/.../designsystem/components/SharedComponents.kt`、`FormInteractions.kt`、`ui/.../feature/shared/FeatureFeedback.kt`、`feature/callers/*`、`feature/report/ReportScreen.kt`、`feature/settings/*`（除 SettingsOverview/SettingsRoute）、`feature/home/HomeScreen.kt`、`feature/conversation/ConversationScreen.kt`、`feature/ai/*`（收口）、`feature/recording/*`（收口）、ui 测试 | 与 P2：`AiSettingsScreen`/`AiSettingsViewModel`/`RecordingScreens`/`RecordingViewModels`/`UiFakeApi`；与 P3：`SettingsOverviewScreen`/`SettingsRoute` |
| P2 | `feat/thinking-mode` | `backend/.../application/api/{ThinkingPolicy,AiSettingsApi,RecordingApi,ConversationCompatibility}.kt`、`SafeProjection.kt`、`BusinessScopes.kt`、`conversation/{ConversationService,ToolRegistry,ToolExecutor,OperationScopePlanner}.kt`、`RecordingPorts.kt`、`RecordingWorkflow.kt`、`app/.../platform/ai/*`、`BackendGraph.kt`（收口） | 与 P1：AI/录音 UI 与测试替身；与 P3：`BackendGraph.kt` |
| P3 | `feat/app-update` | 新增 `backend/.../application/api/AppUpdateApi.kt`、`ui/.../feature/update/*`、`app/.../platform/update/*`、`app/src/main/AndroidManifest.xml`、`app/build.gradle`、`version.properties`、`app/src/main/res/xml/*`、`tools/*`、`.github/workflows/*`、`app/.../bootstrap/BackendGraph.kt`（收口） | 与 P1：设置首页入口行与路由；与 P2：`BackendGraph.kt` |

规则：**交叉文件在 Wave 1 内任何 track 都不得修改**，只在对应"收口提交"里由单一 owner 修改。

## 3. 依赖图与波次

```
G1 发行身份与备份 ─────────────┐（已定案：debug 证书；阻塞 P3 签名/发布/设备验收，不阻塞 P3 实现）
G2 录音端口 ───────────────────┤（已取消外部依赖：由 P2-S9 自行改造）
G3 MiMo/DeepSeek Anthropic 核对 ┘（已完成 2026-10-19：MiMo=api-key、DeepSeek=x-api-key）

Wave 1（并行 3 条）
  P1: 布局 → 档案/分页 → 拆卡骨架 → 非 AI 设置页即时生效 → 测试/文档
  P2: ThinkingPolicy → 数据模型+迁移 → 两客户端与快照 → 会话/工具层 → 测试
  P3: 版本来源 → 契约+平台实现 → GitHub 查询/元数据 → 下载安装 → 更新页 → 发布脚本

Wave 2（串行收口，单一 owner）
  M1 AI 表单 = P1 §7 结构 + P2 开关/错误码接线（含 AiSettingsViewModel 单字段 patch）
  M2 设置入口 = P1 拆卡 + P3 检查更新行/路由
  M3 BackendGraph = P2 客户端构造 + P3 更新 API 装配
  M4 共享测试替身 UiFakeApi = 三方字段合并

Wave 3（验证，按序）
  V1 全量验证命令 + 设备仪器测试
  V2 P3 签名/发布/latest 核对 + 设备覆盖升级（先装已发布 v0.2）
  V3 P2 两次真实请求 + MiMo/DeepSeek 开启态实测
  V4 P2 录音侧（等 G2 解封）
```

## 4. 步骤级并行标记约定

三份计划里每步都带标记。P2 用方括号写法；P1/P3 在步骤表的"并行/收口"列用中文写法，**语义完全相同**，等价关系：

- `[并行]`（P1/P3 写"可并行"）：Wave 1 内可与其他 track 同时做，文件不交叉。
- `[收口:M1/M2/M3/M4]`（P1/P3 写"需收口：与 X"）：必须等对方 track 的依赖步骤完成，由收口 owner 一次改完。
- `[gated:Gx]`（P1/P3 写"依赖/阻塞"）：外部事实或密钥未就绪，做到能做的部分后停下，不得标记完成。
- `[串行:Vx]`（P1/P3 写"不可并行"）：验证类步骤，必须在前述步骤全部合入后执行，不能与其他验证并行。

## 5. 合并顺序（硬约束）

1. **P2 数据模型先行**：`AiConnection.thinkingMode` + `AiSettingsApi` 视图/补丁字段（纯增量、带默认值）单独小提交，先合入集成分支。目的：让 P1/P3 在稳定基线上编译，避免反复 rebase。
2. Wave 1 三条线各自合并（P1 → P3 → P2 的其余部分；顺序可调，但每合一条都要跑 V1 子集）。**合并 P1 时先不合 §7 的 AI 表单完整版**（只合结构骨架），AI 表单留到 M1。
3. Wave 2 依次合 M1 → M2 → M3 → M4，每个收口提交单独跑全量验证。
4. Wave 3 按 V1→V2→V3→V4 执行；V4（P2-S9 录音侧验证）在 P2 端口改造完成后执行，不再等待 G2（已取消）。

## 6. 共享文件所有权表

| 文件 | Wave 1 owner | 收口 owner | 禁止事项 |
| --- | --- | --- | --- |
| `ui/.../feature/ai/AiSettingsScreen.kt` | 无（冻结） | M1：P1 owner 改结构与开关位置，P2 提供文案/错误码 | Wave 1 任何 track 不得提交该文件 |
| `ui/.../feature/ai/AiSettingsViewModel.kt` | 无（冻结） | M1：P2 owner 改单字段 patch，P1 提供双快照 | 同上 |
| `ui/.../feature/recording/RecordingScreens.kt`、`RecordingViewModels.kt` | 无（冻结） | M1 | 同上 |
| `ui/.../feature/settings/SettingsOverviewScreen.kt`、`ui/.../app/SettingsRoute.kt` | 无（冻结） | M2 | Wave 1 不得改 |
| `app/.../bootstrap/BackendGraph.kt` | 无（冻结） | M3 | Wave 1 不得改 |
| `ui/src/sharedTest/.../UiFakeApi.kt` | 无（冻结） | M4 | Wave 1 各 track 用各自临时 fake，不提交共享 fake |
| `backend/.../application/api/CallringUiApi.kt` | P3 | P3（M3 只加装配） | P1/P2 不得改 |
| `app/build.gradle`、`version.properties`、`.github/` | P3 | P3 | P1/P2 不得改 |
| `docs/` 下的 spec 与计划 | 无 | Lead | 实施过程中不改 spec；发现冲突写进实施记录 |

### 只读引用（Wave 1 任何 track 都不得修改，写入方见括号）

| 文件 | 谁在写 | 其他 track 只能读 |
| --- | --- | --- |
| `backend/.../application/api/AiSettingsApi.kt` | P2 | P1 只引用 `patchCombined`/`automaticAnalysis` 字段，不得改契约 |
| `ui/.../app/AiRoutes.kt` | P2（M1） | P1 不得改 |
| `app/build.gradle`（根 `build.gradle` 同） | P3 | P1 只引用 compose BOM/版本行作证据，不得改 |
| `ui/.../app/CallringApp.kt` | P1 | P3 不改（更新页走 `SettingsPage.UPDATE`，无需新路由） |
| `backend/.../application/api/CallringUiApi.kt` | P3 | P1/P2 只引用 `checkNumber`/`aiSettings` 模式，不得改 |
| `README.md`、`PROJECT.md` | P1 先改，P3 只补 `:backend:test` | P2 不改 |
| `docs/superpowers/**`（spec/计划） | Lead | 全部只读 |

规则：出现上表以外的重叠文件时，先在该表登记 owner 再动手；未登记即视为违规，合并前必须回退。

## 7. 每个波次的验证门

- Wave 1 每条 track 合并前：`.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug` 全绿，且本 track 新增测试通过。
- Wave 2 每个收口提交：同一命令全绿 + 对应收口验收项（M1 的 patch 断言、M2 的入口可见/隐藏、M3 的装配可启、M4 的 fake 覆盖）。
- Wave 3：V1 全量 + `:ui:connectedDebugAndroidTest`（设备可用时）；V2/V3 的证据按各自计划要求留档；任何一项缺证据不得写"已验收"。

## 8. 阻塞与回退

- **G1 备份失败/指纹不符**：P3 实现与本地校验照常合并，但签名/发布/覆盖升级标 blocked；不得改用新证书绕过（本机数据无法保留）。
- **G2 已取消**：录音端口由 P2-S9 自行改造，无外部等待，不存在"前置提交 SHA"。
- **G3 已核对（2026-10-19）**：MiMo 用 `api-key`（无 x-api-key）、DeepSeek 用 `x-api-key`；仅剩真实请求 smoke（P2-S11），401 时才回退为"仅 OpenAI 协议"。
- **某条 track 合并失败**：在集成分支回退该 track 的提交（`git revert`），不 force-push 已合并分支；另起修复提交。
- 三条 track 都改 `ui`/`backend`/`app` 三个模块，**任何一条不跑全量验证就合并，都会在别人合并时才暴露编译断裂**——这是本编排的第一红线。

## 9. 已定决策（跨计划约定）

1. **步骤引用格式**：三份计划都用 `Sxx` 编号，跨文件引用必须写 `P1-S06` / `P2-S7` / `P3-S10`，禁止只写 `S06`。
2. **更新页接入方式**：作为新增 `SettingsPage.UPDATE` 子页（枚举在 `SettingsOverviewScreen.kt`，`SettingsPage.entries` 自动生成路由），`SettingsRoute` 增 UPDATE 分支组装；不新增 `UiDestination`、不改 `CallringApp` 路由。M2 的共享文件就是 `SettingsOverviewScreen.kt` + `SettingsRoute.kt`。
3. **`testDraft`**：保留到 M1，M1 内改为 `testSlot` 并删除契约；在此之前不允许"草稿测试"与"即时生效"两套逻辑并存。
4. **`resolvedProfile`/`ProviderProfile` 删除**：推迟到 M1 收口后单独提交——`AiRoutes.kt:25` 与 `app/src/debug/.../RealAiConversationDeviceTest.kt` 仍引用；本轮只停止用它决定思考开关，不提前删字段。
5. **真实请求口径**：基线 2 条 Qwen（关闭态只读聊天 + 连接测试）；MiMo、DeepSeek 开启态各 1 条是标"已实测"的必要条件，最多 4 条。
6. **G3 未结论前**：Anthropic 的两个组合（DeepSeek×Anthropic、MiMo×Anthropic）按"可配置 + UI 提示待核对"处理，不进入默认可用集合；结论出来后只改 `ThinkingPolicy` 一处。
7. **spec 不改**：实施中发现的 spec 冲突写入实施记录，由收口 owner 汇总给 Lead 决定是否修订 spec。
8. **发行身份（已定案）**：v0.2 发行密钥已确认丢失（2026-10-19），改用本机 Android debug 证书（SHA-256 `5a901cad57f5590483f362e4752ca8f4a56197340b71d2632ded0195bf8a24b4`）作为当前发行身份；v0.2 不可覆盖升级、按无存量处理；备份 `debug.keystore` 与指纹留档是 P3-S01 第一门槛；切换正式证书需一次性卸载重装，另行评审。
9. **录音端口（已定案）**：由 P2-S9 自行改造 `AiClient`/`RecordingStore` 快照路径，取消原"前置提交 SHA"门禁与 blocked 占位。
10. **Anthropic 认证头（已核对）**：MiMo=`api-key`（或 Bearer，无 `x-api-key`），DeepSeek=`x-api-key`；由同一份厂商→认证头映射表驱动，禁止散落分支。

## 10. 完成定义（DoD）

- 三份计划的所有 `[并行]`、`[收口]` 步骤完成并留有验证输出；`[gated]` 步骤要么解封完成，要么在实施记录里列为 blocked 并说明解封条件。
- 全量验证命令全绿；设备验证按 V1–V4 留证；更新链路与真实请求有独立证据，不以 Debug 通过代替。
- README / 契约文档 / PROJECT.md 同步完成（P1 §文档同步、P3 §八）。
- spec 与实现冲突记录在实施记录的"冲突"一节，不静默改 spec。
