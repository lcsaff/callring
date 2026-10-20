# T08 calling 实施与验证证据

状态：本切片 CODE FREEZE，待主负责人规格审查与质量审查；未提交、合并、推送或发包。没有勾选主计划/全项目完成。

生产起点 `c38f937a45bd94f03da5f6e390ed54032db955b9`（T07）。验证时 HEAD `90cafba84dcc3a52ee5b50bd11895a5de88e66ca`，后续 HEAD 变动仅为主负责人准备文档提交；T08 源码仍为工作树差异。只操作 `C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring`，未访问/覆盖原项目用户修改。

冻结源码集合 SHA-256：`35363449b26e6c1468512f65f68eb3b754fb9a0f2fff81735cbdd82fa731b632`。集合为 `git diff --numstat` 的真实内容差异路径与未跟踪源码路径之并集，仅 `.kt/.java/.py`，按路径排序，逐项拼接 UTF-8路径、NUL、LF规范化文件字节、NUL；共 31 个源码/测试文件，不包含本文或 JSON。Git 的 CRLF 文件状态噪音没有内容差异，未纳入集合。

## 实现边界

- `IncomingCallService`、`CallLogImportService`、`DecisionQueryService` 只声明 CallingTransactions / DecisionInputsTransactions / coordinator 等实际窄能力，没有 Context/BackendState/CoreUnitOfWork 依赖。CallringBackend 保留原同步公开签名与异常边界，显式委托服务；平台 IncomingCallCoordinator 的期限、prepared/commit、session、调度和降级实现没有替换。
- CoreUnitOfWork 组合 CallingReader/Writer 和 DecisionInputsReader；SqliteCoreUnitOfWork 的所有方法仍走同一个 SQLite 事务、活动/只读 lease 和 StorageResources 生命周期。calling SQL 适配不反向获取 coordinator fence。
- 登记按规则号码及事件时刻统计区间授权；trim account，保留 `number:at[:account]` 编码，重复 key 返回原 id/原 original，零写入/零revision。SQL AUTOINCREMENT 提供新 id，空且非白名单档案仅缺失时创建，不触决策镜像。
- 导入先按批次全局 logId 查行，按输入顺序覆盖 byLog；只对被原规则接纳的输入验证 logId/duration。缺省 account 保留既有值。只有 new/number/time 变更重分类，account-only / duration / result 不重分类；original 永不覆盖。批次 WorkHistory 缓存一次，统计存在性按事件时刻缓存。
- 原始行使用唯一 CallingRowCodec，更新仅包含改变列，保留未改 account / screeningKey / 原始 reason/calendar/source/result/decision 编码。旧 CallStore 的剩余全快照消费者也复用该 codec，没有为旧生产接口保留第二算法/codec。
- 同号码完整候选闭包按 time,id 稳定顺序读取，正反候选均 cap 2 判歧义，±5000 inclusive/account unknown 规则保留。center 只限定 ±10000 写集。关联先解除所有变化旧引用，再写行，最后建新引用；FK/UNIQUE 未禁用。
- 每次实际事务用 CommandCommitter + 精确 call:id / calls:旧新号码 / calls / 新档案字段与exists keys 单次发布 CALLS/REPORTS/必要 CALLERS；no-op 零publish。失败批次或 IGNORE 退出后全事务rollback，零revision。SqlAccess 的 Unit insert 委托同一 insertReturningId，IGNORE 返回-1只计0 statement writes并拒绝成功；没有第二插入实现。

## 决策镜像与查询

冷预热只读 whitelisted number、WorkHistory、quiet、statistics intervals、overlay；不读 calls/archive/无关档案详情。专用不可变 DecisionInputs 深冻结后发布。冷捕获与发布位于同一输入提交 fence 内，这是设计明确的冷预热例外；settings/whitelist patch 也在同 fence 内从当时最新镜像更新，不能让旧捕获覆盖较新patch。DecisionPublicationTest 使用 latch 让冷 SQL 输入捕获与 quiet+whitelist patch 交错，证明最终两项修改均保留。

普通 statisticsStart / hasRecentPending 在 SQL 外先取scope marker、查询后复核相关scope，最多3次；不持 fence 做普通查询，不把结束marker贴给旧数据。statisticsStart 是 MIN(start)，空0；pending 是 EXISTS，保留 hidden/count/logId/time 原谓词，没有添加 result 条件，并保留 Long 极值饱和区间。普通通话登记/结果/关联变化不重建已预热镜像。

## 真实 SQL 访问证据

