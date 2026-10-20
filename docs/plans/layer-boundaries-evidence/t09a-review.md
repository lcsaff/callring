# T09a 报表、首页与 CSV 检查点

2026-10-21，生产基线82a58d6。独立规格审查和代码质量审查均PASS，无本片待修缺陷。全T09尚未完成，AI源、完整快照和组合读取一致性由T09b收口。

Report/Home使用显式窄端口。同一SQL只读租期内按目标号码和范围读取两次流，原累加器计算完整范围评分、总计和质量，分页只缓冲请求页。直接来源用LEFT JOIN读取，保留原始/当前值与旧编码。首页只取最新可见通话和必要设置/工作事实。相关scope在SQL外复核，三次冲突拒绝；不读取完整业务状态或加热决策镜像。

CSV捕获可重读，文档外写在租期、fence与重试结束后仅执行一次。1197字节golden来自实际旧实现，逐字节一致；SHA-256为10615668b38d370cf649a56f358214e3ab0a2a821eb4da897022cd3a20eeb66a。写失败和外写后错误不重放。回执读取失败是窄边界模拟，ContentResolver样本不能代表真实SAF生命周期验证。

已删除旧Home/Reports工厂与全状态业务、ReportCalculator.timeRange/report、Context.page/meta/read/lock/revision、QuerySupport.paged及SettingsValidation.requireIssues。CallerRowCodec和csvCell保留唯一实现。CoreSnapshotProjection仍使用的helper在T09b随最后消费者删除；录音目标读取T10、旧通知T11、初始化和fixture旧存储T13均有明确退出任务。

主负责人独立核查最终日志、XML、lint、golden与四份JSON，重跑两守卫、17+4 Python测试和diff检查。完整门54秒成功，147tasks（22执行/125缓存），backend284/UI298/app270，失败/错误/跳过均0；contracts NO-SOURCE。UI lint31 Warning/3 Hint、app6 Warning，零Error。包含Debug构建和BackendQa/UI AndroidTest Kotlin编译。

1千/1万无关通话夹具中，去除规模字段后的访问窗口一致：report/CSV各两次流、消费2行，Home最新查询消费1行；全状态装载、完整快照、修订推进均0。这是查询次数及成功消费行数，不能推导SQLite引擎扫描量。实际Home累计loader覆盖相关、无关、noop和执行中更新。设备测试尚未执行。
