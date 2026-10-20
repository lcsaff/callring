# T09a 报表、CSV、首页实施证据

2026-10-21。生产基线 `82a58d65465655c313a70daa2288a2d0c232b9ec`，工作树 `codex/layer-boundaries`。仅本片实施冻结；不表示 T09b、T10–T15 或全项目完成。未提交、暂存、合并、推送或发包。

## 实施与读取闭包

- `application/report/ReportService` 显式依赖 `ReportReads`、协调器、dispatcher/clock、runtime 方法及文档写函数；`application/home/HomeService` 仅依赖 `HomeReads`、协调器、dispatcher/clock、runtime 与 ringtone 方法。无 BackendContext/BackendState/CoreUnitOfWork 注入，无具体 Store 强转。
- `CoreReadSession` 组合真实窄能力；`SqliteCoreUnitOfWork` 的 report/home 与既有调用共享 SqlAccess、数据库连接、事务、active/read-only lease 和 StorageResources 生命周期。
- 普通报表在同一 SQL session 内两次定向读取 canonical number、`hidden=0`、`[start,end)` 的事实：升序 time/id 喂唯一 `StreamingReportAccumulator`，降序 time/id 在应用层按既有解码后的 filter 计完整 total，仅保请求页 CallDetail。不存在普通页全 CallerDetail 列表、其他号码历史或逐行 origin 查询。
- `SqliteReportCalls` 只过滤/排序，direct linked screen 单 LEFT JOIN；`CallerRowCodec` 从原 SqliteCallerStore 读取投影机械提取，仍使用唯一 LegacyValueCodec。provider 拥有 current，screen 拥有 screeningAt/decision/note/original；MERGED 映射复用 CallerProjection。
- totals 仍使用领域 `ReportTotalsAccumulator`（经 StreamingReportAccumulator），质量使用完整目标范围 visible rows；不随详情 filter/page 改变。范围前通话从不建立 repeat previous。保留 unknown duration、PENDING/UNRESOLVED、未核验、本人岗位及inactive角色语义。
- Home 只读 global latest visible LIMIT 1（time/id DESC + direct screen）、必要 WorkHistory、open statistics EXISTS、overlay key、quiet mode key。ring/runtime 在 SQL 外；同次 clock 只采一次供 work/meta，保留 WorkClassifier 的 zone/DST/next boundary。
- 普通读均 marker before → SQL/平台观察 → 相关 keys after，三次上限；meta.dataRevision=before。SQL callback 不拿 fence；文档 IO 不持 SQL session/fence。相关 race 重读，持续变化返回 CONFLICT；无关 profile/score 不使 Home 重读，无关 B profile 不使 A report 重读。

## CSV 字节与外部写边界

- CSV 使用原列顺序、BOM、UTF-8、CRLF、号码前单引号及原 csvCell 公式/逗号/引号/换行处理；明细降序，包含 uncounted。捕获范围内全部 A 导出必要数据后退出重读与 SQL lease，再仅一次目标写。
- 既有 `CallringBackendTest.csvCapturesMyPositionBasisAndCompleteTotalsInTheReportSnapshot` 的输入/断言样本未改。临时恢复 HEAD 的旧 Reports/Home/装配源运行该样本，捕获原字节，恢复新源码后增加逐字节比较。资源 `backend/src/test/resources/report/t09a-original.csv`：1197 bytes，SHA-256 `10615668b38d370cf649a56f358214e3ab0a2a821eb4da897022cd3a20eeb66a`。没有新 formatter 自算 oracle，也没有生产双实现。
- 原实现捕获日志 `build/qa/t09a-csv-original.log` SHA-256 `e22d2236156c24897b319ba9325f1cf9af1b3e9c7819ea89f6919ef72b5ee98b`。本片临时 baseline 源备份、format 临时源及重复原始字节文件已清除，仅保留字节资源与运行日志。
- 窄 fake 的可控 related race 证明 capture=2、write=1；外部 write 内另一线程可取得协调 fence，且 SQL lease 已退出。写失败/写后抛错模拟读回失败均不重放外部写。这里的“读回失败”是窄文档写边界模拟，不是真实 provider 或 ReceiptVerifier 读回验收；组合读回一致性仍属 T09b/T12。
- 真实 BackendGraph + AndroidDocumentStore/ReadbackDocumentStore 在 Robolectric content resolver 注册流上运行 CSV；通过 BOM 起始写计数确认实际目标流只写一次。未宣称真实 SAF provider 生命周期已验。

## RED 与测试范围

- `ReportReadContractTest` 1k/10k 无关 B 的真实 BackendGraph 行为 RED：report full state loads expected 0 but 1。`build/qa/t09a-red.log` SHA-256 `bcaf07b974d32c072f07788acb85c88bc94dc6c3e2a9bfa32b07a37ceec78423`。首次误写 ExportReceipt 字段导致的编译失败不计行为 RED。
- Home 累计单航班测试临时退回原 Snapshot 双路径，真实 loader 的 related/noop 与 in-flight 测试均失败（2 failures），恢复新路径通过。`build/qa/t09a-home-red.log` SHA-256 `1f749a5626a1af846c4e3ce6ddcb33c441f3e104d601dc1093b8ff996695b8d5`。先前测试类型拼写/返回类型编译问题已修复，不计 RED。
- 独立窄 `ReportHomeReadTest` 11 项：DST 23/25小时、区间前 repeat、target范围/uncounted/quality、filter与Long分页、冻结DTO、first-error、default zone、direct-linked事实、相关重试/全部依赖持续3次CONFLICT、无关marker保持before、一次clock、CSV转义/排序/一次外写/失败不重放。仅声明窄能力，无万能假context。
- 真实SQL夹具在各入口外核对 B 全持久列、profile 和 calendar_audit 不变；包含 hidden screen、原始decision note/current-original、非canonical存储号码不纳入canonical报表。新夹具修复过 logId 唯一、引用FK、copy保留旧 legacyDecision 的问题，均与产品实现缺陷区分。
- `ScopedTransactionTest` 为新 report/home 方法补 closed lease（含空范围visit）拒绝；既有 read-only禁止写/事务rollback测试继续执行。
- `HomeViewModelTest` 实际 loader 对 CALLS/WORK_CALENDAR/QUIET_HOURS/STATISTICS/OVERLAY/RINGTONE/RUNTIME 变化加1，对profile/position/score无关变化加0，noop加0，in-flight合并与旧数据拒绝。T07 `CallerAccessContractTest` 的真实 A/B ReportViewModel related1/unrelated0/noop0、未引用岗位0继续执行。

