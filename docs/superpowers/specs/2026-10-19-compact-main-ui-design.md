# 主界面紧凑布局、档案入口与设置即时生效设计

状态：已评审并拆分，待实施；2026-10-19（第 2 版：并入“设置即时生效、移除保存按钮”与“思考模式开关”需求）。本文是主界面与设置设计的唯一现行版本，取代本路径旧稿；旧稿的 AI 请求策略、应用更新与发布内容已迁出。本文只写方案与验收，不含实现代码，尚未实施。

## 0. 拆分说明
本设计已拆为三份 spec 与一份证据，本文只覆盖 UI 布局与设置结构（原稿第 1–35、89–93、180–238 行范围，不含 AI 请求策略与更新发布实质内容）：

- **A（本文）**：四个主页面公共布局、档案页入口、分页、设置首页、**全部设置页即时生效（移除保存按钮）**、AI/录音表单结构、代码落点与测试同步。
- **B**：[AI 请求策略](2026-10-19-ai-request-policy-design.md)：供应商/兼容合同语义与字段、关闭思考、请求策略快照与最低验收。本文不重复，只链接。
- **C**：[应用更新与发布](2026-10-19-app-update-design.md)：更新页、当前版本读取、下载安装、发布与签名。本文只保留设置页“检查更新”入口位置，行为与验收详见 C。
- **D**：[外部事实证据](2026-10-19-external-facts-verification.md)：代码事实与外部 API 核对记录；本文 §2 的事实与 D §5 一致，引用冲突时以本文决策为准并记录差异。

本文不再保留 AI 思考策略、更新与发布流程的实质内容；设置页“检查更新”只写“详见 C”。若实施中发现与本文决策冲突的代码事实，先按决策实现，并在实施记录列出冲突，不擅自改决策。

行号与编号说明：本文所有 `文件:行` 为 2026-10-19 工作区快照，只用于定位，符号移动后以符号名/文件为准。本文阶段编号（阶段一–四）在本文件内独立：B 用阶段 0–3（0 为前置阻断项），C 沿用原文档全局编号（阶段三–四），三份文件编号不对应、不可互相引用。

## 1. 推荐方案
保留首页、档案、沟通、设置四个主页面，只压缩公共布局并消除重复入口；档案页删除两个正文按钮但保留相关能力——新号码报表入口放到搜索框，录音归档收敛到报表逐次明细；设置首页拆卡；**所有设置页取消“保存设置”，改为逐字段即时生效**；AI 表单按用途分三组、只保留一个“思考模式”开关、预算折叠。相比全局压缩子页面，此方案改动集中、不破坏现有深链与测试标签；相比自动加载列表，保留现有分页可控制改动范围与加载行为。

