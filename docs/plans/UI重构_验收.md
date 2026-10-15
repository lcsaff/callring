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
| UI 单元测试 | 主分支最终完整运行 111 项通过，零失败/错误/跳过 | 日志 `ui/build/qa/summary.json`、`ui/build/qa/build.log` |
| Compose 设备测试 | 主分支独立 UI 包完整运行 39 项通过 | 日志 `ui/build/qa/device-tests.log`；包含实际 Window、桌面宿主、原色按钮及新增 4 项共享回归 |
| 设置对话框状态恢复 | 18 项共享设置用例通过；三个计数重置回归和一个旋转期间完成回归均实际执行失败→修复→通过，新增默认设置重置及权限恢复入口回归也已实机通过 | 覆盖草稿、首次成功关闭及单次提交；不等于真实杀进程验收 |
| 完整 App/Backend/UI 构建 | 主分支最终通过 Debug、Release、AndroidTest、Backend QA、App/UI Lint；Backend 66、App 66、UI 111 项单元测试均零失败/错误/跳过 | 摘要 `app/build/qa/backend-refactor/summary.json`，日志 `app/build/qa/backend-refactor/build.log` |
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

主分支集成补充（2026-10-15）：UI `89382a9` 与审查修复 `4260d1f` 已合入 `main`。增加权限设置恢复入口、权限授予后恢复同步、默认设置清理无效输入；5 个回归中先观察到 4 个预期失败，修复后核心 66 / app 66 / UI 111 项本地测试及完整构建、Lint、严格边界检查通过，并在主工作区复验。用户恢复无线 ADB 后，在主工作区 `2580ce7` 再次执行两个设备验证脚本，均退出 0：Backend QA 31 项、UI 39 项通过，其中含新增的 4 项共享回归。外部权限授予后 resume 的 Activity 行为由新增 Robolectric 用例验证。UI 测试包的悬浮窗及 HyperOS `10021` app-op 已恢复为测试前的 `default`，cleanup 无错误。仍未执行的现场项目保持上表待验状态。

统一重跑后，记录源码提交、时间、设备型号/系统、脚本退出码、单元与设备计数、日志路径及未通过项目。现场项目应逐项附实际操作与观察结果；未执行、设备断线、权限阻断和测试失败均保持待验或失败，不能以模拟或旧记录替代。

## 官方交互与紧凑布局改造（2026-10-15）

本轮按用户确认覆盖顶部应用栏、日期/区间/时间选择、可增删的工作与免打扰时段、职位菜单、表单键盘操作、未保存草稿退出、删除/重置确认、操作进度与 Snackbar、预测返回接入、自适应导航和统一紧凑间距。没有增加无障碍专项工作，原有主题原色与评分语义保留。

页面间距收紧为页边距 16dp、常用间隔 8dp、设置卡片内边距 12dp、设置行最小高度 56dp；输入框仅在存在错误时预留错误提示槽。标准 Material 控件触摸区域未缩减。窗口变宽后切换为侧边导航，内容最大宽度为 840dp；变回窄窗口保留已选页面。

新增回归覆盖 Snackbar 单次消费、状态恢复后不重播、保存后退出仍可见、保存失败保留草稿、整行开关、删除/重置取消不写入、日期 UTC 编码、报表业务时区、时间选择取消及恢复、相同起止时间校验、跨午夜说明、夏令时缺失时刻拒绝及原 epoch 毫秒保留。时间字符串采用 Locale.ROOT，避免地区数字格式破坏 ISO 时间解析。

最终验证：分支 `feat/android-ui-interactions`，源码为本段所在提交。`python tools/verify_ui.py` 退出 0，UI 单元测试 **128 项，零失败/错误/跳过**，AndroidTest APK 和 UI lint 通过；`:app:assembleDebug` 退出 0。完整隔离设备运行 **55 项通过**，随后对新增的真实悬浮窗路径单独复验 **1 项通过**，不是新增独立的第 56 项。最终本地摘要为 `ui/build/qa/summary.json`，完整设备摘要快照为 `ui/build/qa/full-device-summary.json`、设备日志为 `ui/build/qa/device-tests.log`；悬浮窗补验日志和摘要位于 `ui/build/qa/overlay-confirmation/`。两次设备运行的悬浮窗及 HyperOS app-op 均恢复到原来的 default，cleanup 无错误。

