# 并行开发计划（4 条并行流）

状态：执行编排，2026-10-19。将 [P1 设置即时生效](2026-10-19-settings-immediate-apply-plan.md)、[P2 思考模式](2026-10-19-thinking-mode-plan.md)、[P3 更新发布](2026-10-19-app-update-plan.md) 重组为**按层划分的 4 条并行流**，同类工作合并成工作包，取消原 M1–M4 收口。

## 1. 目标与原则

1. **按层并行**：每条流独占一个顶层目录，文件零重叠，任何一条流不需要等另一条流的文件解冻。
2. **接口先行**：层间只通过 backend API 交接，且只允许增量（新字段带默认值、新工具参数可选），因此各层可独立编译。
3. **AI 工具调用 API 只扩展**：工具数量/编号/入参/返回只增不改（见 B §15），本计划任何工作包不得删除或重命名工具字段。
4. **同类合并**：同一模式的重复工作（8 个设置页去保存、5 个厂商×协议矩阵、5 个更新状态链）各合并成一个工作包，一次做完、一次验收。

## 2. 四条并行流

| 流 | 分支 | 写作用域（独占） | 可立即开工 | 依赖 |
| --- | --- | --- | --- | --- |
| S1 后端契约与策略 | `feat/backend-contracts` | `backend/src/main/kotlin/**`、`backend/src/test/kotlin/**` | 是 | 无 |
| S2 平台实现 | `feat/app-platform` | `app/src/main/kotlin/**`、`app/src/test/kotlin/**` | 需 S1 契约类型落地 | S1（仅类型） |
| S3 UI | `feat/ui` | `ui/src/main/kotlin/**`、`ui/src/test/**`、`ui/src/androidTest/**`、`ui/src/sharedTest/**` | 部分立即（S3-A/B/C） | S1（AI/更新字段）、S4-0（依赖与 Provider） |
| S4 构建与发布 | `feat/packaging` | 根 `build.gradle`、`app/build.gradle`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/**`、`version.properties`、`tools/**`、`.github/**`、`README.md`、`PROJECT.md` | 是 | 无（签名验收除外） |

`app/src/main/res/xml/**` 与 Manifest 归 S4：S2/S3 只消费 FileProvider authority 常量，不写这些文件。

## 3. 合并后的工作包

| 包 | 内容（原步骤） | 流 | 一次验收 |
| --- | --- | --- | --- |
| S1-A 思考策略契约 | `ThinkingPolicy` 矩阵+解析+快照+数据模型+迁移+投影/冲突键/指纹/工具层（P2 S1–S4） | S1 | `:backend:test`，矩阵 5 组合 + 旧 `compatibility` 兼容用例 |
| S1-B 更新契约 | `AppUpdateApi` + `CallringUiApi` 默认实现（P3 S03 后端部分） | S1 | `:backend:test`，默认实现返回失败不崩溃 |
| S2-A AI 客户端 | 聊天/分析客户端按快照下发开关参数、开启态回传、认证头映射（P2 S5–S6） | S2 | `:app:testDebugUnitTest` 客户端测试 |
| S2-B 录音端口与批次 | `AiClient`/`RecordingStore` 快照签名、批次快照、装饰器（P2 S9） | S2 | `:backend:test --tests *RecordingWorkflow*` |
| S2-C 更新平台 | GitHub 检查/元数据/下载/校验/安装 + `BackendGraph` 装配（P3 S04–S08 + S03 装配） | S2 | app 更新单测矩阵 |
| S3-A 布局与导航 | 自定义底部导航、`compactTitle`/`expandedHeight`（P1 S01–S02） | S3 | 导航/标题仪器测试 |
| S3-B 档案与分页 | 档案入口、号码报表动作行、单页隐藏分页（P1 S03–S05） | S3 | 档案/报表测试 |
| S3-C 设置即时生效 | 8 个设置页去保存/去草稿、逐字段提交、防抖/冲突（P1 S07–S12） | S3 | 设置页测试 + 手工改值即生效 |
| S3-D 设置首页与更新页 | 拆卡 + 检查更新行 + `SettingsPage.UPDATE` + 更新页 14 状态（P1 S06 + P3 S09/S10） | S3 | 入口可见/QA 隐藏、更新页状态机 |
| S3-E AI 表单与开关 | 三组/折叠错误/自动分析迁移/双快照/唯一思考开关（P1 S13 + P2 S7） | S3 | patch 断言 + 开关文案 |
| S3-F 测试与文档同步 | 各页测试更新、README/契约/PROJECT 同步（P1 S14 + P3 S12 文档部分） | S3/S4 | 全量命令 + grep 无旧文案 |
| S4-0 依赖与清单 | `androidx.core` 依赖、Manifest 权限、FileProvider 路径 XML、版本读取（P3 S02 依赖部分） | S4 | `:app:assembleDebug` 编译通过 |
| S4-1 版本与签名 | `version.properties`、signingConfigs（debug 证书）、QA 隔离（P3 S01/S02） | S4 | 签名包指纹 = `5a901cad…24b4` |
| S4-2 发布工具与 CI | `tools/` 校验脚本、`.github/workflows/` 失败即停（P3 S11） | S4 | 样例失败项全部非零退出 |
| S4-3 发货验收 | 单测矩阵、发布演练、真机同证书覆盖升级（P3 S12/S13） | S4 | 证据齐全（见 P3 §7） |

独立前置动作：**备份 `debug.keystore` 并记录指纹**（P3-S01），只阻塞 S4-1/S4-3，不阻塞 S1/S2/S3。

## 4. 依赖与波次

```
S4-0 依赖与清单 ┐
S1 契约 ────────┼─→ S2 平台 ─→（编译集成）
                └─→ S3-D/E（读契约）
S3-A/B/C 无依赖，第一波即可开始
S4-1/S4-2 无依赖；S4-3 等 S1–S3 合入
```

| 波次 | 内容 | 出口条件 |
| --- | --- | --- |
| W1 | S1 全部、S2-A、S3-A/B/C、S4-0/S4-1/S4-2 并行 | 各流独立编译+本流测试通过 |
| W2 | S2-C、S3-D/E（契约就绪后）、S1-B 合入 | 层序编译：backend → app → ui 全绿 |
| W3 | 集成验证：全量命令 + 仪器测试 | 见 §5 |
| W4 | 设备覆盖升级、真实请求、发布演练（不可并行） | 见 P2/P3 验收清单 |

层序只影响 W2 的合并顺序，不影响 W1 的开工。

## 5. 验证门

| 门 | 命令/内容 | 时点 |
| --- | --- | --- |
| G-流 | 各流自己的 `--tests` 过滤 | 每个工作包完成时 |
| G-集成 | `.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug` | W3 |
| G-仪器 | `:ui:connectedDebugAndroidTest`（设备可用时） | W3 |
| G-升级 | 同 debug 证书 0.3.0→0.3.1 覆盖安装、数据保留 | W4 |
| G-发布 | 签名/摘要/latest 核对、两次 Qwen 真实请求、MiMo/DeepSeek 开启态各一条 | W4 |

## 6. 文件所有权（按层，零收口）

| 路径 | 唯一 owner | 其他流 |
| --- | --- | --- |
| `backend/src/**` | S1 | 只读 |
| `app/src/main/kotlin/**`、`app/src/test/kotlin/**` | S2 | 只读 |
| `ui/src/**` | S3 | 只读 |
| 根 `build.gradle`、`app/build.gradle`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/**`、`version.properties`、`tools/**`、`.github/**`、`README.md`、`PROJECT.md` | S4 | 只读 |
| `docs/superpowers/**` | 只在实施结束后由收尾提交更新 | 实施期间只读 |

规则：任何跨流改动都必须由目标路径的 owner 提交；发现未登记的重叠即回退该提交。由于按层划分，文件级重叠为 0，因此不再有"M1–M4 收口"。

## 7. 项目满足性核对

| 要求 | 现状证据 | 结论 |
| --- | --- | --- |
| 4 条流可独立开工 | `backend`（java-library）、`ui`（Android library）、`app`（application）、根构建/`tools/` 已分目录；无跨模块源码引用循环 | 满足 |
| 文件级零重叠 | 按层划分后，每流写作用域为互不相交的目录/文件集合（§6） | 满足 |
| 层间接口只增量 | 现有 `CallringUiApi` 默认实现模式 + 新增字段带默认值；AI 工具只扩展（B §15） | 满足 |
| 每条流可独立验收 | `:backend:test`、`:ui:testDebugUnitTest`、`:app:testDebugUnitTest`、lint、`assembleDebug` 均为现成入口 | 满足 |
| 同类工作可合并 | 设置页统一走 `SaveAction`/`rememberFormBack`；5 个厂商×协议组合同一矩阵；14 状态同一状态机 | 满足 |
| 构建链可复现 | Gradle 8.13 / AGP 8.13.2 / Kotlin 2.3.21 / compileSdk 36 已固定；JDK 17 在 `JAVA_HOME` | 满足 |
| 缺口（计划内解决） | `.github/` 不存在、`signingConfigs` 未配置、`androidx.core` 未引入、`version.properties` 未建 | S4-0/S4-1/S4-2 负责，不阻塞 S1/S2/S3 |

## 8. 风险与回退

- **S1 契约返工**：S2/S3 依赖新增字段；因只增量且带默认值，返工只影响新增字段本身，不破坏旧调用。
- **S3 内部文件交叉**：S3 是一个 owner，内部按 S3-A→B→C→D→E 顺序提交即可，无需跨流协调。
- **签名身份**：debug 证书机器绑定；备份与指纹留档是 S4-1 门槛，失败则 W4 的签名/发布/设备验收停止，不得改用新证书。
- **合并冲突**：只在"docs/superpowers/** 收尾提交"与 Owner 提交间可能出现，按 §6 规则处理；出现未登记重叠即 `git revert` 该提交，不 force-push。

## 9. 完成定义

- S1–S4 的所有工作包完成并各有验证输出；W3 全量命令与仪器测试全绿。
- AI 工具调用 API 的集合/编号/入参/返回与改造前一致（只增字段），`ToolOperationCoverageTest` 通过。
- W4 的证据齐全：签名指纹、APK 摘要、latest tag、覆盖升级前后数据、真实请求记录；未完成项单列。
- 实施期间 spec 不变；结束时的收尾提交只做文档状态更新。
