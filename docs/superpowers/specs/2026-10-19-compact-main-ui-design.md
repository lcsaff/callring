# 主界面紧凑布局、档案入口与设置重组设计

状态：已评审并拆分，待实施；2026-10-19。本文是主界面与设置设计的唯一现行版本，取代本路径旧稿；旧稿的 AI 请求策略、应用更新与发布内容已迁出。本文只写方案与验收，不含实现代码，尚未实施。

## 0. 拆分说明
本设计已拆为三份 spec 与一份证据，本文只覆盖 UI 布局与设置结构（原稿第 1–35、89–93、180–238 行范围，不含 AI 请求策略与更新发布实质内容）：

- **A（本文）**：四个主页面公共布局、档案页入口、分页、设置首页、AI/录音表单结构、代码落点与测试同步。
- **B**：[AI 请求策略](2026-10-19-ai-request-policy-design.md)：供应商/兼容合同语义与字段、关闭思考、请求策略快照与最低验收。本文不重复，只链接。
- **C**：[应用更新与发布](2026-10-19-app-update-design.md)：更新页、当前版本读取、下载安装、发布与签名。本文只保留设置页“检查更新”入口位置，行为与验收详见 C。
- **D**：[外部事实证据](2026-10-19-external-facts-verification.md)：代码事实与外部 API 核对记录；本文 §2 的事实与 D §5 一致，引用冲突时以本文决策为准并记录差异。

本文不再保留 AI 思考策略、更新与发布流程的实质内容；设置页“检查更新”只写“详见 C”。若实施中发现与本文决策冲突的代码事实，先按决策实现，并在实施记录列出冲突，不擅自改决策。

行号与编号说明：本文所有 `文件:行` 为 2026-10-19 工作区快照，只用于定位，符号移动后以符号名/文件为准。本文阶段编号（阶段一–四）在本文件内独立：B 用阶段 0–3（0 为前置阻断项），C 沿用原文档全局编号（阶段三–四），三份文件编号不对应、不可互相引用。

## 1. 推荐方案
保留首页、档案、沟通、设置四个主页面，只压缩公共布局并消除重复入口；档案页删除两个正文按钮但保留相关能力——新号码报表入口放到搜索框，录音归档收敛到报表逐次明细；设置首页拆卡；AI 表单按用途分三组并把预算折叠。相比全局压缩子页面，此方案改动集中、不破坏现有深链与测试标签；相比自动加载列表，保留现有分页可控制改动范围与加载行为。

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
【决策】把 `SettingsOverviewScreen.kt:37-40` 的“录音与 AI”卡拆成两张卡：“录音”卡 →“录音设置”→“录音设置页”；“AI”卡 →“AI 设置”→“AI 设置页”。在“系统与支持”卡（`:44-46`）的“运行状态与权限”之后增加“检查更新”行，副标题显示当前安装版本名（读取来源见 C），点击进入更新页（页面与状态由 C 定义）。`backendQa` 变体不显示该入口（见 C）。入口接线点在 `SettingsRoute.kt`。

【理由】录音设置与 AI 设置职责不同、页面独立，合并卡片让用户多点一层；版本副标题让用户在入口处即可核对当前版本，避免更新页才能看到。

【验收】设置首页依次出现“录音”“AI”两张独立卡且各只含对应一行，不再存在“录音与 AI”合并卡；“系统与支持”内出现“检查更新”，副标题等于系统应用信息中的版本名（如 `0.2`，不得硬编码），点击进入更新页；`backendQa` 变体不出现该行；失败判据：仍是合并卡、副标题为空或与安装包不一致、点击无反应。

## 7. AI 与录音表单
### 7.1 三组结构
【决策】AI 设置表单固定三组，自上而下为“内容分析与沟通”“录音转写”“高级设置”。前两组各自包含连接地址、模型、密钥状态与“本地编辑密钥”、连接测试、按方向的复制连接；录音转写组包含“录音就绪后自动分析”开关。删除 `AiSettingsScreen.kt:28`、`:33` 的重复长说明，同类信息只保留一处。保存、放弃草稿、冲突提示、密钥本地编辑与测试确认保留。

【验收】页面自上而下出现三个组标题且顺序正确；每组字段齐全可编辑；自动分析开关只出现在“录音转写”组；重复说明只剩一处；失败判据：组顺序错误、字段缺失、同一说明出现两次。

### 7.2 高级设置折叠与错误可见
【决策】“高级设置”默认折叠，容纳“上下文预算”“输出预留”两项（`AiSettingsScreen.kt:44-49`），并预留“供应商/兼容合同”控件位置（契约见 B）。折叠状态下若预算非法，必须在高级设置标题处显示红色汇总错误；点击保存时自动展开并定位到非法字段，展开后显示字段级错误。校验规则沿用 `ConversationBudget.validate`（`AiSettingsApi.kt:13-15`：上下文 8192–1048576、输出 1024–16384、输出小于上下文）。

【理由】保存按钮当前依赖预算可解析（`AiSettingsScreen.kt:50`），折叠会把禁用原因藏起来，用户只看到按钮不可点。

【验收】折叠状态清空或填入非法预算 → 保存不可点且标题处出现错误汇总；点击保存 → 自动展开；展开后对应预算字段有错误文案；恢复合法值 → 汇总消失、保存可用；失败判据：按钮禁用但页面无任何错误提示，或展开后仍无法定位错误字段。