悬浮窗补验实际复现了服务 Context 打开普通 Compose Dialog 时的 BadTokenException，随后改用官方 MaterialAlertDialogBuilder 与公开 Material 3 对话框主题，在显示前设置悬浮窗口类型。补验覆盖实际职位下拉菜单、取消退出提示保留草稿、确认放弃后退出编辑；同一共享用例也已通过最终 Robolectric 全量运行。截图已检查首页、设置、档案表单等间距，位于 `ui/build/screenshots/android-interactions/`。规格审查和代码质量审查均已通过。

真实系统返回手势的逐帧动效、厂商键盘的所有高度组合和系统锁屏仍需现场观察；预测返回接入与窗口尺寸切换的自动测试不替代这些人工项目。上文真实 SIM、长时间息屏和重启项目继续按原范围记录。

## 桌面快捷操作、即时外观与悬浮窗检查（2026-10-15）

本轮分支 `feat/widget-controls-live-appearance`；需求见 [专项设计](../superpowers/specs/2026-10-15-widget-live-appearance-design.md)，取代上文旧小部件的号码绑定方案。小部件只展示全局免打扰开关与当天请假快捷操作；添加组件无需配置号码，也不提供档案、评分、报告或请假详情。请假按回调触发时设备日期和时区创建当天全天记录，不启动 Activity。读取失败禁止盲写，多实例和旧 RemoteViews 的重复回调通过串行读取去重；旧“今日已请假”仍保留安全回调，避免系统尚未刷新跨日画面时阻止当天操作。

免打扰使用 Glance 原生 Switch 及显式目标状态，只切换应用免打扰模式，不修改原时段、适用号码范围；关闭前记住有效模式，重新开启恢复。偏好读取与 API 写入在同一锁内，已用挂起读操作复现并修复快速 OFF→ON 恢复错模式的竞争。另用挂起跨午夜回归验证日期在排队前捕获。全天边界以相邻日期本地零点计算，覆盖 25 小时夏令时日。

外观页采用根级 ViewModel 即时更新应用主题并串行自动保存；RGB 滑块 250ms 合并写入，新的选择不会被旧结果覆盖，失败保留当前效果并原位重试。预设主题横排，显示模式紧随其后，自定义颜色与专属预览合并，删除保存按钮和离开确认。

“运行状态与权限”新增无历史记录依赖的“测试悬浮窗”。未授权时进入官方系统权限页；已授权时启动只读测试会话，不创建来电、档案、白名单或铃声。界面说明当前窗口显示与后台来电显示的验证边界，并为 MIUI/HyperOS 提示独立的后台弹出限制。

本地 UI 全量 **144 项测试通过，零失败/错误/跳过**；UI Lint、测试 APK、正式 Debug APK 及 App Lint 通过。严格后端边界检查通过，生产 API 契约未变。独立审查发现的两项日期边界及免打扰竞争均已修正。

最终 `python tools/verify_ui.py --device 192.168.10.149:5555` 退出 0，完整隔离设备 **61 项通过**，含真实双尺寸全局组件和新悬浮窗服务。此前两项重点路径单独运行 **2 项通过**，为相同用例补验，不额外累计。完整摘要/日志位于 `ui/build/qa/summary.json`、`device-tests.log`，补验位于 `ui/build/qa/widget-overlay/`；两次均恢复测试包 `SYSTEM_ALERT_WINDOW` 和 HyperOS `10021` 到原始 default，cleanup 无错误。首次实机运行暴露测试宿主对前台 Activity 和 CompoundButton 点击返回值的错误假设，已修正为验证真实可见窗口、实际关闭动作、持久化 API 结果与两实例状态；首次失败日志保留在 `ui/build/qa/controls-first-device/`。

已核对 `ui/build/screenshots/widget-live-appearance/` 中外观页、双尺寸组件、自定义主题与真实服务窗口截图。自测窗口可见测试标题和解释，没有编辑/白名单/报表入口，并验证点击关闭后物理系统窗口移除。自动化检查使用测试框架的标准节点查询，没有增加应用无障碍功能。

设备排查确认：小米 15 Pro / Android 16 / HyperOS 上没有安装 `com.greatwall.callring`；已安装 `com.greatwall.callring.backendqa` 的来电显示 sink 按 QA 设计为空实现，其标准悬浮窗权限允许，但后台弹出 app-op `10021` 为 ignore。该 QA 包真实来电不显示不能直接认定为生产窗口故障。未安装正式包、未改变 QA 包权限或数据；真实 SIM 后台来电和系统锁屏项目仍待现场验证。

实现参考官方 [Glance 交互](https://developer.android.com/develop/ui/compose/glance/user-interaction)。跨日处理不依赖清单中的 DATE_CHANGED 必达，依据官方 [隐式广播例外](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)保留安全的即时计算操作。