## 2. 现状与证据
- 档案页正文有“按号码查看报表”“通话录音”两个按钮：`ui/.../feature/callers/CallerListScreen.kt:33`、`:34`；标题右侧是文字按钮“职位管理”“新增”：`:26-29`。
- `UiDestination.Lookup` 路由存在：`ui/.../app/UiDestination.kt:13`；外部 target `"lookup"` 存在：`UiDestination.kt:29`、`ui/.../app/CallringActivity.kt:126`；无档案号码的 `report` 深链回退 Lookup：`CallringActivity.kt:85`。
- 档案搜索只遍历已保存档案：`backend/.../application/CallersService.kt:14-29`。`checkNumber` 是同步纯计算接口：`backend/.../application/api/CallringUiApi.kt:24-25`；`NumberCheck` 含 `normalized`/`validProfileNumber`/`matchesRule`：同文件 `:186-190`。
- 档案路由把查询页、录音入口回调接到列表页：`ui/.../app/CallringApp.kt:111-112`；独立归档列表路由：`:114-118`；查询路由：`:119-125`。
- 分页组件：`ui/.../feature/shared/FeatureFeedback.kt:32-36`；调用点为档案页 `CallerListScreen.kt:42` 与号码报表页 `ui/.../feature/report/ReportScreen.kt:68`；显示页码已是 `pageIndex + 1`，内部 `pageIndex` 为 0 基。
- 公共标题栏 `ScreenFrame`：`ui/.../designsystem/components/SharedComponents.kt:35-73`，TopAppBar 在 `:51-61`。四个主页面分别调用于 `HomeScreen.kt:25`、`CallerListScreen.kt:26`、`ConversationScreen.kt:68`、设置总览经 `SettingsLayout`（`SettingsOverviewScreen.kt:20`）。
- 底部导航为 `NavigationSuiteScaffold`：`CallringApp.kt:70-83`；外层 `Scaffold` 在 `:84-85`；四个 tab 与 `testTag("tab-…")` 在 `:52`、`:76`。
- 设置首页 6 张卡，“录音与 AI”在 `ui/.../feature/settings/SettingsOverviewScreen.kt:37-40`，“系统与支持”在 `:44-46`；设置路由：`ui/.../app/SettingsRoute.kt:15-17`。
- AI 页单列滚动、含重复长说明、预算在底部、高级兼容块默认折叠：`ui/.../feature/ai/AiSettingsScreen.kt:28`、`:33`、`:34-40`、`:44-50`（保存按钮 `enabled` 依赖预算可解析）。
- AI 页 VM 只加载 AI 视图：`ui/.../feature/ai/AiSettingsViewModel.kt:8-52`（`:17`）；录音视图由另一 VM 加载：`:54-83`（`:65`、`:74`）；`patchCombined` 已存在但 UI 未使用：`backend/.../application/api/AiSettingsApi.kt:24`。
- “录音就绪后自动分析”开关当前在录音设置页：`ui/.../feature/recording/RecordingScreens.kt:76-77`。
- 设置页现状普遍是“草稿 + 保存按钮”：共用 `SaveAction`（`SettingsComponents.kt:70`）、`rememberFormBack` 的保存/放弃分支（`FormInteractions.kt`）；AI 与录音页各自有“保存 AI 设置/保存设置”按钮（`AiSettingsScreen.kt:50`、`RecordingScreens.kt:85`），并带 `dirty/baseline` 草稿态（`AiSettingsViewModel.kt:13-18`、`RecordingViewModels`）。记录类表单另有确认按钮（职位/请假对话框、密钥确认、连接测试、删除确认）。
- 依赖：`build.gradle:9` 为 compose BOM `2025.12.01`，本机 Gradle 缓存解析为 **material3 1.4.0**；已核对 `AppBarKt`/`TopAppBarDefaults` 含 `expandedHeight`、`TopAppBarExpandedHeight` 及 `SingleRowTopAppBarOverrideScope`/`TwoRowsTopAppBarOverrideScope`。版本 `app/build.gradle:14-15` 为 `versionCode 2`/`versionName '0.2'`；backend 是 `java-library`（`backend/build.gradle:1`）。

## 3. 公共布局
### 3.1 底部导航
【决策】窄窗口把 `NavigationSuiteScaffold` 的底部栏替换为自定义底部导航，内容高度约 56dp；宽窗口保留现有自适应侧边导航。四个 tab 顺序与 `testTag("tab-<route>")` 不变。底部 inset 只在自定义导航组件内用 `navigationBars` inset 处理一次，由 `Scaffold` 的 `bottomBar` 承接其高度，`NavHost` 继续使用 `Scaffold` 的 `innerPadding`。

【理由】`NavigationSuiteScaffold` 直接使用 M3 `NavigationBar`，内容高度约 80dp，无法只靠传入 `Modifier` 收到 56dp；若导航栏与 `Scaffold` 同时处理 bottom inset 会双重叠加。替换成本集中在一个组合函数，且保留测试标签后现有导航测试仍可复用。

【取舍】若坚持沿用 `NavigationSuiteScaffold`，只能接受默认导航高度，本项不达成；本设计选择替换底部栏、保留宽窗口侧边导航。48dp 点击区与 56dp 内容高度共存：点击区靠外扩与语义节点保证，不靠缩小内容，可点击高度不得低于 48dp。

【验收】底部导航内容区实测高度 56dp±2dp；手势导航与三键导航下底部都只有一个 inset，不出现第二层系统手势空白；每个 tab 的可点击语义节点高度 ≥48dp（且不被裁到 48dp 以下），点击图标或文字都触发切换；切换后选中态与页面一致；失败判据：底部出现双重空白、tab 点击区域小于 48dp 或 `tab-*` 标签丢失。

### 3.2 紧凑标题（仅四个主页面）
【决策】`ScreenFrame` 增加参数 `compactTitle: Boolean = false`，仅首页、档案、沟通、设置四个主页面传 `true`，子页面保持默认。`compactTitle = true` 时通过 `TopAppBar` 的 `expandedHeight` 传 `48.dp`（material3 1.4.0，默认值来自 `TopAppBarDefaults.TopAppBarExpandedHeight`），不新增自绘标题栏。48dp 指标题内容高度、不含状态栏；状态栏与手势区 inset 仍由 `windowInsets` 单独处理，不得把 inset 计入高度。

【理由】标题高度固定在共用组件、用显式参数逐页开启，避免全局压缩子页面；material3 1.4.0 已支持 `expandedHeight`，无需自绘，风险从“是否支持”降为“inset 与背景处理是否正确”。

