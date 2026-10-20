# 分层整理执行基线

2026-10-20。源码基线 af9e485（生产源码与 fa886a4 相同）；工作分支 codex/layer-boundaries。

## 环境

- JDK 17，Gradle 8.13；SDK 目录来自 ANDROID_HOME/ANDROID_SDK_ROOT，Android 36。
- 在应用管理的独立工作树运行，原工作区修改未复制、清理或覆盖。
- adb devices 观察到 192.168.10.149:5555 在线；本阶段未安装 APK、未运行设备用例。

## 原始完整基线

命令：`python tools/check_backend_boundaries.py`；退出 0，70 JVM 源码通过既有边界检查。

命令：`python -m unittest discover -s tools -p test_backend_boundaries.py`；退出 0，4 项通过。

命令：`.\gradlew.bat :backend:test :ui:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :ui:lintDebug :app:lintDebug --console=plain`；退出 0，BUILD SUCCESSFUL，4m17s。

| 模块 | JUnit 项 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| backend | 222 | 0 | 0 | 0 |
| ui | 280 | 0 | 0 | 0 |
| app | 218 | 0 | 0 | 0 |

Debug APK 构建、ui/app lint 完成。存在原有废弃 API/Gradle 9 兼容性警告，不代表 lint 失败。

本地完整日志：`build/qa/layer-boundaries/baseline-gradle.log`（生成目录，不入 Git）；SHA-256 `c263a17fd5511d11cba61fb44ee896fc93519b3125755d9fbc43adc93c4e099c`。这些项数来自本次完整基线 XML；后续定向测试会覆盖本地 XML，不应将覆盖后的子集当作原完整基线。

## 数据访问 characterization

新增 LocalAccessFixture 与 LocalAccessContractTest，分别创建 1千/1万条 B 的通话，固定 A 的档案、岗位、单条历史和统计区间；复审后扩充显式配置历史、原始/当前分类差异、旧编码哨兵及独立录音身份/统计链接。数据仅存在 Robolectric 隔离数据库中，未访问设备或真实用户数据库。

定向命令：`.\gradlew.bat :backend:test --tests com.greatwall.callring.ToolCompatibilityBaselineTest :app:testDebugUnitTest --tests com.greatwall.callring.data.LocalAccessContractTest --console=plain`；修复测试夹具将普通 Kotlin interface 错用成 SAM 的编译问题后，退出 0。新增 2 项本地访问测试和 1 项工具兼容测试通过。此失败属于新增测试编译错误，不是原生产基线失败。

| 普通操作 | 1千条无关通话的 fullStateLoads | 1万条无关通话的 fullStateLoads |
|---|---:|---:|
| 读取免打扰 | 1 | 1 |
| 修改 A 名称 | 1 | 1 |
| 查询 A 报表 | 1 | 1 |
| 登记 A 来电 | 1 | 1 |

修改 A 名称与登记 A 来电各提升全局修订 2 次，并将 16 个 DataDomain 全部标为变化；纯查询不提升修订。该记录为后续去重/精准发布的真实生产路径基线。

对应原始 JSON 为本目录 baseline-access-1000.json、baseline-access-10000.json。计数来自真实 Graph 的 coreStateLoads，SqliteBackendStore.read/transaction 每次 StorageSession.load 都加载 profiles/settings/schedules/calls（源码已核对）。该计数**不是 SQL 行扫描计数**，也不能据两组计数相同推断访问范围不随数据量增长。

当前测试固定业务结果、每次操作前后 B 的完整档案及全部通话事实不被误改和普通操作不调用完整 AI 快照；不将全量读计数 1 写成必须保持的目标。各切片迁移后逐项增加 fullStateLoads=0 及端口/SQL 实际范围断言。目标专门表/行探针在对应适配器建立时补齐，尚未完成。

## 既有语义回归来源

原始完整基线包含 DomainRegression、CallCorrelation、StoragePolicyCharacterization、ScopedRevision、BusinessRevisionCoordinator、NumberDeletionIntegration、RecordingCommandContext、ToolBusinessIntegration、RequestLoader 等实际测试类；回滚、旧值编码、并发/取消、原始事实和工具协议由这些用例提供当前基线，不将新增 characterization 的 2 项虚增为这些全部场景。

## 未完成门

SQL 表/返回行和窄端口探针明确分拆到 T05，须在首次存储行为修改前建立，A1 最终标准不变；contracts/模型抽离可先行。新增 UI RefreshIsolationTest 3 项和 O10 默认值修正后的 1/2/3 定向测试全部通过。另一次完整门通过 223/283/220，逐类 XML 计数与摘要保存在 s0-junit-summary.json；该完整门后仅修改测试夹具和清单 metadata，并已定向复验。规格复审与独立质量审查均通过；质量审查的初始夹具完整性断言和清理边界建议已修复，app 2 项定向测试再次通过（s0-quality-fixes.log）。尚未开始生产迁移，不宣称五项问题已解决。
