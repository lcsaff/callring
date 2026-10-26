# T13a 全状态存储协议删除实施证据

本片基于`a8dc87eca1ef48a9068584e8980553752940852c`，仅修改`C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring`。主工作区用户改动未触碰，未stage/commit/push/version/device。本说明为实现候选证据，规格与独立质量结论由负责人记录；T13b、T14、T15尚未完成。

## 实际删除闭包

CallringDatabase.onCreate在原SQLiteOpenHelper创建事务内，先写effective=0的defaultWorkSchedule，再定向写内置calendar_years、holiday/makeup calendar_days与原raw audit及当前时间。MAKEUP_DAY_NAME保持唯一codec常量，不额外物化quiet/score/settings默认key。schema版本3、DDL/约束/索引/immutable audit与已有upgrade均保留。DatabaseInitializationTest验证精确asset事实、raw audit、sequence与无默认settings物化；资产读取失败验证schema/config事务回滚后能正常重试。

删除BackendStore及read/transaction协议、BackendState/BusinessState、StorageSession.load/save/键校验和advanceSequence、ProfileStore/CallStore/SettingsStore/ScheduleStore、TableSnapshot与生产SQLiteDatabase.scalar、SqlAccess.nextId及专用旧QueryTag。defaultWorkSchedule迁domain/model唯一实现。CallerRowCodec、CallingRowCodec、WorkConfigCodec及LegacyValueCodec继续是唯一真实持久编码解释，未按legacy字样误删。

SqliteBackendStore全文件删除，唯一SqliteCoreStorageResource直接持有原SQLite资源及sessions/scoped/fullSnapshot/真实metrics/close能力，不留alias/wrapper/旧构造器。CoreStorageResource删除legacy与stateLoadCount，metrics方法改必需实现；StorageResources删除BackendStore继承/read/transaction/legacy/stateLoadCount，只通过同实例typed能力lease。Graph删除coreStateLoads键，真实SQL、narrow/source/runtime/classification/完整capture计数保留。完整capture计数测实际FullSnapshotReader调用，不能等价解释为已删除的全状态装载计数。

## Fixture与回归迁移

backend的纯classifier/decision测试使用仅决策输入闭包的DecisionFixture，correlation/编码characterization只持独立call map；position比较只持position事实。MemoryCoreUnitOfWork实际实现CoreRead/WriteSession和Quiet端口，分域集合通过具名reader/writer执行，失败提交回滚原事实，不提供全状态read/transaction façade。CallringBackend、Conversation、DecisionPublication/DecisionInputs/CoreSnapshotProjection等全部消费者迁出旧模型。

app/debug/backendQa测试使用真实窄writer，必要的显式历史ID/旧编码/sequence seed由debug专用SqlTestSupport执行明确分域SQL。同连接白盒反射只取本测试持有的SqliteCoreStorageResource.db/sql，不新增production测试接口、不新开第二资源、不提供全字段state或旧read/transaction形状。fixtureSql使用原连接写事务和deferred FK验证以支持历史fixture插入顺序；被测普通操作仍走真实writer/发布。全事实断言明确用fullSnapshot.capture().let，其work事实在facts.work下，无旧模型适配层。

设备fixture中的duration/score/calendar replacement/concurrent whitelist toggle/ignored leave rollback改实际窄writer；旧map键协议不再存在，其回滚测试改为现行position约束+其它写入原子回滚。精确sequence、v1/v2 migration、unknown raw列、original facts和immutable audit断言保留。设备类唯一改名SqliteCoreStorageResourceDeviceTest，同步verify_backend.py显式FQCN。没有执行设备。

1000/10000局部quiet/settings/profile/calling/report/recording/source访问仍沿原测试规模及SQL table/row范围、零完整capture和零无关刷新断言执行。旧fullStateLoads/coreStateLoads样本不能机械改名成新指标：历史baseline样本保留原貌，当前样本由本次真实测试输出复制，summary逐SHA记录。

## 守卫与真实失败分类

