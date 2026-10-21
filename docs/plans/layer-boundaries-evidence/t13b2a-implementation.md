# T13b2a 查询投影与窄端口候选

2026-10-21。基线 `a4fe96403f34f2e4beff635e94d2a38186bb2915`，工作树 `codex/layer-boundaries`。本片只提取查询/完整快照投影、相关设备观察、纯关联规则与混合端口，不关闭 T13 全部工作。未 stage、commit、push、改版本或运行设备。

## 唯一职责与实际结构

ScopedQueryAssembly 仅构造 registry、六个窄行 projector 和 QueryService。Appearance、Ringtone、RecordingSettings、AiSettings、Widget、Runtime projector 位于 backend/application/conversation/query，只接 typed read 函数。四个设置 projector 内聚于 SettingsQueryProjections.kt，无空壳文件；AiSettingsService 仍是唯一安全设置视图实现。构造只存 lambda，不读取 api/store/recording。同步到 suspend 的 runBlocking 桥移入 backend，QueryService 的 IO/runInterruptible 边界未动。

ValueScopedReadSource 保持 data/query 唯一 generic 过滤、有界 TreeMap、分页、匹配全集计数、Unicode 切片；D 域 consistency 政策改由 backend queryConsistency 显式注入。D01–D10/D21 保持 native facts/SQL，普通查询没有改成完整 capture。D15/D16/D20 通过唯一 LazyScopedReadPort 延迟持有真实 adapter，外部版本仍来自 coordinator；scope/consistency/reset/validateRead/delivered/discard/closeCursor 全部传递。未初始化 adapter 的 reset 不创建资源，避免关闭尚未使用的 registry 初始化录音。

完整快照按 Core、RecordingText、RecordingSettings、Directory、Observed 五组 source 提取到 backend，原五组调用次序和错误归属 `D01-D12,D21` / `D15,D16` / `D13,D14` / `D17` / `D18-D20` 保持。withSnapshotSource 保留取消重抛、BackendFailure 原结构及未知异常映射；snapshotSourceCount 附于既有 FullSnapshotProjection，原 projectCoreSnapshot 实现未改。D02/options、D05/intervals+numbers、D06/history、D07 三集合、D09/entries、D15 三集合全集计数保持，D18 对象 count=1。NOT_CONFIGURED/EMPTY、目录权限/资源 lease、完整预算/事务/freeze/三次联合版本与 keys 校验未改。

RecordingPlaybackObservation 在 AndroidRecordingPlayback 原同一锁内一次读取 status/callId；旧 JSON snapshot 入口删除。RecordingActivityObservation 是三个容器依次取数的 live observation，不声称原子快照。AndroidJobObservation 只返回本应用调度事实，原 null scheduler/原 pending 顺序/包过滤保持；JSON 归 RuntimeQueryProjection。scoped D19 保持 runtime、含 callId 的 playback、processing、jobs，full D19 仍仅 runtime 与 PlaybackStatus，两种 shape 不合并。processing/jobs 仍 live_probe，不新增 publisher 或持久版本。

AndroidPreferences.scopedBindings 替换 scopedBindingRows，只返回 WidgetBinding 序列，保留原 toInt/as String 解析与无额外排序；不复用具有不同 malformed 包装及排序的 listBindings。Widget projector 在读取前选择 default/bindings/installed；零安装仍生成 installation-observation 哨兵。

## 旧实现删除与最后消费者迁移

| 删除项 | 唯一新入口与原最后消费者 |
|---|---|
| application/RecordingPorts.kt | AiClient/AiFailure/AudioReference → ports/recording/AiClient.kt；三种候选/metadata → RecordingMetadata.kt；sameRecordingNumber → domain/model/RecordingNumber.kt。RecordingWorkflow/Service、录音端口、AndroidRecordingStore/MetadataReads/AudioTranscriber/CompatibleAiClient、所有对应 test imports 已迁真实声明。 |
| application/RecordingAssociations.kt | RecordingCall/Link/resolve → domain/calling/RecordingAssociations.kt；findRecordingCall → application/recording/RecordingCallLookup.kt，仍一个 CallingTransactions lease；IncomingRecordingSession → application/recording/IncomingRecordingSession.kt。BackendGraph、IncomingCallController/AndroidIncomingRecovery、RecordingIdentityReads、关联/SQL/会话测试全迁。domain 不依赖 application。 |
| application/ports/BackendPorts.kt | CalendarSource、DocumentStore（含 CalendarDocumentReader）、RuntimeProbe、AudioController（含 PreparedRingtoneImport）、SyncScheduler → ports/platform 各内聚文件；PreferenceStore → ports/store；BackendFailure 保持 ports 独立错误边界。CallringBackend、对应 service/platform adapters、test fixtures 全迁。 |
| platform/PlatformSafety.kt | DocumentSafety → platform/document；AudioFiles/Generation/PreparedBackup/atomic replacement → platform/audio/AudioFiles.kt。AndroidDocumentStore、AndroidAudioController、PlatformSafetyTest/RingtonePreparationTest 直接用真实新入口；copyBounded 仅一份，锁外 prepare、最终 fence、rollback、close/FSync 调用未改。 |
| RecordingPlayback.snapshot / RecordingService.playbackSnapshot | AndroidRecordingPlayback.observation / RecordingService.playbackObservation。唯一生产 RuntimeQueryProjection 与 ToolBusinessIntegrationTest、RecordingApplicationServiceTest fake 全迁；status() 仍供 full D19 使用。无 JSON alias。 |
| RecordingWorkflow.activitySnapshot | activityObservation，唯一生产 RuntimeQueryProjection 全迁。无转发旧方法。 |
| AndroidPreferences.scopedBindingRows | scopedBindings；唯一生产 WidgetQueryProjection 通过 assembly 窄 supplier 接线，无旧 SourceRow/JSON helper。 |
| ConversationAssembly.jobStatus | AndroidJobObservation.observe 返回 typed facts；scheduledJobsValue 为唯一 JSON 映射。 |
| assembly 内 source/sourceCount 与 D 域分支 | backend 五组完整 source、withSnapshotSource、snapshotSourceCount、六个 scoped projector；assembly 不再计算 DTO、JSON、count、错误域或过滤。 |