`CallAccessContractTest` 通过真实 BackendGraph 在1k/10k无关号码B通话夹具上逐操作测量，seed、Graph资源预打开和B原始全列审计均在测量窗外。每步断言全状态装载与完整快照均零；审计B的profile与calls全部持久列，保持完全相等。标签固定，JSON不含号码、SQL文本、参数或私文。窄端口计数是真实 Session 方法调用，不是常量零。

复制文件：`calling-access-1000.json`、`calling-access-10000.json`、`sql-calling-1000.json`、`sql-calling-10000.json`，源自本次 `app/build/qa/layer-boundaries/`。除 unrelatedCallCount 外两规模所有 SQL 窗口及端口计数一致。

| 操作 | 1k/10k calls消费行 | revision delta |
|---|---:|---:|
| record | 2 / 2 | 1 |
| duplicate | 1 / 1 | 0 |
| decision | 1 / 1 | 1 |
| decisionNoop | 1 / 1 | 0 |
| import | 3 / 3 | 1 |
| statisticsStart | 0 / 0 | 0 |
| pending | 1 / 1（EXISTS结果） | 0 |
| coldDecisionInputs | 0 / 0 | 0 |
| warmDecisionUnchanged | 1 / 1 | 1 |
| importNoop | 1 / 1 | 0 |

首版允许读取完整同号码候选：写窗口外的候选会影响反向唯一度数，不能把候选截断为写集或 LIMIT1。当前固定A闭包只消费2/3行，B从1k到10k不增加测量读取；同一号码历史增长时该号码完整闭包仍可能增长，这是有名局限，不宣称常量候选或引擎扫描行数有界。queryCalls 是调用数，consumedRows 是成功 cursor moves，writtenRows 是 SQL statement effects（可在随后rollback），不是SQLite内部扫描或已提交行数。

## 升级迁移与删除审计

StorageMigration.upgrade 的1→3/2→3均退出 StorageSession.load/saveCalls，直接使用 onUpgrade 已有 db transaction，不新开资源会话、不推进业务revision、不加schema版本。screen current 用同一 WorkClassifier(WorkInputs) 定向更正；v1继续保留旧screen id/原original、provider SQL自增新id/原观测，再用唯一list patch关联。CallingMigrationTest 在实际Robolectric SQLite运行v1/v2升级，并用 triggers 禁止 callers/positions/settings/config/calendar/overrides/leaves/audit incidental writes，检查原事实、link及FK；这不是设备执行。

已删：BackendContext 的五个calling业务体、coreTransaction、coreScopeValues、全状态预热捕获；迁移最后消费者退出后删除无调用 StorageSession.saveCalls；CallCorrelation 两个 BackendState overload；DecisionSnapshot.capture(BackendState)。调用者/fixture同步迁为明确输入及列表patch，没有生产兼容包装或未调用旧入口。

剩余真实删除门（不是最终豁免）：

- T09：Home/Reports/ReportCalculator、CoreSnapshotProjection/FullSnapshotReader、source/scoped 查询仍使用全状态；application/callers/CallerProjection 已是无状态纯映射，旧同名包装在T07删除。WorkClassifier 已只有 WorkInputs 原生入口，BackendState 作为其现有实现的剩余消费者随T09/T13迁出。
- T10：BackendGraph recording.findCall lambda 仍 store.read→全部calls→resolveRecordingCall，明确留给recording目标ID闭包。
- T10/T11：BackendContext.notifyChanged 的真实消费者仍是 RingtoneService 三处；publishScopes 的recording/preferences/widget等未迁移消费者仍存在，未在本片误删。
- T13：CallringDatabase.onCreate 的内置日历/raw audit 初始化仍实际调用 StorageSession.load/save；SqliteBackendStore.read/captureFull 仍经 StorageSession.load，服务于T09有名的读/快照消费者。StorageSession.save 与旧四Store保存链还被初始化使用；TableSnapshot.previous 仍由 SettingsStore/ScheduleStore实际使用。
- 旧 BackendStore.transaction / StorageResources.transaction / SqliteBackendStore.transaction 经全生产源码审计已没有普通业务writer调用，只剩接口/转发实现和大量测试fixture seed/审计准备使用。它们是T13强制迁出的测试存储债，不能称为calling生产消费者，也不能为测试永久保留生产接口；待T09读/快照、T13初始化与fixture迁出后整套旧存储协议一并删除。T08新业务完全退出旧transaction/read。
- changedScopeKeys 仍由 AppearanceService 实际使用，保留该小diff helper。

## RED / GREEN 与最终门

真实行为RED与编译失败分开记录：