新删除守卫覆盖所有src/main/test/debug（统一源集合扫描），拒绝旧协议声明/引用/调用以及旧资源legacy投影；保留FullSnapshotFacts、codec、JSON/sealed/opaque值自身合法cast。现有具名操作数字/契约/平台纯净守卫继续执行，无整文件豁免。

`t13a-guard-red.log`在旧guard上得到26 tests / 24个预期assertion failures（8类反例×3 source sets），随后26项通过。签名迁移期间的Kotlin编译准备失败独立记录，不充当行为RED。首轮完整app JVM运行332 tests / 4 failures：fake调用故意拒绝的port、重复caller seed及新测试source漏接原SQL probe；定位修复后定向StorageResources/Caller SQL/Probe/初始化测试通过。不会将这些调查失败称为预期RED。

## 候选与后续

初次候选71个相对BASE变动或untracked的.kt/.java文件，按sorted相对路径+LF+rawbytes（删除DELETED）+LF聚合：`6b2b960dcf7051f804a45b69472e4fb6ac5961604d1f86529153ac1252315b3f`。逐文件manifest为`build/qa/layer-boundaries/t13a-source-freeze.json`。完整门、XML WindowsPath组件casefold排序聚合、lint、日志、当前fixture逐SHA与退出状态见`t13a-verification-summary.json`；只有实际运行后填入结果。

T13b保留目录/职责归位、bootstrap查询投影、UI bridge与designsystem、系统组件薄壳及最终全文件清单/PROJECT/README；T14统一CI、T15设备及最终验收未执行。本片不将T13整体标完成。


## 规格与自审修复

负责人规格核对确认旧协议消费者退出、schema/codec/ID/回滚/升级/CSV/历史分类及1000/10000范围与无关刷新行为保留。指出架构guard还需阻止四个已删Store与fullDiff/stateLoads回流，初始化测试需核对calendar metadata及落盘schedule完整事实。自审另发现旧计数退出后出现重复completeCaptures断言/JSON put。

本轮补精确旧CallStore/ProfileStore/ScheduleStore/SettingsStore词、fullDiff/stateLoads的声明/引用跨main/test/debug反例；36个新子例在原guard下实际失败（28 tests/36 failures），修后28项通过，SqliteCallStore/SqliteCallerStore/RecordingSettingsStore等真实窄类型合法。指标去重并让样本直接输出实际captureDelta。初始化补year/source/published/version与完整持久schedule解码比较；首次期望遗漏codec原有周末6/7空列表，14项focused产生1个测试期望失败，核对唯一codec原实现后仅修正显式历史持久期望，未改codec或默认日程。第二次focused14项通过。

修前完整门exit0，backend360/app334实际执行，UI312缓存，contracts NO-SOURCE，全部0 failure/error/skipped，155 tasks/22 executed/133 up-to-date；日志t13a-fullgate.log。修前源码manifest、summary与43份安全JSON各保存在*-before-review工件中，不冒充修后结果。

修后71路径源码hash为`15e9b55a0e5e4564eac20f94fda819f86954bca5f1887441a758462b24e835f0`，同一manifest路径已更新，旧候选保留t13a-source-freeze-before-review.json。修后完整门使用t13a-fullgate-after-review.log，最终实际状态以下文及summary为准。T08已删除的fullDiff/coreScopeValues继续零引用，本片未重新引入。


## 修后统一完整门

`t13a-fullgate-after-review.log`实际exit0：BUILD SUCCESSFUL in 1m5s，155 tasks / 5 executed / 150 up-to-date。app334本门EXECUTED，backend360 UP-TO-DATE（修前本片完整门已实际执行360），UI312 UP-TO-DATE（本片无UI源码变化），contracts NO-SOURCE；全部0 failure/error/skipped。assemble与backendQa Kotlin状态、AndroidTest编译状态、lint实际task状态见summary，不把缓存/NO-SOURCE说成重跑。

