# T13b1 机械目录迁移候选

基线 `4a9af41b4f0722dd453cecabe36edb115b4373dd`；唯一实施工作树为 `C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring`，未触碰主工作区用户修改，未 stage/commit/push、改版本或运行设备。此片仅整理路径、文件名、package/import 和 Java 跨包调用必需的可见性。规格与质量审查由负责人另行记录，T13b2、T14、T15仍未完成。

**当前最终候选**：source `7aa49380f300b593f587acfc86ebf5bdadce3b69caf3e542276e4b8cb4dd90f6`，规格delta由负责人转达PASS，独立quality仍pending。29+5 guard通过，Release Kotlin/Java实际GREEN；当前完整门是`t13b1-fullgate-after-review.log`（exit0），app334实际执行，backend360/UI312沿用初轮实执行XML并明确标记缓存。下文初次候选数据保留为历史，最终修复与门的详情在末节；不以初轮65b7c/28guard代表当前候选。

## 真实迁移与唯一实现

固定旧HEAD审计的94项 move 全部执行，并采纳T13a实际结果：四个旧Store保持删除，BusinessState保持删除且defaultWorkSchedule保持domain/model唯一实现；新SqliteCoreStorageResource补归data/database。因此本片共95个实际文件移动，生产集合仍318文件（app83、backend120、contracts23、ui92），没有新增生产实现或旧包转发。

app的database/codec/query/calling/preferences/recording及platform的audio/calling/document/calendar/diagnostics/recording/runtime/scheduler按真实职责归位。ArchiveStreamParser直接依赖SQLite，与ArchiveProjectionIndex同归data/recording，明确修订早期platform.ai目标。backend application/conversation按context/execution/foreground/operation/query分组，窄ports与domain按职责分组。RingtoneService保持application/ringtone：铃声用例已与runtime状态/探测分域，无须为匹配早期目标再次迁移。SqliteCoreUnitOfWork保持内聚session单文件，不创建无消费者Read/WriteSession壳。

七个contracts分域CallringUiApi.kt只改明确文件名，不改声明；current-api.md当前inventory同步七个相对文件键，所有声明哈希与内容保持。冻结的current-api-baseline.md、旧CallringUiApi.kt、AI兼容golden及既有历史样本未修改。UiApiOwner/UiNotifications归ui/app/bridge，UI继续只依赖contracts。

Java三个helper跨包后公开的精确清单：CallStateSnapshot类型及active；PreparationGate类型、构造、ready/cancel/commit；SessionGate类型、构造、WAIT/PLAY/STOP共用字段声明、owns/update。合计12处public关键字，算法、字段与时间阈值原样。RingPlaybackService系统FQCN保持，所有生产/test/sharedTest/debug/backendQa调用方同步imports；无需改Manifest、资源、runner类身份、JobID、Intent/action/extra、PendingIntent或channel。

## 机械等价与清单闭合

ignored证据目录为 `build/qa/layer-boundaries/`：

- `t13b1-path-fqcn-map.json`：95项旧/新路径、package及已知符号FQCN归一表。
- `t13b1-mechanical-equivalence.json`：全部577个tracked Kotlin/Java文件比较。剥package/import及空行，按精确FQCN表归一，仅允许上述Java public；执行体差异0，全部字符串字面量序列差异0。没有通过修改业务表达式解决导入冲突。
- `t13b1-filemap-closure.json`：原227项逐项实际唯一实现/删除替代，以及当前318项实际生产集合；缺项、重复均0。生产迁移清单保留2026-10-20/fa886a4历史基线并进入执行状态，已完成项勾选，混合职责仍pending。
- `t13b1-static-checks.json`：旧移动FQCN的显式import为0；git diff --check退出0；untracked EOF/尾空白检查为0。

初候选源码freeze为 `65b7c06519a1f78cb3b8f728760e74f6404b4c48659878a9b1dd3030c32f9223`，397路径，已由下述规格修复候选取代。`t13b1-source-freeze.json`使用相对基线git diff --no-renames文件集合并入untracked .kt/.java，排序后逐路径+LF+原始字节（删除项DELETED）+LF聚合。

## 实际验证

