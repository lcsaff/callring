# UI 重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 `refactor/ui` 中实现契约 v1 对应的 Kotlin Compose UI，统一管理设计系统、页面状态、系统交互及悬浮窗，并在联调完成后移除旧 UI。小组件最终字段在实现前由用户确认。

**Architecture:** 新 UI 放在 Android library 模块 `ui/`，通过 `CallringUiApi` 消费业务侧 `backend` 模块。生产声明只在业务模块维护。普通界面使用无存储依赖的 Compose Screen 与独立 ViewModel；设计系统、评分语义和主题注册表集中维护。

**Tech Stack:** JDK 17、Gradle 8.13、AGP 8.13.2、Kotlin 与 Compose compiler plugin 2.3.21、Compose BOM 2025.12.01、coroutines 1.10.2、compileSdk 36。minSdk 保持 29，targetSdk 保持 33，本轮不混入目标版本引起的电话及后台行为变更。

## 构建和协作

API 来源为业务分支的 `backend/src/main/kotlin/com/greatwall/callring/application/api/CallringUiApi.kt`。集成后 `:backend` 来自同仓库目录；并行期间通过明确的 `-Pcallring.backendDir=../backend/backend` 指向业务工作树，构建输出留在 UI 工作树，不复制契约，不修改业务方源文件。

UI 预设 ID 为 `unicom_red`、`sky_blue`、`teal_green`。默认外观为 `AppearanceSettings(ThemeSelection.PRESET, "unicom_red", null, ColorMode.FOLLOW_SYSTEM)`。UI Application 后续通过业务侧 `BackendProvider` 提供单个业务图，并注入通知路由与 `IncomingCallUiSink`。

## 任务 1 工具链与可编译边界

文件：根 `build.gradle`、`settings.gradle`、`gradle/wrapper/gradle-wrapper.properties`、`app/build.gradle`；新增 `ui/build.gradle` 和 `ui/src/main/AndroidManifest.xml`。

- [x] 锁定上述工具链，并建立 `:ui -> :backend` 依赖。`ui` 不依赖 `app`。
- [x] 添加 Compose、Lifecycle ViewModel、StateFlow、Material 3、协程测试和 JUnit 依赖。
- [x] 编译 UI 及业务 API 来源，运行原 69 项单元测试，保留原有行为基线。

验证命令：

```powershell
.\gradlew.bat -Pcallring.backendDir=../backend/backend :ui:testDebugUnitTest :ui:assembleDebug :app:testDebugUnitTest --console=plain
```

## 任务 2 设计系统与展示语义

文件：`ui/src/main/kotlin/com/greatwall/callring/ui/designsystem/`、`ui/src/main/kotlin/com/greatwall/callring/ui/format/`，测试放在 `ui/src/test/kotlin/` 对应包。

- [x] 先编写评分分界、未知时长与零秒、未知职位关系和主题配置测试，确认缺失行为导致失败。
- [x] 实现共享评分样式、格式化、三套预设、可扩展注册表和自定义主色生成。评分语义不随主题改变。
- [x] 实现 Material 3 Theme、标准间距和基础组件。未识别预设只显示兜底，不修改原保存 ID。
- [x] 执行对应单元测试和 `:ui:assembleDebug`，再进行契约与代码质量审查。

验证记录：2026-10-15，新模块 38 项单元测试通过，Debug AAR 与 AndroidTest APK 构建通过。设计系统已完成独立契约审查与代码质量审查。设备执行尚未进行，APK 构建不代表实机验收。

评分测试的固定用例：

```kotlin
assertEquals(ScoreBand.GREEN, scoreBand(80))
assertEquals(ScoreBand.YELLOW, scoreBand(79))
assertEquals(ScoreBand.YELLOW, scoreBand(60))
assertEquals(ScoreBand.RED, scoreBand(59))
assertEquals(ScoreBand.UNRATED, scoreBand(null))
```

## 任务 3 可独立测试的页面状态

文件：`ui/.../state/`，`ui/.../feature/home/`、`callers/`、`report/`、`settings/` 中的 ViewModel 和 UiState；Fake 仅在测试源集及设计预览中。

- [ ] 测试旧查询晚返回、取消不报错、错误字段映射、无记录、未知时长、真实零秒、权限缺失、保存失败和变更通知。
- [ ] 实现请求序号、领域和对象匹配、生命周期刷新与草稿分离。`RUNTIME` 相同修订号通知仍然有效。
- [ ] 档案未修改白名单时传 `whitelisted=null`；明确修改使用目标值。收到保存数据变化不覆盖草稿。
- [ ] 报表只展示返回的 totals、score、quality；筛选和分页只作用于 details。
- [ ] 外观通过正式 API 保存，不额外写 SharedPreferences。保存失败保留编辑值。

核心并发验收：先发 A 查询，再发 B，最后返回 A；显示值必须保持 B。调用被取消后不出现保存失败提示。此逻辑通过可控挂起 Fake 和协程虚拟时间验证。

## 任务 4 页面与导航

文件：`ui/.../app/`、`ui/.../feature/`、`ui/.../designsystem/components/`、`ui/src/main/res/values/strings.xml`。

- [ ] 实现首页、档案列表与编辑、号码报表、设置总览、免打扰、职位、工作安排、日历与日期调整、请假、评分、铃声、运行条件和外观页面。
- [ ] Screen 只接收状态与操作回调；系统授权、SAF 和跳转由宿主协调。
- [ ] 首页无 v1 未提供的今日统计；档案不伪造白名单或有记录筛选。
- [ ] 采用统一导航参数和语义定位，覆盖加载、空数据、错误、保存中和完成反馈。
- [ ] 使用 Compose 测试和界面截图核对主题、表单、列表及状态展示。

## 任务 5 悬浮窗与系统接入

文件：`ui/.../overlay/`、`app` 的新 Application 与 Manifest、UI 系统交互适配和对应设备测试。

- [ ] 实现任意线程可调用且快速返回的 `IncomingCallUiSink`。
- [ ] sessionId 隔离请求；使用开始时刻加载概览；当时决策来自请求。
- [ ] 拆开 Service、WindowManager 协调器、会话状态和 Compose 展示。
- [ ] 验证拖动边界、键盘、收起、锁屏隐藏、结束不重弹、草稿保存及过期请求丢弃。
- [ ] 生产入口注入业务图；不注入 Fake，不调用旧静态 UI 类。

## 任务 6 小组件和最终清理

小组件在用户确定显示字段与快捷操作之后实现；此前完成其主题、数据加载和系统入口边界，不把原型示例作为字段需求。

- [ ] 按确认字段实现 Glance、小组件配置和按 widgetId 隔离的操作。
- [ ] 删除设计文档列出的旧 Activity、Ui、悬浮窗及 Provider 实现、废弃资源和旧入口别名。
- [ ] 同步业务侧旧 UI 引用清理、Debug Manifest、QA 图、设备测试和脚本；不为旧测试保留旧字段或旧类。
- [ ] 完成 Debug、Release、AndroidTest、Lint、单元与隔离数据设备回归。
- [ ] 核查生产依赖图没有旧 UI、Fake、旧入口转发或双 UI 路由，再完成整体集成。

## 执行与检查点

按用户“继续”的授权连续执行，无需再次选择执行方式。独立任务使用子代理实现和审查；同一文件不交给多个实现者同时修改。每个已验证的边界形成 UI 分支提交，不修改业务工作树。若业务模块尚未完成某个接入点，先完成可独立验证的 UI 工作，明确记录联调条件。