1. 初始临时enum夹具误用 ALLOW/SILENCE 产生编译失败；纠正后才取得 `t08-red.log` 的2项真实RED：1k/10k record 全状态装载预期0、实际1。迁移过程的旧签名/fixture编译错误也没有冒充行为RED。
2. 新provider初次导入的raw-loaded baseline造成冗余UPDATE，`t08-redundant-write-red.log` 2项RED（预期2次实际3次UPDATE）；用insert返回的完整事实作该批次baseline修复，最后实测2次。
3. 空 providerCalls batch 绕过lease活动检查，`t08-empty-lease-red.log` 4项1失败；检查移到chunk前。最终escaped reader/writer全通过，空输入不能逃逸。
4. IGNORE返回-1在SqlAccessReturningId仍计1，`t08-ignore-probe-red.log` 1项1失败，修为0；主负责人进一步指出同根Unit入口，`t08-unit-ignore-red.log` 2项2失败。Unit委托同一实现，真实Score第二字段IGNORE代表用例验证前一字段SQL写也rollback且零publish。最终全部GREEN。

完整门在发现新实证缺陷后重跑；在严格 Unit insert 修复及31个本片实改/新增Kotlin文件使用官方ktfmt四空格格式后，再发现并删除无调用的 StorageSession.saveCalls；没有保留迁移死入口。早先通过轮保留 `t08-full-gate-initial.log` / `t08-full-gate-lease-green.log` / `t08-full-gate-returning-id-green.log` / `t08-full-gate-unit-insert-green.log` / `t08-full-gate-formatted-green.log`，最终结论只依据下列最终log。

环境：JAVA_HOME=`D:/Apps/zulu17.54.21-ca-jdk17.0.13-win_x64`；ANDROID_HOME=`D:/Apps/AndroidSDK`。

```powershell
.\gradlew.bat :contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug :app:compileBackendQaKotlin :ui:compileDebugAndroidTestKotlin --console=plain
```

最终 `build/qa/layer-boundaries/t08-full-gate.log`：exit0，BUILD SUCCESSFUL in 48s，146 actionable tasks（17 executed / 129 up-to-date）。所有XML 0 failures/errors/skipped；contracts:test NO-SOURCE，不能宣称有contracts测试执行。

| 模块 | XML suites | tests | failures/errors/skipped |
|---|---:|---:|---|
| contracts | 0 | 0 | 0 / 0 / 0 |
| backend | 57 | 273 | 0 / 0 / 0 |
| ui | 70 | 296 | 0 / 0 / 0 |
| app | 57 | 268 | 0 / 0 / 0 |

关键套件：Graph访问2；CallingSqlSemantics9；CallingMigration2；CallCorrelation4；DecisionPublication1；IncomingCallCoordinator13；SessionGate5；PreparationGate3，均最终通过。其余账户unknown/known、窗口外反向候选、同时间稳定顺序、原始编码、真实ID序列、全批rollback/IGNORE、命令/foreground/取消回归均随完整门执行。

附加门：两个架构脚本 PASS；unittest test_architecture.py 17项、test_backend_boundaries.py 4项全部OK；git diff --check通过。架构守卫确认冻结契约/golden/hash pin未改。

- `build/qa/layer-boundaries/t08-full-gate.log` SHA-256 `7a07c7d6da1b6d9e21de45fcabd85fcbdd2baa0f5e1e1d9a848b4b4d86fb3323`
- `build/qa/layer-boundaries/t08-red.log` SHA-256 `9281e8f24556bb276fffe471c4231f468b1df2ba11d6378833d937cb2e36e489`
- `build/qa/layer-boundaries/t08-redundant-write-red.log` SHA-256 `7841cb739aa163b09f59c46ae75c15527dd43b383495cccb91c359d9443ab7e3`
- `build/qa/layer-boundaries/t08-empty-lease-red.log` SHA-256 `8f879614b9ee83b95b29590885e850c3996f150eb1f5bdc2bac2676200b13207`
- `build/qa/layer-boundaries/t08-ignore-probe-red.log` SHA-256 `ab59ac572db8a782aed07ac9088d7fc2bceacca8daed6fcc75f7403cf77be3c3`
- `build/qa/layer-boundaries/t08-unit-ignore-red.log` SHA-256 `c26de9cfa337ad66dc3b89b17de3ffeaea250ce6d8d9aef6c0c403da9febb6ba`

未运行任何device测试、安装、真机Telecom/权限生命周期冒烟；本次只编译backendQa与AndroidTest。Debug APK仅完整门构建产物，不是发包交付。待双审；不得用本片冻结代表T09–T15或全项目完成。