单一Gradle owner执行完整门：contracts:test、backend:test、ui/app testDebugUnitTest、app assembleDebug、ui/app lintDebug、app compileBackendQaKotlin、ui/app compileDebugAndroidTestKotlin。`t13b1-fullgate.log`与立即捕获的.exit为0：BUILD SUCCESSFUL in 2m24s，155 tasks，38 executed、117 up-to-date。

backend360、UI312、app334全部本门EXECUTED，合计1006测试，failure/error/skipped均0；contracts NO-SOURCE。原1000/10000局部读写、精确刷新和完整capture范围断言没有修改，随原app套件实际执行。assembleDebug、backendQa Kotlin、UI AndroidTest Kotlin实际执行，app AndroidTest Kotlin为NO-SOURCE。lint实际执行，UI30 Warning+3 Hint、app6 Warning，无error；本片不夹带无关告警整改。

两个Python架构guard退出0，backend120 JVM源；对应28与5项unittest均通过。定向编译准备阶段的未解析/冲突/多余内部import失败保留compile至compile5日志，compile6成功；它们属于机械迁移编译准备，没有新增业务行为RED或修改业务断言。

`t13b1-verification-summary.json`记录命令、退出、task执行/缓存/NO-SOURCE、XML数/聚合SHA、lint/log/fixture哈希。XML按WindowsPath组件大小写折叠顺序排序，再用relative POSIX路径+LF+rawXML+LF聚合；backend69、UI75、app65份XML。43份当前安全JSON逐SHA复制到`t13b1-fixtures`，其中两份sql-baseline明确标记historical non-regenerated，不能冒充重跑结果；既有tracked样本保持历史原貌。

## 明确保留的后续工作

T13b2仍须提取bootstrap中的D5查询/完整快照业务投影、主题Appearance桥、CallerCard、组件控制器，并拆PlatformSafety、RecordingAssociations、BackendPorts及混合UI文件职责。本片仅更新它们使用已迁移依赖的imports，保持当前唯一真实实现，没有增加临时桥。PROJECT/README最终结构、T14统一CI及T15最终验收不在本片关闭。

## 规格修复与修后完整门

规格审查发现两条机械同步的多余`data.scalar` import：SqlAccess与SqliteCoreUnitOfWork实际调用SqlAccess成员方法，顶层scalar只存在debug SqlTestSupport中。debug/QA门因包含该测试源而掩盖错误；单独Release编译实复现两条unresolved scalar（exit1），删除且仅删除这两个import后，compileReleaseKotlin/compileReleaseJavaWithJavac实际执行成功（exit0）。没有把测试helper移进生产，成员实现未改。其它main新增import扫描没有发现同类仅测试定义引用。

同轮将架构守卫的具体资源cast正则同步真实`data.database.SqliteCoreStorageResource`，不扩大为整包禁令。原负例同步新路径，并增加全限定Kotlin as/as?和Java cast反例、保持其它sealed/KeyStore/同包OtherResource cast合法。29项测试在旧规则下RED（4处assertion failures），精确改正则后GREEN29；另5项backend边界测试仍通过。RED/GREEN和Release修前/修后原日志均保留，属于真实路径缺口修复证据。

修后最终源码freeze为 `7aa49380f300b593f587acfc86ebf5bdadce3b69caf3e542276e4b8cb4dd90f6`，仍397路径；门后复算相同。577执行体与字符串差异仍0，Java12处可见性清单未变。原候选freeze/summary/43份fixture保留为before-review，旧完整门日志保持原文件名。

`t13b1-fullgate-after-review.log`实际exit0：BUILD SUCCESSFUL in 1m13s，155 tasks /17 executed/138 up-to-date。app334本门EXECUTED、0failure/error/skipped；backend360/UI312本门UP-TO-DATE，明确沿用初候选门的真实执行XML，不能冒充再次执行；contracts NO-SOURCE。assemble/backendQa实际执行，UI AndroidTest Kotlin缓存、app AndroidTest Kotlin NO-SOURCE。lint无error，告警仍UI30 Warning+3 Hint/app6 Warning，分析/报告具体缓存状态在summary中记录。

当前summary记录29+5 guard、Release独立编译、两轮门及缓存区别。仅文档补充Graph原项的真实四Assembly与Settings/UiEdits的已提取服务目标；Graph依赖的ConversationAssembly仍有b2任务，原项保持pending。最终规格/独立质量结论由负责人记录。