## SQL 与窄端口证据

最终完整门的 `app/build/qa` 输出复制为本目录 `report-home-access-{1000,10000}.json` 和 `sql-report-home-{1000,10000}.json`。这些是 query invocations / successful cursor moves consumedRows，不是SQLite engine scans，不用任意耗时阈值替代范围证明。标签不含号码、SQL参数或文本。

每个规模：report/CSV 各2次 reportVisits、calls consumedRows=2；Home homeLatest=1、calls consumedRows=1。三个入口 fullStateLoads=0、completeCaptures=0、revisionDelta=0；没有加热 DecisionInputs。Home不读callers/positions/score/recording。1000与10000无关 B 下定向读取范围相同。

## 已删与后续真实消费者

- 已删除旧 HomeService/ReportsService 的 with(context) 工厂与业务，旧 ReportCalculator.timeRange/report、BackendContext.page/meta/read/lock/revision、QuerySupport.paged、旧 SettingsValidation.requireIssues。csvCell 唯一实现迁入 application/report，既有 DomainRegression 样本仅更新import。
- T09b：CoreSnapshotProjection 仍真实调用 score/quality/reportTotals/visible/currentSchedule/stats、quietCopy/scheduleCopy及完整 BackendState capture；最后消费者退出时删对应旧helper，不提供新窄服务fallback。SqliteScopedReadAdapter 的业务计算/持久JSON、sourceVersion/组合一致性、完整快照主体仍未迁。
- T10/T11：BackendContext 仍有 Runtime/Ringtone/Widget及对应装配消费者；number仍供WidgetBindingsService。剩余 Home/Overlay/widget/oldDataChange消费者与旧feed最终删除为T11，不宣称全P2完成。T10 recording findCall及精确runtime/recording scope出版闭包按原计划。
- T13：BackendStore/BackendState、SqliteBackendStore旧read/transaction、StorageSession、onCreate/fixture旧协议仍有具名消费者；upgrade已于T08迁出。按既有t13-removal-preparation退出，不为本片新增兼容接口。

## 最终验证

环境：`JAVA_HOME=D:/Apps/zulu17.54.21-ca-jdk17.0.13-win_x64`，`ANDROID_HOME=D:/Apps/AndroidSDK`。

```powershell
.\gradlew.bat :contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug :app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin --console=plain
```

新增/实改区域先使用本地临时 ktfmt 四空格格式，再运行完整门。首轮完整门仅暴露 CsvCell 迁包后 DomainRegressionTest 旧显式import未删的编译问题；删除失效import后必要重跑。原首轮 `build/qa/layer-boundaries/t09a-full-gate-initial.log` SHA-256 `383c37d89388fde728714a7db77267a9e2aa4eb78c5e86ce7228a46c5ffbb74e`，不当作行为失败或验收通过。

最终 `build/qa/layer-boundaries/t09a-full-gate.log` exit0，BUILD SUCCESSFUL in 54s，147 actionable tasks（22 executed / 125 up-to-date）；SHA-256 `49e87a51f9139a9c59bc32e1fc8dfe211a391d6bc275cd3879856a9be5f268eb`。最终源门后未做生产代码调整。

| 模块 | XML suites | tests | failures / errors / skipped |
|---|---:|---:|---|
| contracts | 0 | 0 | 0 / 0 / 0（NO-SOURCE） |
| backend | 58 | 284 | 0 / 0 / 0 |
| ui | 70 | 298 | 0 / 0 / 0 |
| app | 58 | 270 | 0 / 0 / 0 |

UI lint 31 Warning / 3 Hint / 0 Error；app lint 6 Warning / 0 Error。两个架构脚本 PASS，`python -m unittest discover -s tools -p test_architecture.py` 17项与 `test_backend_boundaries.py` 4项全部OK，`git diff --check`通过。契约、旧81工具golden/hashpin、schema未改。

最终门输出证据SHA-256：

| 文件 | SHA-256 |
|---|---|
| report-home-access-1000.json | `0db8932994caeb6c107d3aef5f2100d3ec6feb875bff645c55ac8e934c2c3228` |
| report-home-access-10000.json | `591d6a8e6dffcd20801c7e84e6acc47c3bac5033725ae5c17b1dcaa4d19621da` |
| sql-report-home-1000.json | `d2052ea14f4b763393ca76736b601c5024dfadbf0e0eb176d6030003e154fe3a` |
| sql-report-home-10000.json | `d2052ea14f4b763393ca76736b601c5024dfadbf0e0eb176d6030003e154fe3a` |

无device运行、安装、真机Telecom/权限/SAF生命周期冒烟；仅编译 backendQa 与 AndroidTest。Debug APK是构建验证产物，不是发包交付。等待根的规格→质量审查，仅冻结T09a。
