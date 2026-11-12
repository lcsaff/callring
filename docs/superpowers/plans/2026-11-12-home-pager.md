# 四页主页 Pager Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 完成已确认的四页主页滑动、状态恢复、手势保护和验证交付。

**Architecture:** 根 home 导航目的地持有 Pager，应用宿主保留各页 ViewModel；实际访问才创建业务模型。PagerState 统一页签与滑动，页面用稳定键保存 UI 状态，次级导航保持原状。

**Tech Stack:** Kotlin、Compose Foundation Pager、Navigation Compose、SavedStateHandle、Robolectric、隔离 Android instrumentation。

## Task 1：回归与主页容器

Files: `ui/src/test/kotlin/com/greatwall/callring/ui/app/HomePagerTest.kt`、`ui/src/main/kotlin/com/greatwall/callring/ui/app/HomePager.kt`、`CallringApp.kt`、`SettingsRoute.kt`。

- [x] 增加冷启动查询计数测试：`assertEquals(0, searches); assertEquals(0, summaries)`；`performTouchInput { swipeLeft() }` 后选中档案，再次滑动选中沟通；跨页点击不加载中间页。
- [x] 执行 `./gradlew.bat :ui:testDebugUnitTest --tests '*HomePagerTest' --console=plain`，确认旧实现缺少 Pager，且设置摘要提前查询导致失败。
- [x] 以单一 PagerState 创建主页容器：`rememberPagerState(pageCount = { 4 })`，配置 `PagerSnapDistance.atMost(1)`；tab 调用 `scrollToPage(index)`；稳定后才标记访问。
- [x] 将页面 ViewModel 放在稳定宿主、按访问创建；用 `rememberSaveableStateHolder().SaveableStateProvider(pageKey)` 保存页面滚动；只有可见首页启动 TimeRefresh。
- [x] SettingsSummaryViewModel 按需共享获取；SettingsRoute 只有 STORAGE 消费摘要时获取；外部主页目标映射到 Pager，次级路由继续使用 NavHost。
- [x] 增加非首页返回首页、详情返回原页及重复切页 loader 不增加的测试，再运行上述命令。

## Task 2：沟通草稿和阅读状态

Files: `ui/src/main/kotlin/com/greatwall/callring/ui/feature/conversation/ConversationViewModel.kt`、`ConversationScreen.kt`、`ui/app/AiRoutes.kt`、`ui/src/test/kotlin/com/greatwall/callring/ui/app/ConversationDraftTest.kt`。

- [x] 使用独立 SavedStateHandle 构造模型并验证：`assertEquals("草稿", restored.input.value)`；独立宿主的新 handle 不共享草稿。
- [x] 执行 `./gradlew.bat :ui:testDebugUnitTest --tests '*ConversationDraftTest' --console=plain`，观察恢复和隔离用例失败。
- [x] 删除全局 processConversationInput，改为 `savedState.getMutableStateFlow("input", "")`；保留公开输入流形状、send compareAndSet 保护和正式 API，不保存聊天历史或重放任务。
- [x] 将 readingHistory 改为按 sessionId 保存：`rememberSaveable(state.sessionId) { mutableStateOf(false) }`；新对话弹窗上报给主页手势门。
- [x] 沟通 Route 接收稳定宿主模型；回复请求保持 ViewModel scope，不在页面退出时 stop。
- [x] 再运行草稿测试、沟通相关测试，补延迟回复切页和阅读位置恢复回归。

## Task 3：手势和恢复验证

Files: `ui/src/main/kotlin/com/greatwall/callring/ui/designsystem/components/PagerGestures.kt`、主页及沟通/档案组件、`ui/src/test/kotlin/com/greatwall/callring/ui/app/HomePagerTest.kt`、`ui/src/androidTest/kotlin/com/greatwall/callring/ui/app/HomePagerDeviceTest.kt`。

