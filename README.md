# 规则来电铃声与来电管理

独立 Android 应用，包名 `com.greatwall.callring`，版本 **0.2**。号码规则沿用 `3[0-9]{4}`，例如 `31234`；接听仍使用手机原有电话应用。

## 使用

1. 在主界面授权电话状态和通知，并设为“来电识别应用”。开启规则铃声可为规则号码播放自选音频，支持试听和恢复内置旋律。
2. 打开“号码档案与来电报表”，填写号码、姓名、部门和职位。职位从管理设置中的预配置选项选择；配置“我的当前职位”后显示 **↑ 高于我、↓ 低于我、＝ 同级、— 未设置**。层级数字越小职位越高，不同职位允许同级。
3. 在管理设置开启来电统计，设置每周工作时段、调休补班时段、时区、个人请假和日期调整。统计只记录启用区间内的规则号码；暂停保留历史。
4. 选择免打扰模式：关闭、全部非工作时间静音、自定义非工作时间静音。自定义模式只在“非工作时间且命中静音时段”时生效；默认只处理规则号码，可扩大至系统交付的全部可筛选来电。
5. 白名单始终绕过应用免打扰，仍遵守手机自己的静音、振动、系统勿扰和音量设置。关闭规则铃声不会关闭统计或免打扰。
6. 授予悬浮窗权限后开启来电悬浮窗。卡片展示档案、职位比较、评分、次数、工作分类和来电决策，可拖动、收起、编辑姓名/部门/职位、切换白名单、查看报表。主界面可主动显示最近来电卡片。
7. 从主界面或桌面长按添加小组件。每个组件绑定一个号码，显示档案、职位比较、评分、次数、最近来电和当前工作状态，提供编辑、报表、白名单、全局免打扰和刷新。

快捷操作更新同一份持久化设置。小组件的文字编辑打开应用编辑页；标准桌面组件不直接输入文字。

## 工作时间、节假日与请假

默认周一至周五 `09:00–12:00,13:00–18:00`，每段开始包含、结束不包含；支持多段和跨午夜。判断顺序为 **个人请假 → 手动日期调整 → 官方放假/调休 → 每周安排**。

