# 实施前结构风险复核

2026-10-20。只读代码审查发现的实施约束；不是验收通过证明。

1. T03 必须同时提取内部录音配置/测试连接端口，以及 setSystemFolder 的安全结果，不能只移动包后等 T10 才修编译。所有调用方包括调度、AI 客户端、快照、UI 和测试都要同步。
2. CredentialDialog 复制预览必须由后台按 AUTO 计算，不可复用受当前 compatibility 影响的 resolvedProfile。
3. UI/sharedTest 使用多个 Unavailable singleton 作为委托假件；这些实现移 backend 后必须建立 UI 自有假件，禁止添加 backend testImplementation 破坏隔离。
4. domain 原生模型迁移须包含 codec、历史枚举、nullable 原始分类和双方映射；WorkClassifier 的输入也需窄化，不能只在 BackendState 外包一层 DecisionInputs。
5. 过渡期旧入口与新端口共享同一个懒 SQLite 资源；生命周期锁覆盖整个操作而非只保护 get()。计数不打开库，关闭后所有接口拒绝新操作。
6. 固定 fence→SQL 顺序；SQL 内不取 coordinator 版本。统一发布后仍保留命令版本向量、domainMarker、incarnation 和 own-commit 记录；无操作不产生数据变化但可记录命令完成。
7. SQL 源查询中的统计、工作状态和 AiJson 投影需上移；持久化 JSON 解析下沉 codec。FullSnapshotReader 的审计/大小限制不可丢。
8. 第一片精准提交必须同时接入首个真实累计订阅和加载协调，不能在 T11 前依赖最近单事件掩盖跳帧。
9. 当前小组件显示全局免打扰和请假，不读取号码/绑定。不得按旧命名推测每个 widget 都显示号码档案；A/B 和 widget i/j 的刷新断言分别按实际消费者制定。widget 配置/绑定 API 独立保留。
10. Report/overlay overview 的质量含运行权限，需要补 RUNTIME 依赖；overlay 的纯外观读取优先窄化 DTO，避免为了订阅完整 SettingsSummary 放大查询。

对应生产源：RecordingApi.kt、CredentialDialog.kt、UI/sharedTest、BusinessState.kt、WorkClassifier.kt、DecisionSnapshot.kt、LazyBackendStore.kt、BusinessRevisionCoordinator.kt、SqliteScopedReadAdapter.kt、SnapshotViewModel.kt、WidgetModel.kt、OverlayViewModel.kt、ReportCalculator.kt。实现检查点必须逐项验证，不能将这份清单当修复结果。

11. 报表边界澄清：现有 StreamingReportAccumulator.accept 首先排除统计区间外记录，重复呼叫 previous 仅在区间内更新。因此本次不得把区间前一通话加入重复扣分；设计中的必要边界输入只在既有算法实际需要时加载，不能改变当前范围内评分。跨午夜工作窗口的前一日配置/时段依赖则仍要保留。

## T04 输入面清单（基于83f6932只读核对）

六个 domain 源码使用15个需独立的契约类型：WorkScheduleSettings、CalendarYear、DateOverride、QuietSettings、QuietMode、ScoreSettings、TimeWindow、CallResult、CallDecision、CallSource、ClassificationFact、WorkReason、WorkStatus、WorkDayKind、DateOverrideKind。不要把 CallerProfile/PositionOption/DataQuality/CalendarInfo/Page/ApiError 等展示聚合复制进领域。

累加器额外需要原生 TimeRange/ScoreSummary/ReportTotals。纯数值/日期/窗口校验移领域，API fieldErrors 和分页/DTO 组装保留 application。枚举转换使用穷尽 when，不以 ordinal、别名或无检查的 name/valueOf 掩盖遗漏。

WorkClassifier 应依赖 WorkInputs（完整工作历史、日历、覆盖、请假、leaveVersion），DecisionInputs 再加白名单、免打扰、统计区间、overlay。DecisionSnapshot 深冻结嵌套集合并只持有这些输入。保留生效时间/版本排序、前历史兜底、前日跨午夜、DST gap/overlap、[start,end)、原始事实不随更正改变。

跨层改动需同时覆盖 application projections/Context 输入输出/CoreSnapshotProjection.safeValue、app 的 CallStore/SettingsStore/ScheduleStore/LegacyValueCodec/WorkConfigCodec/StorageMigration/CallringDatabase/SqliteScopedReadAdapter/AndroidCallLogSource。快照 JSON 必须先转公开 DTO，不能让 serializer 未识别的新 domain 类型静默降级。

File 参数的 AI 客户端/HTTP/流/协程执行仍是 application ports 与 app adapter，不随纯厂商策略迁入 domain。

## T05 额外前置约束

- 业务服务拿到的事务能力应按业务片收窄，不能向每个服务注入可访问所有核心表的会话或重新造一个万能 Context。底层同库资源统一，外层可提供按能力投影的事务接口。
- 生命周期锁可保护完整数据库操作，但 ScopedReadPort.version 的 coordinator 读取必须在资源/SQL 操作锁之外；否则形成 fence→resource 与 resource→fence 反向等待。关闭检查与版本观察需分开设计。
- 所有存储范围/表/行探针先在旧实现记录，再切新适配器。累计订阅与第一片精准写同时落地，不等 T11。
