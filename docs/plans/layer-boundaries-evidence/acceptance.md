# 分层解耦最终验收

2026-10-21。A0–A8 自动验收通过，A9 真机行为未验证。五项耦合整改、内部目录归属、六个系统组件的控制器提取及旧迁移实现清理已完成；生命周期终审遗漏已修复，不留待后续实施。

验证源码提交 `911369c82b657f0c224e6070ccd3005a8ac36c50`，版本 `0.3.9 / 12`；后续提交只补验收文档。原227项迁移清单和当前360项归属均闭合，无遗漏/重复/陈旧路径；当前 app92、backend140、contracts23、ui105。依赖为 app→ui/contracts、app→backend/contracts，ui/backend→contracts；纯 domain 不依赖 contracts/Android/UI。

| 验收 | 结论与实际证据 |
|---|---|
| A0 分层/归属 | PASS。两守卫、所有source sets、50/5正反例及真实UI编译类路径检查；T14实际注入backend依赖失败、精确恢复后通过；360项归属闭合。 |
| A1 局部访问 | PASS。当前1千/1万固定夹具归档43份安全副本，其中2份SQL基线是历史样本，未再生成。当前普通入口160个完整捕获计数断言均0；设置/档案局部表、行和端口范围由实际契约测试验证。旧全状态模型/协议/差异保存已删除。 |
| A2 结果/事务 | PASS。定向SQL、回滚、唯一ID、跨实例去重/关联、导入幂等、报表分类与历史更正回归通过；原CSV1197字节/7CRLF及SHA保持。历史批更正与显式完整快照仍按真实必要范围读取。 |
| A3 刷新 | PASS。累计实体失效为唯一协议；真实VM/widget/overlay loader、A/B隔离、慢消费者、恢复、时间边界、并发和本地写去重回归通过。1千/1万样本无关报表重载增量均0，见T11审查及最终测试XML。 |
| A4 具名AI操作 | PASS。唯一81项OperationCatalog贯穿执行、确认、作用域及回执；乱序/目录负例通过，全部81项外部协议冻结SHA保持。数字元数据选择/ordinal负例进入CI。 |
| A5 窄依赖 | PASS。BackendContext及替代缺能力默认、旧工厂删除；服务用明确窄能力，组装留bootstrap，各组假端口测试通过。 |
| A6 存储/生命周期 | PASS。非SQLite资源替代、懒打开/计数不打开/并发关闭及关闭后拒绝通过。终审发现的保留查询重新开库已在4e7fdd2修复；3+23项定向测试、完整门及独立双审通过，原来电排空测试保留。 |
| A7 取消/隐私/回执 | PASS。核心/录音部分失败、事务取消、generation、授权、密钥脱敏、COMMITTED_UNVERIFIED与闲聊零源查询回归保持；未修改协议或持久schema。 |
| A8 完整自动门 | PASS。最终 `python tools/verify_backend.py` exit0；Python50+5+12、两守卫、contracts:test明确NO-SOURCE、JVM1036项结果全部通过；构建/lint及发布工具正反例通过，详见下表。 |
| A9 真机 | 未验证。ADB仅192.168.10.149:5555 offline；未安装/运行instrumentation。真实SIM/待机/重启、widget/overlay系统行为、覆盖升级及真实下载保留缺口，未清理用户数据或覆盖主安装。 |

| 最终JVM门 | 项数 | 状态 |
|---|---:|---|
| backend | 366 | UP-TO-DATE；前一相同业务源码完整门实际执行366项 |
| ui | 310 | UP-TO-DATE；沿未变UI源码的已验证结果 |
| app | 360 | EXECUTED |

上述结果失败/错误/跳过均0；没有将缓存说成重跑。最终实际执行UI类路径检查、app Debug/Release/backendQa构建、UI/app lint；UI Debug与两AndroidTest APK构建UP-TO-DATE。lint无Error/Fatal，UI19 Warning+3 Hint、app6 Warning。5个APK实际aapt包名与metadata一致，3个应用变体版本12/0.3.9（QA带既有后缀），AndroidTest APK不要求发行版本。发布工具19/19、workflow正反例9/9通过，未发布Release。

旧全状态read/transaction、BackendState/StorageSession、BackendContext、nullable旧通知/第二publisher/双loader、数字业务分派、存储legacy投影及迁移forwarder/alias/default能力已关闭。保留的是唯一数据编码解释器和外部稳定协议标识；历史证据与现行业务实现分开。六组件稳定FQCN未变，唯一工作流分别在 RuntimeEventController、CalendarSyncJobController、CallLogSyncJobController、RecordingJobController、CaptureServiceController、PlaybackServiceController，21项直接控制器回归已验证。

[最终机器摘要](acceptance-summary.json) SHA：f48f1c09378b00d723bb5d7a010ea9f0f89c3c7e5902cf1a0b752268dda88925。原始日志/JUnit/lint及43安全副本归档于本地 `build/qa/layer-boundaries/t15-final-run`、`t15-final-fixtures`，索引及SHA在摘要中。指标记录调用范围与消费行，不冒称SQLite物理扫描量。各阶段独立审查见本目录t03至t15-lifecycle-review；[删除账本](legacy-removal-ledger.md)记录最后消费者闭包。主线推送及该最终提交远端CI结果在执行后单独报告。
