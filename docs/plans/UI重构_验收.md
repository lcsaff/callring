# UI 重构验收

日期：2026-10-15。范围：`refactor/ui` 上的新 UI 与正式业务 API 集成。本文记录迁移交付和验证边界；实施过程见 [UI 重构实施](UI重构_实施.md)，接口语义以 [API 契约 v1](UI业务API契约_v1.md) 及 `backend` 的生产声明为准。

## 交付范围

| 范围 | 交付内容 |
| --- | --- |
| 生产组装 | `CallringApplication` 单业务图，正式 API 注入；生产源码无 Fake、旧 UI 转发或并行旧业务实现 |
| 普通页面 | 首页、档案搜索/编辑、号码查询与报表、设置及免打扰/职位/工作安排/日历/请假/评分/铃声/运行条件/外观子页 |
| 页面状态 | Compose Screen 接收状态与回调；独立 ViewModel 调用主线程安全 API；查询序号、取消、草稿、字段错误与变更刷新 |
| 外观 | 联通红默认，天空蓝、青绿；任意 RGB/HEX；跟随系统/浅色/深色；评分色独立于主题 |
| 评分 | 80+ 绿、60–79 黄、低于 60 红、无记录中性；区分未知时长和真实零秒 |
| 悬浮窗 | 会话隔离，使用请求中的开始时间与当次决策；拖动、收起、编辑、白名单、报表、锁屏隐藏与结束不重弹 |
| 桌面组件 | Glance；小尺寸姓名/号码与评分，大尺寸增加部门/职位；报表、明确目标值白名单操作；大尺寸更换/刷新；按 widgetId 绑定 |
| 切换与数据 | 旧组件身份直接移除，用户需重新添加；保留数据库、原设置和导入音频 |

上述为实现范围。窗口、键盘、桌面宿主及系统电话行为须按下表独立验证，不因实现存在而视为通过。

## 可重复验证

```powershell
python tools/verify_ui.py
python tools/verify_ui.py --device ADB_SERIAL
python tools/verify_backend.py
python tools/verify_backend.py --device ADB_SERIAL
```

UI 验证脚本构建并执行 `:ui:testDebugUnitTest`、`:ui:assembleDebugAndroidTest`、`:ui:lintDebug`。单元测试计数采用与后端脚本相同的 XML 汇总字段：`tests`、`failures`、`errors`、`skipped`。构建失败、XML 缺失/空测试或存在失败均返回非零退出码。

共享设置和窗口用例同时由 Robolectric（SDK 35）与独立设备测试运行。`StateRestorationTester` 覆盖保存计数重置以及同一 ViewModel 在 composition 销毁期间保存完成；它模拟实例状态恢复，不等于真实杀进程。测试依赖和 Fake 均不进入 Release。

设备参数仅安装 UI 模块 AndroidTest APK。脚本同时核对 `output-metadata.json` 和 APK 实际包名 `com.greatwall.callring.ui.test`，核对安装后的 runner 与 target 都属于该包；使用 `com.greatwall.callring.ui.testing.UiTestRunner`，限定测试包前缀 `com.greatwall.callring.ui`。只有明确的 `OK (N tests)` 且没有失败标记才记为通过，ADB 返回 0 不等于测试通过。

执行前手动唤醒并解锁。脚本检查设备在线和锁屏状态，安装后等待 12 秒安全扫描，再允许测试包 `SYSTEM_ALERT_WINDOW`；MIUI/HyperOS 另临时允许 `10021`。安装前保存原 app-op 模式，最终恢复；首次安装按 `default` 恢复。不触及生产包与 Backend QA 包，不清除数据、不自动解锁、不修改锁屏超时。设备命令最多等待 180 秒，超时后尝试停止测试包并恢复模式；断线导致恢复失败时结果明确失败，恢复日志保留供人工处理。

UI 日志、构建结果与 `summary.json` 位于 `ui/build/qa/`，该目录被 `build/` 忽略规则覆盖。`summary.json` 每次写入本次状态；请结合对应日志与源码版本判断，避免把旧产物当成本次结果。后端与完整 App 构建证据由 `tools/verify_backend.py` 生成于 `app/build/qa/backend-refactor/`。

## 当前证据与待验项目

本表区分迁移期间已观察到的结果和最终验收；代码继续变化后需统一重跑，最终计数以本次脚本输出为准。

