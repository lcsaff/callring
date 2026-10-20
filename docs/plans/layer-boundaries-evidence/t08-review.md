# T08 来电与关联检查点

2026-10-21，生产基线 c38f937。来电登记、结果更正、系统日志导入、统计起点/待补全查询、决策输入预热已迁入 calling 窄服务；独立规格与质量审查均 PASS，无本片待修问题。

登记及导入在同库最终事务中读取必要号码/旧新 logId 闭包，保留 SQL 自增、统计事件区间、account、current/original 与旧编码。双向唯一关联复用唯一列表输入算法，完整同号码候选保留窗口外反向度数；先解除全部旧链接，再写行，最后建立新链接。实际提交单次精确发布，重复/no-op 零失效，失败全部回滚。已修复实测冗余 UPDATE。

冷决策输入捕获与发布共用 fence，不读取通话/归档/无关档案详情；同期 quiet/whitelist 补丁的 latch 测试证明没有覆盖。普通统计查询在 SQL 外复核相关 scope。未改变平台期限、session、prepared/commit 和降级协议。升级迁移退出 StorageSession，复用升级事务及同一分类/关联算法，真实 SQLite 触发器验证没有无关配置/审计写入。

旧 calling 全状态业务、coreTransaction/coreScopeValues、DecisionSnapshot/CallCorrelation 的 State 入口、无调用 StorageSession.saveCalls 均已删除；旧 CallStore 也复用唯一 CallingRowCodec。已通过实际反例修正空批次 lease 绕过、IGNORE 的写入探针假计数；Unit insert 统一委托严格实现，代表 Score 双字段失败验证全回滚、零发布。

主负责人独立读取最终日志、XML、lint、四份 JSON，并重跑两守卫与17+4 Python 测试，全部通过。最终 t08-full-gate.log 为 SUCCESS48s、146tasks（17执行/129缓存）；backend273/UI296/app268，失败/错误/跳过均0；contracts:test NO-SOURCE。包含 Debug/lint、BackendQa Kotlin/UI AndroidTest Kotlin 编译；UI31 Warning/3 Hint、app6 Warning，无Error。

1千/1万无关通话下，SQL及窄端口窗口去除规模字段后完全一致。完整同号码候选随该号码历史增长仍可能增长，属于必要算法闭包；消费行不是引擎扫描量，statement 写入数也不是事务最终提交数。没有执行设备验证。

剩余读/完整快照 T09、录音 findCall T10、初始化及旧测试 fixture T13 继续迁移；旧 transaction 已没有普通生产 writer，不能用测试需求永久保留它。RingtoneService 的 notifyChanged、Appearance 的 changedScopeKeys 仍有明确真实消费者。本片通过不代表全计划完成。