内置经官方公告核对的 **2026 年中国大陆节假日与补班日期**，来源、发布日期和版本在设置中展示。[国务院办公厅 2026 年放假通知](https://www.beijing.gov.cn/zhengce/zhengcefagui/202511/t20251104_4258873.html)

未知年份显示“节假日未核验”，暂按每周安排、手动调整和请假判断。可导入年度 JSON 文件，格式参考 `app/src/main/assets/holidays-2026.json`，包含 year、source、published、version、holidays 和 workdays；实际日期应按该年度官方公告填写。应用不联网抓取日历。

```text
每周工时/静音时段：1 09:00-12:00,13:00-18:00（1=周一，7=周日，每行一个星期）
跨午夜：1 22:00-08:00（按开始日归属）
日期调整：2026-10-16，选择休息或填写工作时段
时段请假：2026-10-16 09:00 至 2026-10-16 12:00
全天请假：2026-10-16 至 2026-10-18（包括结束日）
```

普通工作安排修改对之后来电生效。补录、修改或取消请假，以及修订日历或单日安排，会更正历史分类、重算评分，保留原始分类和响铃/静音决策。报表展示更正依据，导出保留配置、日历、请假版本和原始时区。

## 号码评分与报表

**打扰越严重，分数越低；分数越低，表示该号码越不受欢迎。评分与“是否需要关注”无关。**

默认公式为 `max(0, 100 − 非工作来电次数 × 5 − 短时间重复次数 × 2)`，相邻来电间隔不超过 10 分钟计一次重复。权重、间隔和观察天数可调整；无记录显示“暂无评分”。白名单和职位关系不改变评分公式。

悬浮窗和组件默认显示最近 30 天滚动评分。报表可选择今天、近 7/30 天或自定义日期，按显示日期范围计算，列出逐次日期时间、分类原因、通话结果和时长，区分未知与实际 0 秒。可筛选工作、请假、节假日、休息日和工作时段外来电，并导出 CSV。CSV 保留号码文本和比较基准职位，对公式型文本转义。

系统记录与筛选事件按号码、时间邻域和可用电话账户关联，仅双方唯一匹配时合并展示；不确定事件保留待核对明细，避免重复汇总。重复导入不会重复计数。

## 权限和息屏运行

| 条件 | 用途与缺失时的表现 |
| --- | --- |
| 来电识别角色 | 系统交付可筛选来电，不更换默认拨号器 |
| 电话状态、通知 | 播放与通话结束管理、前台服务通知 |
| 通话记录 | 补全结果与准确时长；未授权时明确提示，保留可登记事件和未知时长 |
| 通讯录（可选） | 让系统也交付已保存联系人的来电；未授权无法保证这些来电的悬浮窗和免打扰 |
| 显示在其他应用上层 | 来电卡片；未授权仍可使用编辑页、统计和免打扰 |
| 后台运行/自启动 | 在 HyperOS 应用设置中确认，页面提供入口和电池优化状态 |

覆盖安装后 HyperOS 可能撤销运行时权限，请重新检查。通话记录权限还受安装来源允许名单限制，系统不允许授权时保留未知时长。

核心处理不依赖 Activity、亮屏或联网。音频准备和决策使用有超时的短时 CPU 唤醒锁，播放使用 MediaPlayer 唤醒模式；没有来电时不运行音频保活。电话结束和系统通话记录变化安排后台补全；Doze 延后时保留待补全状态，系统允许执行后恢复。内容变化任务按 [Android 官方方式替换同 ID 任务](https://developer.android.com/reference/android/app/job/JobInfo.Builder#addTriggerContentUri(android.app.job.JobInfo.TriggerContentUri))，保留处理期间累积的新变化。

息屏不会主动点亮屏幕，锁屏隐藏个人信息；仍在响铃时解锁可显示卡片，已结束的摘要不会重新自动弹出。编辑草稿可在解锁后继续保存或取消。

重启恢复从首次解锁后开始，不承诺 Direct Boot 阶段读取完整配置。普通进程回收可由后续系统入口启动；“强行停止”、权限/角色撤销仍需重新打开和恢复条件，应用不能保证绕过全部厂商省电限制。

## 构建与设备测试

minSdk 29、compileSdk/targetSdk 33；Java 8 源码、JDK 17、Gradle 7.5 / AGP 7.3.1。需 Android SDK / Build Tools 33，通过 `local.properties` 或 SDK 环境变量配置。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:assembleDebugAndroidTest --console=plain
```

可安装文件：`app/build/outputs/apk/debug/app-debug.apk`。Release 默认未签名，没有模拟电话组件、自测 runner 或 QA 数据库路由。

Debug 使用单 APK 自测 runner，避免 HyperOS 拒绝独立测试包。数据库测试使用专属测试库；界面和来电模拟使用 `management_phone_qa.db`，结束后恢复用户数据库及原规则铃声开关。测试组件要求系统 DUMP/Telecom 权限，不接受任意测试号码，不拨出网络电话，不删除系统通话历史。

```powershell
adb shell am instrument -w -e class com.greatwall.callring.ManagementStoreDeviceTest,com.greatwall.callring.IncomingCallManagerDeviceTest,com.greatwall.callring.SyncJobDeviceTest,com.greatwall.callring.CallerWidgetDeviceTest com.greatwall.callring/android.test.InstrumentationTestRunner
python tools/management_device_smoke.py --standby-minutes 30
```

界面检查先解锁手机，再运行 `ManagementUiDeviceTest`、`CallerOverlayDeviceTest`、`CallerWidgetHostDeviceTest`。后两类仅使用测试组件和临时窗口，保留用户已有桌面组件。模拟系统可能保留固定测试号码的记录。

测试中断后可清理模拟状态并恢复正式数据库：

```powershell
adb shell dumpsys deviceidle unforce
adb shell am broadcast -n com.greatwall.callring/.DebugProbeReceiver --es op end
adb shell am broadcast -n com.greatwall.callring/.DebugProbeReceiver --es op unregister
adb shell am broadcast -n com.greatwall.callring/.DebugProbeReceiver --es op qa_finish
```

## 验证记录

2026-10-14 至 2026-10-15，小米 15 Pro（2410DPN6CC），Android 16 / HyperOS OS3.0.308.0.WOBCNXM：

- 69 项单元测试通过；Debug、Release、AndroidTest 构建成功，lint 无错误。
- **51 项设备测试通过**，覆盖数据库、决策失败保护、工作配置历史、系统内容任务、组件身份、双组件渲染与点击、悬浮窗草稿/滚动保存、编辑及 CSV 导出。
- 本地 Telecom 已验证非工作静音、白名单、工作时间响铃、请假静音、自定义静音、关闭规则铃声后统计、非匹配不统计和短时息屏。
- 息屏跨请假结束、确认深度 Doze 后的静音/白名单、进程回收后配置和来电模拟恢复通过。当前设备将应用列入电池优化白名单，实际 App Standby 桶为系统豁免 5，不声称 rare 限制桶已验证。
- 原铃声的接听/挂断停止、不匹配、关闭规则、音频焦点交接、后台和锁屏模拟回归通过。最终 0.2 APK 已安装且与构建哈希一致；已退出隔离测试模式，保留正式数据并清理模拟账户和服务。
- 30 分钟静置已完成，但无线 ADB 离线阻断了下一次来电注入，该项保留为未通过验收。连接恢复后的界面和后台回归已完成，详见 `docs/plans/来电管理扩展_验证.md`、`app/build/qa/management-telecom.json` 和 `management-telecom-standby.json`。

**真实运营商 SIM 来电、真实通话时长和重启首次解锁后的恢复尚需现场验证。** 本地模拟经过 Telecom 和筛选服务，注入使用 Debug 接收器，不能替代真实 SIM 的后台启动条件。Android 16 实机结果不等同于 targetSdk 36 认证。

原铃声链路保留 1.8 秒准备期限和失败放行，接听、挂断、权限撤销或看门狗会结束服务。筛选提交静音后如果播放进程被系统杀死，接口无法重新打开原系统铃声，这仍是平台能力边界。

号码、档案、设置和明细保存在应用私有数据库，禁止云备份/设备迁移，不申请网络权限。诊断日志只记录固定状态文字；报表仅在用户主动导出时写出。
