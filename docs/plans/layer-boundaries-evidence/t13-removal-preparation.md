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

文件清单收口须以真实源码集合核对，不能只机械勾选原227项：2026-10-20-layer-boundaries-file-map.md目前仍是设计基线和未勾选状态，T13回填每个原文件的实际唯一目标/删除位置及证据，再覆盖新增contracts与拆分文件。保留基线日期/数量作为历史，不把当前数量冒充原统计；当前app/ui/backend/contracts所有src/main Kotlin/Java必须有明确归属，无丢失、重复或仍指向已删包装的条目。迁移目录后的生产和测试package/import、Manifest、runner、工具脚本与文档链接一起检查。

## 根组件薄壳补充（b1bf4b0只读预查）

RuleScreeningService已把截止时间业务委托ScreeningController；其Android Call/ResultReceiver/Handler适配仍可留壳。SyncJobService、CalendarSyncJobService、RecordingJobService、RuntimeReceiver还包含任务/事件编排，应各归entrypoint/jobs或entrypoint/runtime控制器；CallCaptureService、RingPlaybackService依文件清单拆出录音/播放控制器，系统组件FQCN保持。纯移动不顺带重写生命周期算法。

提取必须保持：Calendar job IDs76/77、6小时周期、1000ms启动及按databaseName隔离的calendar_sync偏好；Recording job ID73140、持久/网络约束/60秒退避、独立导入失败仍处理已导入项目；Sync的CONTENT_JOB_ID与原观察重挂/停止令牌/异步jobFinished分支。RuntimeReceiver保留广播action集合、goAsync→finally.finish、phone state的session与capture次序。上层静态schedule入口可以作为实际Android组件适配委托保留，不能保留第二份业务实现。

控制器注入明确的调度、权限、后台执行与完成回调，避免强持完整Service/BackendGraph作为万能上下文。现有JobRuns的旧任务完成不能结束新任务、close停止、取消和来电期限测试优先复用；机械提取不为每个委托创建镜像测试。Manifest、ComponentName、PendingIntent/Job身份及backendQa显式runner类名需同步核对。此节未执行组件/设备验证。

## UI设计系统边界补查（c188167只读）

Theme.kt中DefaultAppearance、ThemeRegistry.resolve(AppearanceSettings)、AppearanceSettings.usesDarkColors及CallringTheme的appearance参数仍解释contracts设置，须按设计移到ui/app/bridge。designsystem保留纯视觉ThemeSpec、ThemeDefinition、色板/对比度和Theme渲染；同步app/feature/widget/overlay及JVM/Android测试所有真实调用，不保留旧appearance参数重载或旧包转发。未知preset/缺失customSeed的既有默认视觉回落是产品呈现语义，保持由唯一bridge解释；不能因关键字fallback或默认参数机械删除。

SharedComponents.kt的CallerCard直接接受CallerProfile，移feature/shared并同步具体caller/home等引用；ScreenFrame、行控件、Loading/Error/Empty、ScoreBadge等实际通用视觉部件按依赖分离。contracts.ai通配导入若无使用直接删除，不为无用导入创造新抽象。InteractionFeedback的MutationSuccessEffect和通用消息/状态引用需按T11最终调用核对，避免designsystem反向依赖state/feature；移动本身不改按钮行为、反馈、颜色或布局。现有Theme对比度/unknown preset/深色、SharedComponents及widget颜色断言继续走最终唯一入口。

## 0ce2011 独立只读收口补核

`/root/t13_structure_audit` 未改文件/运行Gradle。该检查点src/main生产Kotlin/Java实际app87、ui89、backend114、contracts23，共313；仅是此时计数，不能冒充最终集合。生产唯一全状态业务消费者已缩为onCreate，Graph仅仍用旧资源命名构造；最小退出顺序仍为定向初始化→测试seed/事实→纯CoreStorageResource→旧模型/接口/投影/计数删除。

两个混合文件不能整文件误删：BusinessState.kt的defaultWorkSchedule有真实生产消费者，迁domain/model唯一实现后删State；SqlSupport.kt仅TableSnapshot专为旧四Store服务，Cursor扩展/args/values仍被窄SQL共用。SQLiteDatabase.scalar只有test/debug调用，迁测试支持，不留下production Compatibility helper。初始化保持补班MAKEUP_DAY_NAME及raw audit，不额外物化quiet/score默认值；LegacyValueCodec保持唯一现行持久解释。

本检查点SqliteBackendStore类型涉及app test/debug20文件，BackendState涉及backend/app test12文件；DecisionPublicationTest、DecisionFixtures也须迁。backendQa复用src/debug/kotlin，不能只搜backendQa。RealAiConversationDeviceTest通过businessReadCounters等待稳定，删除coreStateLoads等旧计数时保持真实source/runtime/SQL断言。

目录补核：data根17/platform根15/platform/ai13、backend application根13/conversation根23/domain根6均尚待归组；新CallingRowCodec、SqlAccess、SqliteFullSnapshotReader也入最终清单。7个分域CallringUiApi.kt按真实职责改明确文件名，不为命名再拆每个DTO。UiApiOwner/UiNotifications归UI app/bridge；MutationSuccessEffect当前仅视觉completed/busy/message且无state/feature import，按实际依赖判断，无需机械迁出。RingtoneService现application/ringtone与原map的runtime目标不同，明确最终决定后回填。

bootstrap的ValueScopedReadSource仍有过滤/排序/分页/Unicode切片，应迁data/query；ScopedQueryAssembly有D11–D19投影和SourceRow构造，CompleteSnapshotAssembly有域scope/count/status与错误标注，实际投影需退出bootstrap，保留接线。sourceCount的D02/D05/D06/D07/D09/D15数据形状逻辑不能换个assembly文件就称纯装配。组件薄壳的既有ScreeningController保持唯一，不再为它新建重复控制器。

测试迁移使用真实窄writer或明确的test-only分域seed/SQL事实支持，保留原完整事实比较、旧编码与回滚断言；不要把BackendState换名复制成全字段测试兼容壳，再保留旧read/transaction形状来规避删除。显式完整快照验收仍可使用既有FullSnapshotReader专用facts；普通入口访问范围则由仍真实执行的SQL/窄读取/完整capture计数证明。