【验收】状态栏 inset 只计一次：标题文字不进入状态栏，内容不被状态栏遮挡，标题区实测高度 48dp；短标题与长标题均为单行、超长省略；浅色/深色均正常；列表滚动到顶与滚动中标题区背景一致、无独立背景色，列表内容不从标题下穿出，标题高度不随滚动变化；失败判据：状态栏高度被加进 48dp、标题上移进状态栏、滚动时出现独立背景色或高度跳动。

### 3.3 宽窗口与子页面
【决策】宽窗口保留侧边导航；号码报表、录音归档、设置子页等继续使用默认高度 `ScreenFrame` 与返回按钮，不传 `compactTitle`。

【验收】宽窗口（可用宽度 ≥600dp）显示侧边导航且无底部导航；子页面标题高度与现状一致，返回按钮可点；失败判据：子页面被全局压缩或宽窗口同时出现两套导航。

## 4. 档案与入口
### 4.1 删除两个正文按钮，标题右侧改图标
【决策】删除 `CallerListScreen.kt:33`“按号码查看报表”与 `:34`“通话录音”两个正文按钮，同步移除列表页的 `onLookup`/`onRecordings` 参数与 `CallringApp.kt:111-112` 的对应回调。标题右侧保留“职位管理”“新增”两个动作，改为图标按钮：每个点击区 ≥48dp，带无障碍名称与提示（如“职位管理”“新增档案”），图标语义不得为空。

【理由】两个入口已有替代路径（见 4.3、4.4），保留正文按钮会与搜索框动作、报表明细重复；图标按钮满足紧凑标题并保留操作可达性。

【验收】档案页正文不再出现“按号码查看报表”“通话录音”文字；标题右侧两个图标各 ≥48dp 点击区，点击分别打开职位管理与新增档案；TalkBack 读出“职位管理”“新增档案”；失败判据：任一文字按钮仍在、图标无无障碍名称或点击区 <48dp。

### 4.2 保留 Lookup 路由与外部深链
【决策】保留 `UiDestination.Lookup`（`UiDestination.kt:13`）、`composable("lookup")` 路由（`CallringApp.kt:119-125`）、外部 target `"lookup"`（`UiDestination.kt:29`、`CallringActivity.kt:126`）与 Intent 映射。删除档案页按钮不等于删除查询能力；无档案号码的 `report` 深链仍回退到 Lookup（`CallringActivity.kt:85`）。

【理由】删除路由会破坏外部深链：`report` 深链对无效/无档案号码的回退目标就是 Lookup；查询页本身还承载号码规范化与规则匹配展示。

【验收】`fromExternal("report", null)` 与无效号码 `report` 均得到 Lookup；`target = "lookup"` 直达查询页；查询页输入有效号码可进入对应报表；失败判据：深链落到首页、空白页或崩溃。

### 4.3 新号码报表入口 = 搜索框动作行
【决策】档案页搜索框下方增加一行动作：“查看该号码的报表”。显示条件：查询文本非空、`checkNumber` 为同步纯计算且 `validProfileNumber = true`、当前搜索已加载且非 loading、**当前结果中没有号码与规范化号码完全相等的档案**（`items.none { it.number == check.normalized }`）。只要没有精确匹配就显示，其他号码的子串命中（`CallersApi` 是 contains 匹配）不抑制该动作行。点击进入 `Report(check.normalized)`，动作行展示规范号码；是否匹配规则只作副文案，不作为显示条件。`CallerListViewModel` 注入 `checkNumber` 并暴露同步派生 `NumberCheck`，Screen 只渲染状态与回调。

【理由】`CallersService.kt:14-29` 只遍历 `s.profiles`，无档案号码搜不到，若不另给入口就丢失 README 第 15 行承诺的“未匹配规则的有效号码也可查看报表”；查询页（Lookup）已不在档案页可达，搜索框动作是最短路径。

【验收】输入无档案的有效号码（如 `31234`）→ 出现动作行，点击后报表标题与号码为规范化号码；输入姓名/部门且无匹配 → 不出现；结果中已有该号码精确档案 → 不出现；输入能命中他人档案号码子串、但自身无精确档案的有效号码 → **仍出现**（边界回归项）；`matchesRule=false` 但 `validProfileNumber=true` 的号码也出现；空白、非法号码、loading 中不出现；失败判据：无精确匹配却被抑制、有精确匹配仍出现、点击进入错误号码或进入后报表为空数据错误。