| 项目 | 当前证据或状态 | 验收要求 |
| --- | --- | --- |
| UI 单元测试 | 本轮完整运行 106 项通过，零失败/错误/跳过 | 日志 `ui/build/qa/summary.json`、`ui/build/qa/build.log` |
| Compose 设备测试 | 本轮独立 UI 包完整运行 35 项通过 | 日志 `ui/build/qa/device-tests.log`；包含实际 Window、桌面宿主与原色按钮像素检查 |
| 设置对话框状态恢复 | 14 项共享设置用例通过；三个计数重置回归和一个旋转期间完成回归均实际执行失败→修复→通过 | 覆盖草稿、首次成功关闭及单次提交；不等于真实杀进程验收 |
| 完整 App/Backend/UI 构建 | 本轮通过 Debug、Release、AndroidTest、Backend QA、App/UI Lint；Backend 66、App 66、UI 106 项单元测试均零失败/错误/跳过 | 摘要 `app/build/qa/backend-refactor/summary.json`，日志 `build/theme-brand-full.log` |
| 后端设备测试 | 本轮隔离 Backend QA 31 项通过，包含新增回归 | 证据 `app/build/qa/backend-refactor/device-tests.log`；与 UI 独立包分开运行 |
| 实际悬浮 Window | 真机显示、收起/展开、隐藏/重挂载、编辑焦点及已有草稿跨隐藏恢复通过；已核对截图 | 实际手势拖动边界仍待现场验收 |
| IME 与锁屏 | 编辑窗口已核对可聚焦标志；键盘遮挡和系统安全锁屏仍待现场验证 | 键盘避让、长表单滚动、保存/取消、锁屏隐藏及解锁草稿恢复；窗口 hide/show 不等于系统锁屏 |
| 桌面宿主双实例 | 大、小真实 AppWidgetHostView 分别绑定 100/200、显示 80/20；部门及附加操作仅在大尺寸显示；白名单只影响目标号码，自定义主题刷新与删除单实例保留另一绑定通过；配置保存/取消通过 | 报表/更换的真实宿主点击仍可现场补验 |
| 真实运营商 SIM | 待验证 | 真实来电展示、免打扰、白名单、铃声停止、结果与真实时长补全 |
| 30 分钟息屏与恢复 | 待验证 | 实际静置 30 分钟后的真实来电；记录电池策略、Doze 与联网/ADB 条件 |
| 重启后首次解锁 | 待验证 | 首次解锁后配置与后续真实来电恢复；不要求 Direct Boot 阶段读取完整配置 |

实际 Window 测试使用系统悬浮窗能力；普通 Compose 测试通过不能替代该项。组件模型和配置页测试也不能替代真实桌面宿主的双组件点击与绑定验收。

历史 [来电管理扩展验证](来电管理扩展_验证.md) 和其他旧 UI 验证文档仅保留背景证据，不计作本次新 UI 的最终结果。本轮维持 targetSdk 33；compileSdk 36 或 Android 16 测试不构成 targetSdk 36 行为认证。

本轮设备：小米 15 Pro，Android 16。两个验证脚本均退出 0；UI 包临时 app-op 已恢复。Release DEX 检查确认新页面、悬浮窗和组件入口存在，旧 UI/业务副本及 UI 测试夹具不存在；生产入口使用正式业务图。源码对应本验收文件所在提交，业务基线为 `e15e180`。

窗口回归同时复现并修正了 `ComposeView` 同名 `height` 遮蔽可用高度而导致初始高度为零的问题。使用明确的 `availableHeightPx`，保持系统窗口 `WRAP_CONTENT`，不保留手工测量或固定高度的诊断补丁。截图位于忽略目录 `ui/build/screenshots/`；测试源中的共享用例可重复生成。

主题已按用户后续确认修正：主按钮与原色预览保留原始 RGB，背景及通用容器中性，文字/图标独立适配明度。正红 `#E60012` 的深浅模式实机像素已核对；蓝、绿、黑、白也纳入相同回归，评分角色保持独立。完整记录见 [主题原色修正](UI主题原色修正.md)。

## 最终验收记录方式

统一重跑后，记录源码提交、时间、设备型号/系统、脚本退出码、单元与设备计数、日志路径及未通过项目。现场项目应逐项附实际操作与观察结果；未执行、设备断线、权限阻断和测试失败均保持待验或失败，不能以模拟或旧记录替代。
