# T04 领域模型与公共执行边界实施证据

日期：2026-10-20。代码基线：`339ccd0`（T03）。工作树：`C:/Users/GreatWall/.codex/worktrees/layer-boundaries/callring`，分支 `codex/layer-boundaries`。

状态：实现、验证、独立规格和质量审查均已通过，由主负责人提交检查点。未修改原项目目录，未变更 SQL scope/schema、版本、持久化编码或外部工具协议。

## 变更

- 新增 `domain/model/DomainModels.kt`：15 个必需设置/分类/通话原生类型，以及报表计算的 TimeRange、ScoreSummary、ReportTotals。未复制 CallerProfile、CalendarInfo、分页、质量和其他展示聚合。
- `WorkClassifier` 使用 `WorkInputs`，保留完整工作配置历史、日历元数据/日期、覆盖、请假及 leaveVersion；BackendState 在过渡期实现该窄接口。生效时间/版本排序、历史前兜底、前日跨午夜、DST 与区间边界算法不变。
- `DecisionSnapshot` 仅持有 `DecisionInputs`，所有集合及嵌套窗口都深冻结；公开只读 `inputs` 可做有类型 copy 后重新 capture，为后续局部 quiet 提交提供输入闭包。无 calls/archives/文件/端口字段。
- `application/mapping/ContractMappings.kt` 显式双向映射；枚举使用穷尽 when，不按 ordinal/name 转换。公开集合投影保持深不可变。CallerProjection 保留原始与当前事实分离；SafeProjection 在公开 JSON 编码前转换原生数据和枚举。
- 原报表累加器移至 `domain/report`，application 保留公开 DTO、运行质量、分页、API 错误和范围组装。保留区间外先排除、previous 仅区间内更新、升序检查、重复含阈值、nullable 空评分、饱和时长与 includeSaturatedMax。
- SettingsRules 返回普通 SettingsIssue，覆盖分钟/窗口/周设置、免打扰自定义、时区、评分、日期顺序、覆盖窗口、请假区间。application 保留原先首个 API 错误和完整嵌套 field path。
- NumberIdentity、日历区间与饱和数学为纯领域 helper；CSV 转义为 application 自由函数，不再依附 BackendContext。
- 厂商识别、思考参数/门禁、预算及连接校验移至原生 `domain/ai`；application 包保留协议转换适配。HTTP endpoint 拼接、File/流/协程客户端执行仍在 application/platform。
- UseCaseRunner 仅持有 dispatcher，负责成功/错误转换、取消传播与写入开始后的 NonCancellable 边界；不持有业务状态和服务定位器。
- 同步 app 的 CallStore、SettingsStore、ScheduleStore、LegacyValueCodec、WorkConfigCodec、StorageMigration、SqliteScopedReadAdapter、AndroidCallLogSource、来电适配与所有受影响测试 source set。原有 JSON 键、未知 raw 字符串、枚举存储字符串不变。

## RED / GREEN

1. 首先新增 DomainIsolationTest，原 6 个 domain 源码契约依赖导致实际断言失败，日志 `build/qa/layer-boundaries/t04-red-domain.log`（1 项失败）。Python domain→contracts/application 两个负例同样先实际失败。
2. 纯设置规则提取前新增边界断言；实际 2 项运行、1 项失败，日志 `t04-red-validation.log`。
3. 映射回归在迁移过程中补齐；其定点负例通过暂时将 ANSWERED 映射到 MISSED 触发明确断言失败，1 项运行、1 项失败，日志 `t04-red-mapping-mutation.log`；随后在 finally 中恢复源码。这是映射负例验证，不将其描述成最初映射实现前的 RED。
4. 守卫追加 java.nio.file/java.sql 负例先失败，强化规则后 15 项 Python 架构测试全绿。
5. 新回归包括显式双向枚举对应、嵌套设置脱离源集合、公开集合冻结、领域事实 JSON、snapshot.inputs 嵌套强转修改拒绝、source 后续修改隔离、typed copy 后镜像独立，以及执行器错误/取消/已开始写入保留。报表定点回归覆盖区间前通话不重复扣分、含阈值、空评分、Long.MAX_VALUE 纳入开关、时长饱和与升序要求。

## 验证记录

环境：PowerShell；JAVA_HOME=`D:/Apps/zulu17.54.21-ca-jdk17.0.13-win_x64`；SDK=`D:/Apps/AndroidSDK`。日志位于 ignored 的 `build/qa/layer-boundaries/`。

- 最终完整门：`./gradlew.bat :contracts:test :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug --console=plain`，日志 `t04-full-gate-final.log`。
- 额外 source set：`./gradlew.bat :app:assembleBackendQa :ui:assembleDebugAndroidTest --console=plain`，退出 0，89 actionable tasks；日志 `t04-source-sets.log`。
- `python tools/check_architecture.py`、`python tools/check_backend_boundaries.py`。
- `python -m unittest discover -s tools -p test_architecture.py`（15 项）、`python -m unittest discover -s tools -p test_backend_boundaries.py`（4 项）。
- JUnit XML 实际项数：backend 238、UI 283、app 220；失败/错误/跳过均 0。contracts:test 为 NO-SOURCE，不计作执行测试。
- Lint XML：UI 31 Warning、3 Hint；app 6 Warning；无 Error。Gradle 仍报告 deprecated features，source set 编译也有原 InstrumentationTestCase 等 deprecated API 警告，不声称零警告。

## 检查清单与限制

- [x] domain 无 contracts/application/Android/协程/文件或网络 IO 依赖；允许无 IO 的 java.net.URI 解析。
- [x] 领域输入闭包、深冻结、当前/原始事实、历史及报表关键边界覆盖。
- [x] 对外公开 contracts 源码无修改；历史 CallringUiApi.kt、current-api-baseline.md、ai-operation-compatibility.json 保持冻结；现有工具兼容检查继续执行。
- [x] 原有 81 工具兼容测试/契约守卫随完整 backend 测试及边界脚本验证；未改 schema 或目录。
- [x] Debug、backendQa、UI AndroidTest 离线装配通过。
- [x] 独立规格与质量审查通过，主负责人核对验证证据；见 t04-review.md。
- [ ] 真机/模拟器来电与存储迁移设备运行：本任务未执行，不把离线构建称为设备验收。

BackendContext、全量状态存储和旧通知协议按后续 T05+ 切片继续迁移；本任务没有提前实施局部 SQL 或通知行为变化。