### 4.4 录音入口迁移与孤立归档
【决策】删除档案页录音入口后，录音的导入、回听、分析/重试、删除统一从号码报表逐次明细的“录音与分析”（`ReportScreen.kt:101`）进入归档页（路由 `CallringApp.kt:148-155`，页面 `RecordingScreens.kt:104-156` 已含这些操作）。独立 `recording-archives` 路由仅保留外部可寻址（`UiDestination.kt:10`、`CallringActivity.kt:120`），不再从档案页进入，其去留不在本文决定。

【理由】归档以 `callId` 关联通话，从报表明细进入能保证用户先看到对应通话再操作录音，避免在无关通话的归档上误操作。

【验收】档案页无录音按钮；报表逐次明细 →“录音与分析”→ 可导入、播放/停止、分析/重试、删除，返回链路正确；用测试数据与设备数据核对全部 `CallArchive` 的 `callId` 均能对应到通话，确认不存在无对应通话的孤立归档，发现即记为缺陷单独处理，不通过隐藏入口掩盖；失败判据：存在无法从任何通话明细到达的归档，或从报表进入的录音与当前通话不一致。

## 5. 分页
【决策】`PageControls`（`FeatureFeedback.kt:32-36`）在单页时整体隐藏：`totalItems <= pageSize`（含 0 条）时不渲染上一页、页码、总条数与下一页。多页时保持现有布局与文案；页码继续按“内部 `pageIndex`（0 基）显示 +1”，不改变语义；loading 期间按钮禁用、首页/末页边界规则不变。档案页与号码报表页同时生效（`CallerListScreen.kt:42`、`ReportScreen.kt:68`）。

【理由】单页时按钮无意义且占空间；两处共用同一组件，只在组件内判断即可一致生效，避免两页行为分叉。

【验收】0 条或 `pageSize` 条数据时列表底部无“上一页/下一页/第 1 页/共 N 条”任何文字；`pageSize + 1` 条时出现控件，首页“上一页”禁用、末页“下一页”禁用、页码从 1 开始、总数正确、loading 中两按钮禁用；档案页与报表页分别验证；失败判据：单页仍显示页码/按钮，或报表页与档案页行为不一致。

## 6. 设置首页
【决策】把 `SettingsOverviewScreen.kt:37-40` 的“录音与 AI”卡拆成两张卡：“录音”卡 →“录音设置”→“录音设置页”；“AI”卡 →“AI 设置”→“AI 设置页”。在“系统与支持”卡（`:44-46`）的“运行状态与权限”之后增加“检查更新”行，副标题显示当前安装版本名（读取来源见 C，必须来自 `PackageManager`），点击进入更新页（页面与状态由 C 定义）。`backendQa` 变体不显示该入口（见 C）。接线方式：设置总览在 `CallringApp.kt` 的 `composable("settings")` 内直接组合（现状 `:156-160`），`SettingsRoute.kt` 只按 `SettingsPage` 分发子页；因此更新页作为**新的 `SettingsPage.UPDATE` 子页**接入——`SettingsPage` 枚举加一项（枚举在 `SettingsOverviewScreen.kt`），`SettingsPage.entries` 自动生成路由，`SettingsRoute.kt` 增 UPDATE 分支组装更新页，`SettingsOverviewScreen` 的导航回调复用既有 `onNavigate`。

【理由】录音设置与 AI 设置职责不同、页面独立，合并卡片让用户多点一层；版本副标题让用户在入口处即可核对当前版本，避免更新页才能看到。

【验收】设置首页依次出现“录音”“AI”两张独立卡且各只含对应一行，不再存在“录音与 AI”合并卡；“系统与支持”内出现“检查更新”，副标题等于系统应用信息中的版本名（如 `0.2`，不得硬编码），点击进入更新页；`backendQa` 变体不出现该行；失败判据：仍是合并卡、副标题为空或与安装包不一致、点击无反应。

## 7. 设置即时生效与 AI/录音表单
### 7.1 移除“保存设置”：全部设置页即时生效
【决策】设置首页可达的设置页（免打扰、职位、我的职位、工作时间、节假日、请假、评分、铃声、运行状态与权限、外观、录音设置、AI 设置）不再出现“保存设置”按钮，每个字段改动立即提交并生效。要删除的现有实现：`SaveAction`（`SettingsComponents.kt:70`）的按钮调用、`AiSettingsScreen.kt:50` 与 `RecordingScreens.kt:85` 的保存按钮、`rememberFormBack` 的“保存并返回/放弃修改”分支（`FormInteractions.kt`）以及各页的 `dirty/baseline` 草稿态。

