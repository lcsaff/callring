# 通话记录与号码档案详情重新设计实施计划

> 执行时使用 executing-plans 或 subagent-driven-development，按阶段验证。复选框用于跟踪实施进度。

**目标**：调整通话记录列表，重组号码报表为可展开的号码档案详情页，并精简单次通话详情。

**架构**：保留现有 Compose/MVVM、正式档案编辑命令和通话详情入口。通话列表与号码详情复用同一通话行及播放、转写能力。后端按页投影档案摘要和指定号码的可见通话，报表统计继续使用现有区间查询。

**技术栈**：Android、Kotlin、Compose Material 3、SQLite、JVM 和隔离设备测试。

## 交互与数据约定

- 全局「通话记录」每行显示档案摘要、日期时间、录音和转写图标。摘要依次拼接姓名、地区、职务，姓名为空时以号码替代，其他空项省略。姓名和号码作为主文字，字体大于日期时间。日期时间使用通话事实保存的时区，工作时间内为低饱和绿色，工作时间外为低饱和红色，移除原圆点。工作状态未知时使用中性色，并提供无障碍说明，避免将未知事实显示为工作时间外。
- 职务摘要优先使用已有的「自述职位」文本，缺失时使用已配置的职位名称，均缺失则省略。此选择仅用于展示，两个字段的存储和编辑语义保持独立。姓名、地区和职务之间使用间隔分隔，长文本单行省略，字号缩放时日期和两个图标仍可触达。
- 录音图标继续播放或停止，转写图标继续打开全文弹窗。缺内容、索引准备中和读取失败使用现有不可操作及错误状态。点击图标只执行对应操作，点击行其余区域进入相同的单次通话详情。
- 「号码报表」入口进入标题为「号码档案详情」的页面。号码信息、报表信息、通话记录三个标题各有右对齐的展开或收起按钮。初始均收起，返回详情时保留当前展开与滚动状态，配置变更和进程重建按现有保存状态机制恢复。
- 号码信息收起时展示同一套姓名或号码、地区、职务摘要。展开后展示号码、姓名、部门、自述职位、所属地区、职位级别、上下级关系、白名单及已有字段来源和异议状态。当前可编辑字段旁显示编辑图标，点击后进入该字段的编辑交互。号码和计算所得上下级关系按现有契约展示，只有正式编辑入口支持的字段可编辑。
- 报表信息收起时展示当前选定区间的来电总数和现有评分，评分缺失显示「未知」。展开后保留现有日期区间、全部统计、质量提示、权限入口、筛选和 CSV 导出，说明筛选只影响报表明细的原有语义。默认区间与现有页面一致。
- 号码详情中的通话记录独立于报表日期和分类筛选，展示该号码所有应用内可见来电。收起显示最新三条，展开通过有界分页展示全部记录并可继续加载。行只显示日期时间和与全局列表相同的录音、转写图标，行本身进入单次详情。没有更多记录时不显示加载按钮，合并记录只出现一行。
- 整页使用一个纵向滚动容器。号码信息区域离开可视范围后，顶部标题显示相同的档案摘要，返回可视范围后恢复「号码档案详情」。展开按钮具有明确的状态和无障碍名称，长标题截断且不遮挡返回按钮。
- 单次通话详情整合号码、时间、时长、分类、结果、录音及转写状态。删除重复说明和静态操作提示，将导入入口、分析结果及确认操作组织为清晰分组。保留缺失、失败、重试、取消、来源依据、分析费用提示及删除确认等会影响操作判断的信息。独立录音归档继续可用。

## 阶段 1 · 通话与档案摘要查询

**代码落点**

- `contracts/src/main/kotlin/com/greatwall/callring/contracts/callers/CallHistory.kt` 和 `CallersApi.kt`
- `backend/src/main/kotlin/com/greatwall/callring/application/callers/CallHistoryService.kt`、`application/ports/store/CallHistoryReads.kt`
- `app/src/main/kotlin/com/greatwall/callring/data/report/SqliteReportCalls.kt`、`data/callers/SqliteCallerStore.kt`、`data/database/SqliteCoreUnitOfWork.kt`

- [ ] 为 `CallHistoryQuery` 增加可空号码条件，并把号码条件纳入游标校验。保持全局查询的现有调用方式。先测试两个号码、跨日期、同时间戳、未接、拒接、未计分及合并记录，验证指定号码的分页无重漏，不能拿其他号码或全局查询的游标继续分页。
- [ ] 扩展当前页批量档案摘要投影，取得姓名、地区、自述职位和配置职位名称。通话与档案摘要在同一核心读取事务中读取，归档状态继续按当前页批量读取。不得逐行调用 `getOverview`，不得读取音频或转写全文。验证空档案、空姓名、空地区、两种职位及职位被修改后的展示。
- [ ] 查询使用号码与时间、ID 的有界索引范围。游标绑定号码、页大小和所依赖的累计失效 token。档案或职位变化刷新摘要，通话和相关归档变化刷新记录，无关设置变化不调用列表 loader。并发变更后旧游标冲突并重新加载，迟到结果不得覆盖新号码。
- [ ] 检查内部契约所有实现与测试替身，评审并精准更新 `docs/contracts/current-api.md` 中对应声明摘要。核对外部 AI 协议基线及既有报表统计行为。

## 阶段 2 · 复用通话行与全局列表

**代码落点**