### 7.3 “录音就绪后自动分析”迁移
【决策】把该开关从 `RecordingScreens.kt:76-77` 移到 AI 设置“录音转写”组，录音设置页删除该开关及其说明，只保留录音开关、采集模式与目录授权。存储字段不迁移、不复制，仍为 `RecordingSettingsView.automaticAnalysis`（`AiSettingsApi.kt:19`）。

【验收】录音设置页不再出现该开关；在 AI 页切换并保存后，重进 AI 页与录音页读到的值一致；旧配置读取值正确；失败判据：两页都出现或都消失、保存后另一页仍显示旧值。

### 7.4 双快照加载与事务保存（新增工作）
【决策】承认以下是本设计的**新增工作**：AI 页“同时加载 AI 配置与录音设置两份快照 + 用 `patchCombined` 在同一事务保存”当前不存在。现状 `AiSettingsViewModel.kt:8-52` 只加载 `AiSettingsView`（`:17`），录音快照由另一 VM 加载（`:65 getRecordingView`），`patchCombined` 虽已存在（`AiSettingsApi.kt:24`）但 UI 未使用。实现要求：AI 页持有两份 base 快照，保存时经 `patchCombined` 提交 AI 字段与 `automaticAnalysis`；录音页保存不得覆盖 `automaticAnalysis`（对 `RecordingSettingsPatch.automaticAnalysis` 发 `Keep` 或使该字段不参与录音页 patch，`AiSettingsApi.kt:22`）。

【理由】只加载 AI 视图无法在同一事务里保存两个页面的字段；若录音页继续提交整份 `automaticAnalysis`，会覆盖 AI 页刚保存的值。

【验收】保存 AI 页时 Fake API 收到 `patchCombined`，其中含 AI 字段与 `automaticAnalysis`，录音其余字段为 `Keep`；保存录音页时 `automaticAnalysis` 为 `Keep`（用 Fake 记录 patch 断言）；两页并发编辑不同字段均不丢失；失败判据：任一保存覆盖另一页刚改字段，或提交整份快照。

### 7.5 供应商/兼容合同控件位置（契约见 B）
【决策】“供应商/兼容合同”选择器的语义、枚举、迁移与请求行为由 B 负责；A 只在“高级设置”组预留该控件位置并注明“契约见 B”，不定义枚举值、默认值或名称。现有高级兼容块（`AiSettingsScreen.kt:34-40`）的迁移随 B 执行。

【验收】A 的实施变更不新增或修改该枚举；表单结构中有该控件占位，B 完成后按 B 的定义显示；失败判据：A 自行定义枚举或复制 B 的映射规则。

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
| `ui/.../app/SettingsRoute.kt` | 承接“检查更新”入口到更新页的接线（页面实现见 C） | 6 |
| `ui/.../feature/settings/components/SettingsComponents.kt` | `SettingsLayout` 透传 `compactTitle`，仅设置总览为 true | 3.2、6 |
| `ui/.../feature/ai/AiSettingsScreen.kt` | 三组结构；高级折叠与错误汇总；删重复说明；预留合同控件位置 | 7.1、7.2、7.5 |
| `ui/.../feature/ai/AiSettingsViewModel.kt` | 双快照加载 + `patchCombined` 保存；录音 VM 不再提交 `automaticAnalysis` | 7.3、7.4 |
| `ui/.../feature/recording/RecordingScreens.kt` | 删除自动分析开关及说明；保留导入/回听/重试/删除 | 4.4、7.3 |
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
| `ui/src/test/.../feature/settings/SettingsScreensJvmTest.kt` | 改 | 拆卡与“检查更新”行；共享 `SettingsScreenCases` 同步影响 androidTest 的 `SettingsScreensTest` |
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
- **阶段三：设置与 AI/录音表单**：拆卡、加“检查更新”入口、AI 三组与折叠错误、自动分析迁移、双快照与 `patchCombined` 保存；验收拆卡与版本副标题、折叠错误可见、自动分析两页一致、保存不互相覆盖、录音页不覆盖 `automaticAnalysis`。
- **阶段四：测试与文档**：更新第 9 节测试，同步第 10 节文档，运行统一验证入口。

```powershell
.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug
```

- `backend` 是 `java-library`，测试任务为 `:backend:test`（`backend/build.gradle:1`），不是 `testDebugUnitTest`；统一命令已补该任务。
- Release 签名与真实下载/覆盖升级不在本设计的统一命令覆盖范围内，由 C 另行验收；仅 Debug 构建与单测通过不代表更新链路通过。
- 设备仪器测试（`:ui:connectedDebugAndroidTest`）在设备可用时执行，覆盖第 9 节 androidTest 清单。
- 若实施中发现与本文决策冲突的代码事实，先按决策实现，并在实施记录的“冲突”一节列出，不静默改决策。

## 12. 不涉及范围
- AI 请求策略、供应商/兼容合同语义、关闭思考与请求快照：见 B，本文不定义。
- 应用更新、版本与签名、下载安装与发布流程：见 C，本文只保留“检查更新”入口。
- 外部 API 与事实核对过程：见 D。
- 业务数据模型、存储迁移、录音与通话的既有身份关联：不改；不做音频存储迁移。
- 独立 `recording-archives` 路由与页面的去留：本次只移除档案页入口并保留外部可寻址，是否删除另行评审。
- 不写实现代码，不执行外部请求。