范围例外（保留一次性确认按钮，它们提交的是一条记录或一次危险操作，不是设置项）：新增/编辑职位、新增/编辑请假、密钥编辑对话框的确认、连接测试确认、删除确认、导入音频文件。**即时生效只覆盖设置界面内的设置项**：档案编辑、来电悬浮窗内的档案编辑、桌面小组件配置页、首页免打扰弹窗（`HomeQuietCard`）保持现状，不做改动。

【理由】用户明确要求“修改直接生效、不再手动保存”；逐字段提交也让 AI 设置与录音设置的交叉字段（7.4、7.5）可以各写各的，不再需要整页事务，同时消除“改了没生效就返回”的丢改动问题。

【验收】逐页检查：任一项改动后立即可观察到生效（不点任何按钮），返回再进入、切页再回来、杀进程重启后值都保持；页面底部与顶部都没有“保存设置”类按钮；例外清单中的对话框仍有确认按钮；失败判据：任一设置页仍出现保存按钮、改动需要二次确认、或返回后值回退。

【提交与冲突】每次提交携带 `base` 修订号；冲突时刷新为服务端最新值并提示“已更新，请重新修改”，不覆盖他人写入；提交失败保留用户当前输入并显示原因，不静默回滚。

【输入防抖】数值文本字段（上下文预算、输出预留、评分阈值）在停止输入 500ms 或失焦且解析合法时提交；非法中间值不提交、不落库，就地显示错误。

【返回与草稿】取消“放弃未保存修改”对话框与草稿保护；返回即离开，已生效改动不回滚（需要撤销时用户再次修改）。`FormDialog`/`UnsavedChangesDialog` 仅保留给例外清单中的记录表单。

### 7.2 三组结构
【决策】AI 设置表单固定三组，自上而下为“内容分析与沟通”“录音转写”“高级设置”。第一组包含连接地址、协议、模型、密钥状态与“本地编辑密钥”、连接测试、按方向的复制连接，以及唯一的“思考模式”开关（7.6）；录音转写组包含连接地址、模型、密钥、测试/复制与“录音就绪后自动分析”开关；高级设置组只含预算两项。删除 `AiSettingsScreen.kt:28`、`:33` 的重复长说明，同类信息只保留一处。没有“保存 AI 设置”按钮，密钥本地编辑与测试确认仍保留。

【验收】页面自上而下出现三个组标题且顺序正确；每组字段齐全可编辑；自动分析开关只在“录音转写”组、思考模式开关只在第一组；重复说明只剩一处；页面无任何保存按钮；失败判据：组顺序错误、字段缺失、同一说明出现两次、或出现保存按钮。

### 7.3 高级设置折叠与错误可见
【决策】“高级设置”默认折叠，容纳“上下文预算”“输出预留”两项（`AiSettingsScreen.kt:44-49`）。没有保存按钮后，校验规则不变（`ConversationBudget.validate`，`AiSettingsApi.kt:13-15`：上下文 8192–1048576、输出 1024–16384、输出小于上下文），但交互改为：**非法值不落库**；折叠时标题右侧显示红色汇总与非法字段数，用户点开即定位；展开后字段级错误就地显示；恢复合法值后汇总消失并立即提交。

【理由】原设计靠“保存按钮禁用”暴露非法值，按钮移除后必须另给可见入口；自动展开会在逐字输入时抖动，因此用“标题汇总 + 点击展开 + 就地错误”。

【验收】折叠状态清空或填入非法预算 → 不触发任何 patch、标题出现红色汇总；点击标题 → 展开且非法字段有错误文案；恢复合法值 → 汇总消失并在防抖窗口后提交一次；失败判据：非法值被提交、折叠时无任何错误提示、或展开后无法定位错误字段。

### 7.4 “录音就绪后自动分析”迁移
【决策】把该开关从 `RecordingScreens.kt:76-77` 移到 AI 设置“录音转写”组，录音设置页删除该开关及其说明，只保留录音开关、采集模式与目录授权。存储字段不迁移、不复制，仍为 `RecordingSettingsView.automaticAnalysis`（`AiSettingsApi.kt:19`）。切换即生效（提交方式见 7.5），没有“保存并返回”。

【验收】录音设置页不再出现该开关；在 AI 页切换后立即生效，退出重进 AI 页与录音页读到的值一致；旧配置读取值正确；失败判据：两页都出现或都消失、切换后需再点保存、切换后另一页仍显示旧值。