- [x] 增加输入框横拖不切页、竖向滚动不切页、弹窗暂停、保存状态恢复用例，先运行并确认失败。
- [x] 提供局部手势保护与阻断计数；子区域在初始触摸阶段声明持有手势，不消费子控件事件；释放/取消恢复 Pager。
- [x] 使用系统手势 inset 让出边缘返回；弹窗出现取消在途翻页并稳定到已确认页，暂停背景页签。
- [x] 档案显式保留 LazyListState；离开当前页面清理焦点，保留文字；复核宽窄布局使用相同 Pager 宿主，防止旋转重建查询。
- [x] 运行 `./gradlew.bat :ui:testDebugUnitTest --console=plain`，修复并复验发现的问题。
- [x] 独立审查设计符合性，通过后独立审查代码质量；修复审查问题并复验。

## Task 4：验证和交付

Files: `PROJECT.md`、`README.md`、本计划的实际证据记录。

- [x] 同步两份规范/用户文档，标明进程内 AI 继续、进程恢复仅恢复 UI 草稿的边界；记录真实验收范围。
- [x] `git diff --check` 并按任务文件提交；不得包含原工作区其他文档和临时文件改动。
- [x] `python tools/verify_ui.py --device emulator-5556`（与 verify_backend 相同完整门），检查 summary 为 passed、全部门任务与测试计数正确。
- [x] `adb devices -l`；可用隔离设备手动解锁后执行 `python tools/verify_ui.py --device SERIAL`，执行 Pager 专项并保留日志；不能取得设备时记录外部阻塞，不跳过合并前设备验收。
交付约束：具备完整验证证据后快进合入 main、推送，并读回最终 SHA 的远端 CI；远端结果在最终交付回复中记录。

## 执行记录

- 设计经用户于 2026-11-12 确认。
- 使用当前 `codex/home-pager` 分支，保留已有不相关改动；按开发工具默认复用当前 checkout，不另建工作树。

- RED：旧实现 HomePagerTest 5 项中 2 项失败（缺容器、设置摘要提前查询）；Conversation 草稿独立及历史恢复 7 项中 3 项失败；held-drag restore 在默认 saver 下失败，保存稳定页后通过。
- 定向 HomePager/Conversation 49 项通过；中断专项 2 项通过。Markdown 回复使用正式解析结果出现作为就绪条件，避免将后台解析未完成当作回复丢失。
- 独立需求审查和沟通代码审查通过；Pager 审查指出的半途拖动恢复、根页返回可见性及弹窗取消顺序已修正并加回归。
- 隔离模拟器 emulator-5556 上手势/输入/后台回复专项 2 项通过，横竖屏、宿主 recreate、返回及草稿保留 1 项通过。旋转验证使用实时无障碍节点，避免 Compose 测试框架等待已销毁窗口的帧；原失败和修正日志留在 ui/build。

- 已合入 origin/main `feb8f1c`，保留远端通话记录、字段编辑和同选择导航复用；合并后的 UI JVM 401 项通过。
- 新增外部目标在弹窗期间延后处理回归，及无显式目标的任务唤起不重新启动导航加载屏障回归；显式 Home 仍生效。
- 子控件慢速横拖的触摸阈值回归先失败再通过；保护只在累计横移越过阈值后消费，避免取消子控件手势。设备普通翻页使用中间区域，边缘专项单独验证；窗口 inset 与正式入口的 enableEdgeToEdge 对齐。
- 实际进程恢复：隔离任务后台保存后仅对测试 UID 发 SIGKILL，再唤起原任务；PID 5591→5895 时页签、草稿及此前未激活的档案搜索读回通过。后续最终源码仍复验，日志在 ui/build/qa/home-pager-process/。

- 最终实现源码 `d58de25f953f64d5f7a4502ac060d1c5f0e8cc6b`：统一门 1,352 项 JVM（469/401/482）、71 项 Python 检查、双 lint 及构建通过；隔离 UI 全套 89 项通过并清理。原始摘要 ui/build/qa/summary.json，均按本轮执行/缓存状态记录。
- 同源码真实测试进程 PID 8526→8823：页签、草稿、搜索恢复；左右边缘不带动 Pager、系统返回按键回首页通过。installed_apk_matches=true，摘要保存在 ui/build/qa/home-pager-process/summary.json。原生系统边缘处理器在该模拟器未启用，所以不声称实际边缘返回通过；无需修改系统设置。
- 原工作区计划的六条新增内容已在合入后的文档中逐条核对保留，其他临时文件不纳入提交。后续文档收尾不改业务源码。