旧四个混合文件均删除，所有 main/test/debug/Java 消费者使用新包，不保留旧包 forwarding/typealias/第二实现。原有效 codec、schema 升级与系统身份继续保留。完整逐符号原消费者索引见 ignored `t13b2a-removal-manifest.json`，摘要记录其哈希。

## 验证与边界

WidgetQueryProjection 新 API 测试先因缺失声明 RED；实现后分支隔离/零安装哨兵 GREEN。补充 full/scoped D19 shape、读取次序、全集 count、完整源错误原字段与取消身份、D18 scoped/full malformed 差异与排序测试。现有全部业务断言保留；真实 SQL/窄读/完整 capture 范围、权限、秘密、长文、取消、回滚、1k/10k 与刷新测试由现有完整门验证。

精确 guard 先得到 11 个负例失败，再通过 33 项架构测试及 5 项 backend 边界测试。仅 main bootstrap 禁 SourceRow/SnapshotPart/safeValue/aiObject/count 业务投影；query 构造参数禁明确 Graph/Backend/UiApi/Assembly 全能力类型；旧混合文件和 JSON observation 声明禁止回流。测试专用 bootstrap 夹具及合法专用 JSON Map/Any payload 保持允许，不用整包 allowlist。T14 广 CI 收口另行执行。

初次提取编译发现现有 FullSnapshotProjection 同名文件被生成脚本覆盖，立即从 HEAD 恢复原实现并只附加 sourceCount；另保留 workflow AiProtocol 的实际 import。定向编译后的唯一测试构造 jobs lambda 改 typed facts；无产品算法/协议修补。准备失败日志保留，不冒充通过。最终源码 freeze、完整门、Release 实编、XML/fixture/hash、执行与缓存状态以 t13b2a-verification-summary.json 为准。

完整门实际 exit0，1m27s，155 tasks：21 executed / 134 up-to-date。backend363、app335 本门实际执行，UI312 为未变化来源的缓存 XML，contracts NO-SOURCE；failure/error/skipped 均0。assembleDebug/backendQa 实执行，UI AndroidTest Kotlin 缓存、app AndroidTest Kotlin NO-SOURCE。Release Kotlin/Java 均单独实际编译通过；lint 无 Error/Fatal，沿用 UI30 Warning+3 Hint / app6 Warning。两 guards 与33+5正反例通过，140 JVM 生产源；diff --check、untracked EOF/尾空白检查为0。

93源码路径（相对基线 diff --no-renames 加 untracked Kotlin/Java，含删除）按路径+LF+原始字节/DELETED+LF冻结为 `8a8b0a417c1f9605377d8e0d05d6d49e78c6649668966a6a2499f1fb07bc9f14`；完整门后重算一致，manifest SHA `827566a702745bdbec57bdf147cbbf42c2d30c81e1a0bb2e3531cf95e29e9b51`。43份安全 JSON 逐 SHA 复制到 ignored t13b2a-fixtures，其中两份 sql-baseline 明确历史非重生成；所有旧 tracked 样本保持原貌。XML 按 Windows 路径组件 casefold 排序后 relative POSIX path+LF+raw XML+LF 聚合，具体 log/lint/fixture/manifest/exit 哈希见摘要。

## 清单与后续

原 227 项基线、日期与设计目标保留，本片逐项回填实际唯一目标；当前 main 全集 341：app86/backend140/contracts23/ui92，缺失/重复须以当前审计核对。固定设计的六 projector 用四设置同文件加 Widget/Runtime；完整 count 放既有 FullSnapshotProjection；LazyScopedReadPort 是完整 hook 的真实生命周期 adapter。这些是内聚文件决策，无空壳凑数。

T13b2b 仍待 Theme/Appearance 桥、CallerCard、form 及混合 UI screen/ViewModel；T13b2c 仍待六系统组件 controller 与 PROJECT/README/最终清单统一复核；T14 CI 和 T15 最终设备验收未关闭。规格/质量独立审查结论由负责人记录。
