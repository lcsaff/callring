# T07 档案检查点

2026-10-21，生产基线 c5d62d5。所有 CallersApi 操作及 UiEdits.profile 已迁入 application/callers，通过窄端口与同库 SQL 会话完成；独立规格、质量审查均 PASS，无本片待修问题。

编辑只读取目标档案与岗位闭包；空搜索 SQL 分页，非空搜索保留原 Kotlin Unicode 匹配和完整总数，只缓冲请求页。Overview 流式读取 A 的必要事实，评分复用原领域累加器，数量、最新记录及质量仍覆盖全部可见 A；详情仅目标及直接关联 screen。查询在提交锁外执行，前后复核相关版本，结果携带读取前版本。

档案写入在最终事务中合并 dirty 字段；实际变化单次发布，无操作零失效但记录命令。白名单仅补最新 warm 决策输入，cold 不预热。删除保留格式号码规范化、跨批次先解除全部目标 outgoing 链接、外部 FK 保护、实际数量复核与回滚；发布真实 call:id 及号码冲突键。

真实 Graph 的 A/B 编辑与报表验证相关提交一次重载、无关和无操作零重载。延迟保存完成竞态已有回归。页面与悬浮窗统一必需的草稿写函数，已删除 nullable/full-field 回退及 whitelistTouched 旁路；旧 CallersService、旧 CallerProjection 包装、UI 预合并、无消费者 BackendContext.mutate/events/allDomains 已删除。ReportCalculator/CoreSnapshotProjection 等真实消费者按 T09 删除门迁出，旧存储及通知按后续任务继续收口。

主负责人独立核对最终完整日志、JUnit XML、四份安全 JSON，重跑两守卫及 17+4 Python 测试，均通过。t07-final-full-gate.log 为 BUILD SUCCESSFUL，1m36s、146 tasks（41 执行、105 up-to-date）；backend272/UI296/app254，失败、错误、跳过均0。包括 Debug、lint、BackendQa Kotlin 和 UI AndroidTest Kotlin 编译；contracts:test 为 NO-SOURCE。UI31 Warning/3 Hint、app6 Warning，无 Error。

1千与1万无关通话的两对证据去除夹具数量后内容一致。SQL 计数表示查询和消费行，不表示引擎扫描；GLOB 候选查询可能内部扫描，不宣称零扫描。603 档案 Unicode、406 号码格式变体跨批删除、忽略写入/FK 回滚等已有真实 SQLite 验证。未执行设备测试；本检查点不代表全计划完成。
