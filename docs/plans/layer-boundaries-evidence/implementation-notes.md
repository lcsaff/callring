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