### 7.5 双快照加载与即时提交（新增工作）
【决策】承认以下是本设计的**新增工作**：AI 页“同时加载 AI 配置与录音设置两份快照”当前不存在（`AiSettingsViewModel.kt:8-52` 只加载 `AiSettingsView`（`:17`），录音快照由另一 VM 加载（`:65 getRecordingView`））。实现要求：AI 页持有两份 base 快照；**自动分析开关**提交时经 `patchCombined`（AI 侧全 `Keep`，录音侧只 `Set(automaticAnalysis)`）一次事务落库；其他 AI 字段走 `patchAi`；录音页任何提交对 `automaticAnalysis` 发 `Keep`（`AiSettingsApi.kt:22`）。取消“保存 AI 设置”整页提交。

【理由】自动分析字段存在录音设置里，AI 页必须能写它又不覆盖录音其他字段；即时生效取代整页保存后，每次提交只能带本字段的 patch。

【验收】切换自动分析后 Fake 收到一次 `patchCombined`（AI 全 Keep、录音仅 `automaticAnalysis=Set`）；切换思考模式/地址/模型/预算后分别收到 `patchAi` 且其他字段为 `Keep`；录音页提交中 `automaticAnalysis` 为 `Keep`；两页并发编辑不同字段均不丢失；失败判据：提交整份快照、或一次改动覆盖另一页刚改字段。

### 7.6 思考模式控件（契约见 B）
【决策】AI 设置“内容分析与沟通”组内只放一个“思考模式”开关（开/关，默认关），不出现供应商、兼容合同、参数名或字段位置；开关下方一行说明当前状态（可用 / 自定义网关可能不生效 / 当前模型不支持切换），原因文案由 B 的错误码派生。开关的枚举、厂商矩阵、参数构造、迁移与请求行为由 B 定义；A 只定义控件位置、无供应商选择器。现有“高级兼容”折叠块（`AiSettingsScreen.kt:34-40`）随 B 的迁移一并移除，不保留兼容选择。

【验收】第一组内只有“思考模式”一个开关且默认关；界面无供应商/合同/参数名文案；自定义网关、未知模型、组合不支持三种情况都有一行可读原因；旧“高级兼容”块不再出现；失败判据：仍出现供应商或兼容方式选择、开关无状态说明、或未知模型仍可切换。

## 8. 代码落点
| 文件 | 改动职责 | 决策 |
| --- | --- | --- |
| `ui/.../app/CallringApp.kt` | 窄窗口底部导航替换；删除列表页 `onLookup`/`onRecordings` 接线；档案路由注入 `checkNumber`；保留 lookup/report/archive/设置路由 | 3.1、4.1–4.4 |
| `ui/.../designsystem/components/SharedComponents.kt` | `ScreenFrame` 增加 `compactTitle`；TopAppBar `expandedHeight = 48.dp`；透明容器/滚动色 | 3.2 |
| `ui/.../feature/shared/FeatureFeedback.kt` | `PageControls` 单页整体隐藏，其余行为不变 | 5 |
| `ui/.../feature/callers/CallerListScreen.kt` | 删两正文按钮；图标按钮；有效号码动作行；分页新行为 | 4.1、4.3、5 |
| `ui/.../feature/callers/CallerListViewModel.kt` | 注入 `checkNumber`，暴露同步 `NumberCheck` 派生；分页修正逻辑不变 | 4.3 |
| `ui/.../feature/callers/NumberLookupScreen.kt` | 结构保持现状，仅回归 Lookup 路由与深链回退 | 4.2 |
| `ui/.../feature/callers/NumberLookupViewModel.kt` | 保持现状，仅补回归覆盖 | 4.2 |
| `ui/.../feature/report/ReportScreen.kt` | 分页单页隐藏；保留逐次明细“录音与分析”入口 | 4.4、5 |
| `ui/.../app/CallringActivity.kt` | 深链映射与 `report`→Lookup 回退保持不变 | 4.2 |
| `ui/.../app/UiDestination.kt` | Lookup/Report 路由定义不变 | 4.2 |
| `ui/.../feature/settings/SettingsOverviewScreen.kt` | 拆录音/AI 卡；系统与支持加“检查更新”与版本副标题；设置总览传 `compactTitle` | 3.2、6 |
| `ui/.../app/SettingsRoute.kt` | 新增 `SettingsPage.UPDATE` 分支，组装更新页（页面实现见 C） | 6 |
| `ui/.../feature/settings/components/SettingsComponents.kt` | `SettingsLayout` 透传 `compactTitle`，仅设置总览为 true | 3.2、6 |
| `ui/.../feature/ai/AiSettingsScreen.kt` | 三组结构；思考模式开关（不出现供应商）；高级折叠与错误汇总；删重复说明；删除保存按钮与草稿态 | 7.1–7.6 |
| `ui/.../feature/ai/AiSettingsViewModel.kt` | 双快照加载；逐字段即时提交（自动分析走 `patchCombined`）；删除 save/dirty/baseline 草稿态 | 7.4、7.5 |
| `ui/.../feature/recording/RecordingScreens.kt` | 删除自动分析开关及说明、保存按钮、保存并返回/放弃对话框；保留导入/回听/重试/删除 | 4.4、7.1、7.4 |
| `ui/.../feature/settings/{QuietScreen,ScoreSettingsScreen,WorkScheduleScreen,AppearanceScreen}.kt` | 删除 `SaveAction` 按钮与 `rememberFormBack` 草稿分支，改为逐字段即时提交 | 7.1 |
| `ui/.../feature/settings/components/SettingsComponents.kt` | 删除 `SaveAction` 或改为非保存语义的状态提示；`SettingsLayout` 同时透传 `compactTitle` | 3.2、7.1 |
| `ui/.../designsystem/components/FormInteractions.kt` | `rememberFormBack`/`UnsavedChangesDialog` 仅保留给记录表单，设置页不再使用 dirty 分支 | 7.1 |
| `ui/.../feature/settings/SettingsViewModels.kt`、`ui/.../feature/recording/RecordingViewModels.kt` | 删除整页 save 与草稿，改为单字段 patch + 冲突刷新；错误文案接 B 的新错误码 | 7.1、7.5 |
| `ui/.../feature/home/HomeScreen.kt` | `ScreenFrame` 传 `compactTitle = true` | 3.2 |
| `ui/.../feature/conversation/ConversationScreen.kt` | `ScreenFrame` 传 `compactTitle = true` | 3.2 |

