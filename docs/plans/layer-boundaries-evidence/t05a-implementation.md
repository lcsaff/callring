# T05a：旧实现 SQL 观测与基线

日期：2026-10-20。此切片仅添加 SQL 观测与测试注入，不实施 T05b 窄存储、资源会话或累计订阅，不勾选 T05，也不宣称 P1/P5 已解决。

业务代码基线：`a0b420a9a6b3365379f1e1036c8601131047a897`（T04）；root 随后的准备文档提交不改变业务代码。JSON 的 `baselineCodeSha` 使用其短写 `a0b420a`。本切片在该旧实现之上运行可选探针，未改变查询 SQL、过滤、排序、schema、事务或保存差异判定。S0 `baseline-*.json`、AI golden 和冻结契约均未改写。

## 实现与指标口径

`BackendConfiguration.sqlAccessProbe` 可选注入 final、实例级 `SqlAccessProbe`，Graph 创建实际 SqliteBackendStore 时将其传入；store 的单个 SqlAccess 被旧 StorageSession、四组 Store、TableSnapshot、load、scalar/nextId、sequence、完整快照 preflight/audit 与 SqliteScopedReadAdapter 复用。旧 businessReadCounters 仍保留且读取不会打开数据库。LazyBackendStore 的存储强转保持原状，留给 T05b。

探针只保留固定 QueryTag/SqlTable 枚举与数字，不持有 SQL、绑定值、号码、密钥、异常原文或数据库。它是自身同步的叶子计数器，没有外部 observer 回调，因此不存在 observer 抛错改变事务结果或反取 coordinator fence 的通路。SqlAccess 无探针时直接返回原始 cursor / 直接执行 DatabaseUtils；不读取 cursor.count，不为观测预装载结果。所有 getter/type/BLOB 均转发给原 cursor，保留 TableSnapshot 的 SQLite 类型判定。

- `queryCalls`：实际查询调用次数，按 tag + 依赖表集合聚合，失败调用也包含在内。
- `consumedRows`：成功的 cursor move（first/next/last/previous/position/relative）次数；移动回同一行或再次访问会再次计数。它不是唯一行数、查询完整返回总数或 SQLite 内部扫描量。未遍历的尾行不计。
- JOIN 的一行只计一次；表集合是依赖标签，不将行数分摊或重复归给各表。
- `AGGREGATE` / `NEXT_ID_MAX` / `SEQUENCE` 区分聚合或序列结果；COUNT 返回 10000 仍只消费一个结果行，不表示消费10000条实体记录。DatabaseUtils 保持 SQLiteStatement 实现，成功值读取计一个结果；不引入游标。
- `closedCursors`：游标 close 首次调用次数（包含关闭尝试的 finally 记录）；重复 close 不重复计数。DatabaseUtils 无 cursor，因此此项为0，不等于 statement 未释放。
- `queryOrMovementFailures`：查询打开、游标移动或 DatabaseUtils 值读取的 RuntimeException 次数；消费业务代码抛错时保留已消费行与 close 记录，但不将业务异常冒充 SQL 故障。未添加写入/commit 成功计量，回滚前读到的结果只代表读取事实。
- preflight 动态表名仅映射到固定 SqlTable，未知表归 OTHER，不输出动态标识符；schema 初始化和迁移不属于操作窗口。

## 真实 Graph 基线

复用 LocalAccessContractTest 的1千/1万双号码合成夹具，四项操作分别计量。seed、独立B审计、录音登记/关联/identity查询在窗口外；窗口取探针前后差量。未添加预热。每项后保留完整B profile与所有raw calls相等断言，包括 current/original classification、未知 legacy 编码。完整AI快照计数仍零增量。

| 无关通话 | 操作 | 查询调用 | 消费行/值结果 | calls实体消费行 |
|---|---|---:|---:|---:|
| 1000 | quietRead | 27 | 2101 | 2002 |
| 1000 | targetProfileWrite | 33 | 2106 | 2002 |
| 1000 | targetReport | 27 | 2101 | 2002 |
| 1000 | incomingRegistration | 33 | 2106 | 2002 |
| 10000 | quietRead | 27 | 20101 | 20002 |
| 10000 | targetProfileWrite | 33 | 20106 | 20002 |
| 10000 | targetReport | 27 | 20101 | 20002 |
| 10000 | incomingRegistration | 33 | 20106 | 20002 |

calls实体列仅加总 SNAPSHOT_ROWS + LOAD_ROWS，不包含MAX/序列聚合。实际 Settings 配置只由 TableSnapshot 获取；Settings.load 仅另查 statistics_intervals，因此没有预设每表两次。每项旧fullStateLoads仍1；两项写操作revisionDelta仍2，两项读取仍0。测试只断言真实观测存在和结果不变，未把当前过宽访问量固定为后续永久目标；T06+需改成目标范围硬断言。

原始输出实际位于 `app/build/qa/layer-boundaries/sql-baseline-{1000,10000}.json`，因 app 单元测试工作目录为 app。已经逐项核对允许字段、基线SHA、各项失败计数0后，将相同字节复制到根 `build/qa/layer-boundaries/` 与本文同目录 [1000](sql-baseline-1000.json) / [10000](sql-baseline-10000.json)。根 build 为临时证据，docs副本随源码审查。此报告描述返回/消费工作量，不能据此推断SQLite查询计划/内部扫描行数，也不是性能耗时基准。

## RED → GREEN 与验证

日志位于根 `build/qa/layer-boundaries/t05a-*.log`。最初未提供API的编译失败单列在 red-api；行为RED另有实际执行：

- `t05a-red-behavior.log`：SqlAccessProbeTest两项测试在仅透明转发、空计数器时失败，缺少消费记录；随后 GREEN。
- `t05a-graph-red.log`：真实Graph两项1000/10000测试都在 `quietRead must observe the real Graph SQL chain` 断言失败；接通旧链路后GREEN。
- `t05a-scoped-red.log`：四项探针测试中仅scoped JOIN计量失败（其余3项通过），证明漏接scoped入口；接通后GREEN。
- `t05a-targeted-green.log`：probe 6 + LocalAccess 2 + ScopedRead 6 + StoragePolicy 8 = 22项，失败/错误/跳过均0。覆盖原生类型/NULL/BLOB、六类移动、close幂等、提前退出/消费抛错、SQL失败、回滚读取、JOIN单计、COUNT单行、无探针原cursor与Statement、安全输出、TableSnapshot及preflight。
- `t05a-architecture.log`：check_architecture / check_backend_boundaries通过，架构单测15、backend边界单测4均通过。
- `t05a-full-validation.log`：`./gradlew.bat :contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug --console=plain`，退出0，BUILD SUCCESSFUL（42秒）。actual JUnit：backend 238 / UI 283 / app 226；各自fail/error/skip均0。contracts:test为NO-SOURCE，无JUnit，不能算作已执行契约测试。未变模块任务可能UP-TO-DATE，JUnit来自对应完整suite报告。
- `t05a-additional-compilation.log`：`./gradlew.bat :app:assembleBackendQa :app:compileDebugAndroidTestKotlin :ui:compileDebugAndroidTestKotlin --console=plain`，退出0，BUILD SUCCESSFUL；覆盖构造器/source set兼容。未运行设备测试。

工具链：JAVA_HOME `D:/Apps/zulu17.54.21-ca-jdk17.0.13-win_x64`，ANDROID_HOME `D:/Apps/AndroidSDK`。Gradle既有弃用提示仍存在；未发布APK、未接触生产数据库。代码冻结后交root双审与提交；本实现者不提交。
