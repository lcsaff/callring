# UI 分支协作记录

日期：2026-10-15。分支 `refactor/ui`，工作树 `.worktrees/ui`，共同基线 `20771c4`。

## 所有权与接口

- UI 代码位于新的 `ui/` Android library 模块；模块依赖业务侧的纯 JVM `backend`，只消费 `application.api`，不复制生产声明。
- 新 Application 和新 UI 系统入口由 UI 侧接入业务 `BackendProvider`、`BackendGraph`；不编辑业务实现。
- 并行阶段曾使用模块位置覆盖；已集成业务检查点后移除该临时选项，当前只构建同仓库 `backend/`。
- 设备安装和实际 UI 测试在执行前核对设备状态，避免两个工作树互相覆盖安装。

## UI 主题交接

预设 ID：`unicom_red`、`sky_blue`、`teal_green`。默认配置：

```kotlin
AppearanceSettings(ThemeSelection.PRESET, "unicom_red", null, ColorMode.FOLLOW_SYSTEM)
```

自定义颜色由 UI 生成配套色板，通过 v1 API 持久化。未知 ID 使用红色显示兜底，保留原 ID。评分色由 UI 固定为 80–100 绿、60–79 黄、0–59 红，无记录中性。

## 工具链交接

采用 JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 和 Compose compiler plugin 2.3.21、Compose BOM 2025.12.01、coroutines 1.10.2、compileSdk 36。minSdk 29 和 targetSdk 33 保持原值。最终合并保留一套插件与协程版本。

选择依据：[AGP 8.13 官方兼容说明](https://developer.android.com/build/releases/agp-8-13-0-release-notes)、[Kotlin 版本支持](https://developer.android.com/build/kotlin-support)、[Compose compiler plugin](https://developer.android.com/develop/ui/compose/compiler)。后续验证结果按实际命令记录。

## 已确认约束

小组件字段和操作已确认：姓名/号码、部门/职位、当前评分；提供查看报表和明确目标值的白名单切换。小尺寸显示姓名/号码与评分，大尺寸补充部门/职位。主题和评分语义与页面一致。

UI 分支已快进集成业务侧已提交检查点 `e15e180`，采用同仓库 `backend/` 构建。生产 Application 负责单个 BackendGraph 和展示端口；整体交付必须删除旧 UI，不将中间状态当作最终迁移完成。

UI 迁移已完成生产接线和旧代码清理，保留在 `refactor/ui` 工作树。含后续主题原色修正的本轮全量构建、边界检查、238 项本地测试、35 项 UI 实机及 31 项 Backend QA 实机测试通过。详细证据及尚待现场验收的系统项目见 [UI 重构验收](UI重构_验收.md)。业务工作树不作修改，主分支集成另行处理。