路径缩写规则：`ui/...` = `ui/src/main/kotlin/com/greatwall/callring/`，`app/...` = `app/src/main/kotlin/com/greatwall/callring/`，`backend/...` = `backend/src/main/kotlin/com/greatwall/callring/`；测试路径为 `ui/src/test/kotlin/com/greatwall/callring/ui/…` 与 `ui/src/androidTest/kotlin/com/greatwall/callring/ui/…`。实施计划展开完整路径。

## 9. 受影响测试
| 测试文件（现有） | 需改/需加 | 覆盖点 |
| --- | --- | --- |
| `ui/src/test/.../app/UiDestinationTest.kt` | 改 | Lookup 路由与 `report` 回退保持，不新增删除路由断言 |
| `ui/src/test/.../app/CallringActivityTest.kt` | 加 | 无效号码 `report` 深链 → Lookup；`recording-archives` 兼容 target 仍可寻址 |
| `ui/src/test/.../app/ConversationNavigationTest.kt` | 改 | 自定义导航后四个 `tab-*` 标签与切换仍成立 |
| `ui/src/test/.../feature/callers/CallerListViewModelTest.kt` | 加 | 同步 `NumberCheck` 派生、动作行显示条件；分页修正回归 |
| `ui/src/test/.../feature/callers/NumberLookupViewModelTest.kt` | 加 | 有效/无效/规则匹配三态回归 |
| `ui/src/test/.../feature/report/ReportViewModelTest.kt` | 加 | 单页/多页页码与首末页边界回归 |
| `ui/src/test/.../feature/settings/SettingsScreensJvmTest.kt` | 改 | 拆卡与“检查更新”行；删除保存按钮断言，改为逐字段即时提交断言；共享 `SettingsScreenCases` 同步影响 androidTest 的 `SettingsScreensTest` |
| `ui/src/test/.../feature/settings/AppearanceViewModelTest.kt`、`WorkScheduleViewModelTest.kt`、`SettingsDraftCodecsTest.kt` | 改 | 删除草稿/save 用例，改为“改动即提交、冲突刷新”用例 |
| `ui/src/test/.../feature/recording/RecordingViewModelsTest.kt`、`ui/src/test/.../feature/ai/`（AI 设置 VM 相关） | 改 | 删除 save/dirty 用例；补“单字段 patch + 防抖 + 冲突”用例 |
| `ui/src/test/.../state/DraftEditorTest.kt` | 改 | 若 `DraftEditor` 仍服务于记录表单则保留，设置页相关用例删除 |
| `ui/src/test/.../feature/recording/RecordingScreensTest.kt` | 改 | 删除 `callerListProvidesIndependentRecordingsEntry`；自动分析归属 |
| `ui/src/test/.../feature/recording/RecordingArchiveListTest.kt` | 加 | 归档与通话关联回归 |
| `ui/src/androidTest/.../AppNavigationTest.kt` | 改 | 底部导航高度、48dp 点击区、inset、四主页面切换 |
| `ui/src/androidTest/.../feature/callers/CallerScreensTest.kt` | 改 | 图标按钮语义、有效号码动作行、单页隐藏分页 |
| `ui/src/androidTest/.../feature/callers/NumberLookupScreenTest.kt` | 改 | Lookup 页保持可用 |
| `ui/src/androidTest/.../feature/report/ReportScreenTest.kt` | 改 | 报表单页隐藏分页、录音与分析入口 |
| `ui/src/androidTest/.../designsystem/SharedComponentsTest.kt` | 加 | `compactTitle` 高度、无独立背景色、长标题省略 |

