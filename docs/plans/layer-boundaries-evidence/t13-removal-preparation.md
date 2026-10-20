# 最终旧协议删除闭包准备（非验收）

独立只读复核基于 c5d62d5，避开 T07 进行中的修改。用户要求不留迁移兼容代码；下列均为退出任务，不是最终允许保留项。

| 阶段 | 真实生产消费者及退出要求 |
|---|---|
| T08 | BackendContext 的 incoming/updateDecision/import/statisticsStart/hasRecentPending；CallCorrelation 两个 BackendState overload；DecisionSnapshot.capture(BackendState) 和全量预热，改目标候选/DecisionInputsReader后删除旧入口 |
| T08/T10 | BackendGraph 的 recording findCall 装配 callback 仍 store.read→全部calls→resolveRecordingCall；改目标ID及直接screen闭包，不可遗漏装配lambda |
| T09 | Home/Reports/ReportCalculator/CallerProjection 的 state 参数 helper；SettingsValidation.stats/currentSchedule；CoreSnapshotProjection.captureCore/captureFull 和 FullSnapshotReader 的 BackendState协议；SqliteScopedReadAdapter.workStatus 自行new BackendState，均须替换 |
| T08/T13 | StorageMigration.upgrade 的1→3、2→3均经StorageSession，仅saveCalls；screen current纠正，v1额外相关性纠正；保留split provider新id、screen原id与original事实，不能附带改quiet/score/calendar/audit |
| T13 | CallringDatabase.onCreate 在创建db内导入内置日历+raw audit，改直接定向初始化，不能经业务协调器或第二数据库session |
| T13 | 最后使用者退出后删除SqliteBackendStore/StorageSession、四个全状态Store、TableSnapshot旧消费者、BackendStore/BackendState、StorageResources旧实现/read/transaction/legacy及CoreStorageResource.legacy；同步CallringBackend/BackendContext构造签名 |

## 测试与观测迁移

设备 SqliteBackendStoreDeviceTest、verify_backend显式类名；LocalAccessFixture/LocalAccessContractTest全事实审计、StoragePolicyCharacterization、旧LazyBackendStoreTest命名/内容、StorageResources legacy fake；backend ConversationFixtures、CallringBackend/CallCorrelation/CoreSnapshotProjection/DecisionInputs/DomainRegression/WorkClassifier/MemoryQuietTransactions等夹具全部同步迁移。测试专用seed/事实读取允许存在，不能为旧测试保留生产旧协议或重载。

stateLoads/stateLoadCount/businessReadCounters 属于迁移观测；最终保留SQL/窄端口真实计数并删除不可执行的旧计数/空壳，不让“永久返回0”冒充旧路径删除。

## quiet core复核退出证明

稳定版本未发现quiet restore生产入口。restoreBuiltIn是ringtone，不能作为quiet旧writer证据。quiet真实写入口是save/mode/enabled/edit→typed writeQuiet/saveQuiet，变化推进quiet scope。旧SettingsStore.save仍有四个quiet key写能力；剩余业务没有直接修改state.quiet，初始化只加calendar，升级只saveCalls。删除旧SettingsStore写能力后，quiet.get仅复核quiet，补无关core提交不重读/相关提交不能旧值贴新marker/last-mode-only推进scope的测试。不能以泛称“restore”延后删除。

建议顺序：来电/关联/预热→报表/source/full snapshot→recording findCall→初始化/升级专用SQL→fixture迁移→旧模型/Store/session/overload/空壳计数全部删除→精确符号负例防回流。独立codec、原schema/约束、sqlite_sequence与历史协议数据仍由唯一实现正确处理。