- `ui/src/main/kotlin/com/greatwall/callring/ui/feature/callers/CallHistoryScreen.kt`、`CallHistoryViewModel.kt`
- `ui/src/main/kotlin/com/greatwall/callring/ui/feature/recording/RecordingPlaybackState.kt`、`ui/format/UiFormat.kt`
- `ui/src/sharedTest/kotlin/com/greatwall/callring/ui/feature/callers/CallHistoryScreenCases.kt`

- [ ] 抽出可复用的通话行，明确全局行展示摘要和日期，号码详情行仅展示日期。两处共用相同的图标、可用状态、点击隔离、播放会话所有权和转写弹窗。展示状态不触发额外音频或全文读取。
- [ ] 使用用户看到的工作分类设置日期文字颜色，提供工作时间内、工作时间外和未知三种无障碍语义。分别验证深浅主题、足够对比度、窄屏、长名称和系统字体放大。日期格式包含完整日期和时间并使用通话时区。
- [ ] 回归点击音频仅触发播放或停止，点击转写仅打开正确全文，点击行进入正确 call ID，返回列表保留滚动位置。覆盖音频缺失、只有转写、归档读取失败和索引准备状态。

## 阶段 3 · 号码档案详情页面

**代码落点**

- `ui/src/main/kotlin/com/greatwall/callring/ui/feature/report/ReportScreen.kt`、`ReportViewModel.kt`
- `ui/src/main/kotlin/com/greatwall/callring/ui/app/CallringApp.kt`、`RecordingRoutes.kt`
- `ui/src/main/kotlin/com/greatwall/callring/ui/feature/callers/CallerEditorViewModel.kt`、`CallerEditorForm.kt`
- `ui/src/main/kotlin/com/greatwall/callring/ui/designsystem/components/SharedComponents.kt`

- [ ] 将现有报表路由的页面内容重组为三个独立区域。使用 `rememberSaveable` 或现有 `SavedStateHandle` 保存展开状态和列表滚动状态。标题与按钮同一行，点击按钮只改变对应区域。初始三个区域收起，报表摘要使用当前区间总数与 `ScoreBadge`。
- [ ] 号码信息展开后逐项显示正式档案数据。姓名、部门、自述职位、地区、职位级别及白名单按字段提供编辑入口，复用 `CallerDraft`、`UiEditsApi.profile` 和现有字段合并逻辑。每次只提交用户修改的字段，保留其余字段、来源及异议。处理输入校验、取消、未保存退出、并发冲突、提交失败和保存后刷新。未建档号码首次保存通过同一正式命令建档。
- [ ] 号码通话记录接入阶段 1 的指定号码分页查询，收起请求或展示最新三条，展开继续分页至末页。报表区间与筛选不改变这一列表。复用阶段 2 的通话行和转写弹窗，点击跳转既有详情，返回后保持展开状态与滚动位置。验证切换号码、恢复页面、刷新期间旧页迟到、删除或合并通话及无记录场景。
- [ ] 根据号码信息区域在滚动容器中的真实可见位置切换标题，不能只依赖列表首项索引。档案编辑、恢复或删除后同步更新标题，验证区域部分可见、快速上下滑动、折叠、横竖屏和长摘要截断。无关设置变化不重读档案或通话页。

## 阶段 4 · 单次通话详情整理

**代码落点**

- `ui/src/main/kotlin/com/greatwall/callring/ui/feature/recording/ArchiveScreen.kt`、`ArchiveViewModel.kt`、`CallerSuggestionsSection.kt`
- `ui/src/main/kotlin/com/greatwall/callring/ui/app/RecordingRoutes.kt`

- [ ] 将通话事实、录音和转写、AI 分析、归档管理分组，减少重复标签及无条件显示的引导文字。保留号码、完整日期时间、未知时长、工作分类、通话结果，以及有音频或有文本时对应操作。
- [ ] 验证无归档、有音频无转写、纯文本、分析进行中、待确认、已确认、补全失败及归档不可读。离线转写成功后不自动分析，手动分析确认前不写档，选择确认与播放失败均有可见反馈。独立录音入口的导入、删除、播放和确认行为继续工作。

## 阶段 5 · 集成验证与交付

- [ ] 更新 `PROJECT.md` 中现有通话行及号码详情约定，并更新 `README.md` 的用户入口与操作说明。评审按号码分页、摘要字段及失效订阅，确认不引入数据库迁移或外部 AI 协议变化。
- [ ] 运行 `python tools/check_architecture.py`、`python tools/check_backend_boundaries.py` 和 `python tools/verify_backend.py`。读取统一门摘要，记录 backend、UI、app 测试数、失败数、执行或缓存状态与 lint；按失败项修复并重跑。
- [ ] 在隔离设备运行 `python tools/verify_ui.py --device <ADB_SERIAL>`，覆盖三个区域的展开收起、逐字段编辑、三条预览与分页、滚动标题、图标操作和详情返回。增加窄屏、字体放大、横竖屏及深浅主题的针对性设备用例，记录设备和结果。若真机 runner 超时，保留日志并区分模拟器和真机结果。
- [ ] 按项目交付规则审查 diff，保留其他工作区改动，提交、合并并推送，确认最终提交的远端 CI。交付说明列明未覆盖的真实来电或设备场景。

## 验收判定

全局通话行按档案摘要、日期和图标展示，工作时间颜色与历史事实一致。号码详情默认展示三个收起区域、三条最新通话，展开后可编辑档案、查看完整报表和分页浏览全部通话。滚动标题与档案实时一致。两处通话图标和行导航独立，单次详情保留完整业务操作与必要反馈。查询、刷新、并发、无障碍和设备验证均有对应证据。