修后28/5项正反例及双guard通过，120 JVM生产源。lint沿原报告UI30 Warning+3 Hint、app6 Warning，无新增error。43份安全JSON原字节复制到t13a-fixtures，每份source/copy/bytes/SHA见summary；两份sql-baseline文件明确为a0b420a历史非重生成证据。全部既有tracked通用名及task前缀样本都保留原貌，不机械改旧计数字段。本次真实completeCaptures与旧fullStateLoads不同义，当前观察由新archive及summary引用。

修后backend/UI/app XML聚合分别`2eacfc4f9e6dd703f56520137b0afbca45c2307e9c6ce4d92fba9cbd5f27f9df` / `309b62e48f7949b9113b3682febd164c19b48731a4e622775ba0b8fe060e6acf` / `1c66624f188f1bf83cbdd677df5c10d2b856e9eb7240429ba679514ba3198467`，按WindowsPath组件casefold排序，再用相对p.as_posix()+LF+原始XML+LF聚合。全部日志/manifest/lint/fixture SHA与时间戳、任务状态、真实失败分类在最终summary。规格delta已PASS，独立质量审查仍由负责人另记。没有执行设备或提交。


## 独立质量 P2 修复与当前候选

独立质量复核指出LocalAccessContractTest原通用零完整capture断言在去重时被删除，只剩score/quiet专门检查，导致targetProfileWrite/targetReport/incomingRegistration仅记录指标。已核对并恢复所有measure操作统一计算一次实际captureDelta、断言为0，JSON记录同一值；没有伪造常量计数。定向LocalAccessContractTest的1000/10000两项实际通过。同期删除BackendPorts旧协议悬空KDoc及两个无消费者import，仅注释/import清理，无签名/行为变化。

当前71路径源码聚合为`72810be70942869b78d21bfff34b81a89ccb10db830ef906b5ac76a35ea89c9f`。第二候选的manifest/summary/43JSON均归*-before-quality，旧日志t13a-fullgate-after-review.log原样保留。本轮最终门使用t13a-fullgate-after-quality.log，最终结果以最新summary为准。


## 质量修复后的最终实际验证

`t13a-fullgate-after-quality.log`实际exit0：BUILD SUCCESSFUL in 1m13s，155 tasks / 16 executed / 139 up-to-date。backend360和app334均本门EXECUTED，UI312 UP-TO-DATE，contracts NO-SOURCE，全部0 failure/error/skipped；总1006项XML中694项本门实际执行，312项缓存不冒充重跑。assembleDebug/backendQa Kotlin实际执行，UI AndroidTest Kotlin缓存、app AndroidTest Kotlin NO-SOURCE。lint报告仍UI30 Warning+3 Hint、app6 Warning，无error。

最终backend/UI/app XML聚合分别`1a63be2a5bf853742c10d3552726a1042e2374191b8493f241ab5380d4543b90` / `309b62e48f7949b9113b3682febd164c19b48731a4e622775ba0b8fe060e6acf` / `3208ce6a68e29ed1d75a78a84694c40d33b0ec0a542f9607efc168672638bde3`；仍采用同一WindowsPath排序和相对路径/rawXML字节聚合算法。最终双guard及28/5正反例均0退出，120 JVM源。43份当前安全JSON保存t13a-fixtures，第二候选43份副本保留t13a-fixtures-before-quality，原样本与原始输出未删除。

最新summary明确最终源hash为`72810be70942869b78d21bfff34b81a89ccb10db830ef906b5ac76a35ea89c9f`，规格/质量最终delta由负责人记录。此后仅补证据，不再变动源码；无设备/提交/版本操作。


负责人已转达原规格与独立质量最后delta均PASS，LocalAccess P2关闭且无剩余发现；最终71路径manifest由两位审查方独立核对一致。最终diff/未跟踪EOF及尾空白检查通过。源码保持`72810be...`不变，Gradle已无运行会话，所有权交回负责人做独核/checkpoint。T13b–T15仍按实际余项继续，不由T13a代为关闭。
