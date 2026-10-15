# UI 分支协作记录

日期：2026-10-15。分支 `refactor/ui`，工作树 `.worktrees/ui`，共同基线 `20771c4`。

## 所有权与接口

- UI 代码位于新的 `ui/` Android library 模块；模块依赖业务侧的纯 JVM `backend`，只消费 `application.api`，不复制生产声明。
- 新 Application 和新 UI 系统入口由 UI 侧接入业务 `BackendProvider`、`BackendGraph`；不编辑业务实现。
- 并行构建使用 `-Pcallring.backendDir=../backend/backend`；合并后使用同仓库 `backend/`，该参数只用于模块位置，不选择运行时后端。
- 外部业务模块的构建输出定向到 UI 工作树 `build/backend`，避免与业务侧构建产物相互覆盖。
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

小组件样式已确认，显示字段和快捷操作未确认，进入该部分实现前另行核定。此条件不阻止普通页面、主题、ViewModel 和悬浮窗开发。整体交付必须删除旧 UI，不将中间状态当作最终迁移完成。
