# T06b 历史设置检查点

2026-10-21，生产基线 db9b9a2。工作安排、日历/覆盖、请假及草稿入口完成窄事务迁移；独立规格与质量审查均 PASS，无剩余本片阻断问题。结合 T05b quiet 和 T06a 简单设置，T06 设置切片完成；其余业务仍按后续任务迁移。

schedule 保留每次追加版本、effective 和不重分类语义，空历史边界也有验证。日历准备可取消，最终事务复核当前 metadata 后保留同源同版本 published；请假仅改备注仍推进全局 leaveVersion。必要历史更正按256条批读取 id/number/time/current，复用原领域算法；配置、审计、全部 current 同库原子提交。保留原始分类、未变 raw 编码、makeup day 名称、ID序列、历史时区/DST/跨午夜/跨年行为。

实际变更单次发布，no-op 不写、不扫描 calls、不推进修订。RAISE(IGNORE) 及第二批中途失败验证回滚与零发布。WORK_CALENDAR 域内 schedule/calendar/leaves 分别失效；实际 VM 单次重载、无关子资源零重载，加载期间变化不会丢失。Summary/Overlay summary 的 zone 只订阅 schedule。输出集合冻结，条目时钟单采样；warm 决策镜像只补最新输入中的工作子集。

旧 SettingsService 历史业务 bodies/replaceCalendar、work/leave premerge、无调用验证/投影助手、WorkClassifier.reclassify(BackendState) 入口已删除；旧测试和设备 fixture 同步迁移，无为旧测试保留的生产适配。currentSchedule/scheduleCopy 等仍有 caller/report/full snapshot 消费者，按 T09 删除；其余 Context/全状态/旧通知仍按删除清单收口。

T06a 检查点后的取消差异已通过真实反例复现：进入协程再取消，三处无效 quiet/score/position save 原会提前返回验证失败；现在先检查取消，保持零存储访问。发布日期验证顺序的反例也已纠正。实际 RED/GREEN 与准备失败区分见 t06b-implementation.md。

主负责人独立读取完整门、JUnit XML、lint 和四份 JSON，并重跑两守卫及17+4项 Python 测试，均通过。t06b-full-gate.log 为 BUILD SUCCESSFUL1m47s、146tasks（44执行/102up-to-date）；backend263/UI295/app243，失败/错误/跳过均0。包含 Debug/lint、BackendQa Kotlin/UI AndroidTest Kotlin编译，contracts:test为NO-SOURCE。UI31 Warning/3 Hint、app6 Warning，无Error。

四份安全证据中局部操作跨1千/1万数据范围一致；必要更正 calls 消费1001/10001行、批查询5/41，按既有全历史闭包线性处理，未冒称所有操作恒定。所有普通设置入口全状态/完整快照增量为0。没有执行设备测试。