## 10. 文档同步
- `README.md`：删除第 15 行“号码查询……也可查看报表”的旧表述，改为“档案搜索框对无档案有效号码显示‘查看该号码的报表’”；第 69 行删除“档案 → 通话录音”独立入口描述，改为“号码报表 → 逐次明细 → 录音与分析”；补充新的号码报表入口与录音入口说明。
- `docs/plans/UI业务API契约_v1.md`：第 43–44 行保留 `checkNumber` 同步纯计算语义并补充本入口用途；第 214、282–284 行补充“有效号码无档案时从档案搜索动作进入报表”；第 706–716 行页面接入对照表更新档案列表（`checkNumber` 动作）与设置总览（拆卡、“检查更新”）。
- `PROJECT.md`：「启动注意事项」中过期的工具版本行（Gradle 7.5 / AGP 7.3.1 / SDK 33 / Build Tools 33.0.0）更新为实际 Gradle 8.13 / AGP 8.13.2 / compileSdk 36 / minSdk 29 / targetSdk 33（依据 `gradle/wrapper/gradle-wrapper.properties`、`build.gradle:2-3`、`app/build.gradle:6,12-13`）；同节构建入口行补 `:backend:test`。该文件存在混合换行，不同工具下行号不一致，按内容定位而不是行号。

## 11. 分阶段实施与验收
- **阶段一：公共布局**：替换窄窗口底部导航，`ScreenFrame` 增加 `compactTitle`，四个主页面启用；验收 48dp 标题与 56dp 导航实测、inset 只计一次、深浅色、长标题、滚动背景一致，子页面与宽窗口不受影响。
- **阶段二：档案与分页**：删除两正文按钮、改图标、加有效号码动作行、单页隐藏分页；验收图标点击区与无障碍名称、动作行显示条件、Lookup 路由与深链回退、两页分页行为一致。
- **阶段三：设置即时生效与 AI/录音表单**：拆卡、加“检查更新”入口、移除全部设置页的保存按钮与草稿态、逐字段即时提交（含防抖与冲突刷新）、AI 三组与折叠错误汇总、思考模式开关、自动分析迁移与双快照 `patchCombined`；验收拆卡与版本副标题、任一改动立即生效且重启后保持、非法值不落库、折叠错误可见、自动分析两页一致、各字段互不覆盖。
- **阶段四：测试与文档**：更新第 9 节测试，同步第 10 节文档，运行统一验证入口。

```powershell
.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug
```

- `backend` 是 `java-library`，测试任务为 `:backend:test`（`backend/build.gradle:1`），不是 `testDebugUnitTest`；统一命令已补该任务。
- Release 签名与真实下载/覆盖升级不在本设计的统一命令覆盖范围内，由 C 另行验收；仅 Debug 构建与单测通过不代表更新链路通过。
- 设备仪器测试（`:ui:connectedDebugAndroidTest`）在设备可用时执行，覆盖第 9 节 androidTest 清单。
- 若实施中发现与本文决策冲突的代码事实，先按决策实现，并在实施记录的“冲突”一节列出，不静默改决策。

## 12. 不涉及范围
- AI 请求策略、供应商/兼容合同语义、思考模式开关的参数与厂商矩阵、请求快照：见 B，本文只定义控件位置与“立即生效”交互，不定义枚举与参数。
- “保存即生效”的范围只覆盖设置页；记录类表单（职位、请假、档案、密钥确认、连接测试确认、删除确认、导入音频）与首页免打扰弹窗、桌面小组件配置页本轮保持一次性确认，后续统一。
- 应用更新、版本与签名、下载安装与发布流程：见 C，本文只保留“检查更新”入口。
- 外部 API 与事实核对过程：见 D。
- 业务数据模型、存储迁移、录音与通话的既有身份关联：不改；不做音频存储迁移。
- 独立 `recording-archives` 路由与页面的去留：本次只移除档案页入口并保留外部可寻址，是否删除另行评审。
- 不写实现代码，不执行外部请求。
