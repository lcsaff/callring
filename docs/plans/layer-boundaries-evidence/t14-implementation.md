# T14 架构守卫与统一验证候选

基准 `59309437f01764fa724d04f05b4927fbcc18f2e9`；仅修改守卫、验证工具、Gradle/CI 与当前文档，生产 Kotlin/Java 文件集合保持 360（app92/backend140/contracts23/ui105）。未 stage/commit/push、版本递增或实机 adb。规格/质量审查与计划勾选由负责人管理，本文不关闭 T15。

## 实现与删除

- 数字操作守卫移除文件名选择，检查显式 legacyIndex/displayOrder/operationCode 来源、OperationId ordinal、局部数字别名及 Java switch；新名 helper 与目录自身均受检查。普通分页、普通 enum ordinal、JSON 数字、显式目录/排序/唯一性/协议导出保持允许。静态规则不声称证明任意跨函数混淆。
- SQLite 实际目标为 data.database.SqliteCoreStorageResource；Kotlin as/as?、Java simple/FQCN、import alias 与本地 typealias 链均受检查，覆盖所有 source set。白盒直接持有资源的 SQL、JSON/sealed/Keystore/自有媒体 token 保持允许。
- 原 39+5 守卫测试保留；初门 48+5，审查修复后 50+5；新增 BackendContext/coreScopeValues/LazyBackendStore、资源 legacy、完整 assembly/context 组合、缺能力生产默认、commandApi identity fallback、全状态 load/save、具体双 publisher/loader 模式。实际 bootstrap 接线保持允许，新 bootstrap 服务接完整 assembly 仍拒绝；不以 legacy/fallback/snapshot/Map 单词泛禁。
- UI 增真实解析 debugCompileClasspath 的 Gradle 任务，按 ProjectComponentIdentifier.projectPath 拒绝直接或传递 backend。原模块声明和源码检查继续执行。
- verify_backend/verify_ui 的旧任务表、重复 subprocess/XML 汇总已彻底替换为 verify_common 中唯一门和记录；两个原 CLI 均继续运行全部新模块验证。Windows 保留 C: 工作树拼写调用 gradlew.bat；Linux 使用已安装 Gradle8.13。build/device timeout 分离；每次启动先写新 summary，finally 覆盖成功或失败，记录 commit、argv/exit/timeout、请求 serial 与 cleanup。
- backendQa 单 APK metadata/目录 containment、aapt 真包名、设备在线、安装 Success、runner+自身 target、命令 exit0 且 OK(N>0)、超时和专包 finally force-stop 已加入；UI 既有测试包、手动解锁、app-ops 原值读取/恢复保留。新增 12 组 mock 故障测试进入统一门，不接触真实 adb。
- build/release 两 workflow 均实际调用 python3 tools/verify_backend.py；build 补 codex/**/PR，throwaway debug key 提前到包含 Release 的门之前并删除重复 Release assemble；release secret/签名顺序和发布开关不变。workflow checker 保留原脚本/flag检查并用实际 run 命令及 Python AST 校验门，注释 token 不充数。发布 selftest 增 9 个 workflow 正负样本。

## 验证

先有意义 RED 后 GREEN：新增架构负例/普通 index 正例最初 18 failures；后补 bootstrap/context/typed legacy/双路径负例 2 failures；不合形 JSON metadata 首次错误后改显式拒绝。最终统一门实际运行两守卫与 48/5/12 unittest，均 exit0。全部命令和日志位置见同目录 t14-verification-summary.json。

真实类路径 RED/GREEN 只运行 :ui:checkDebugCompileClasspath：临时加入 UI implementation backend 后 exit1 且命中专用报错，finally 精确恢复后 exit0。原/恢复文件 SHA 均 `ca2ac39481cf2926a3fa653294da06d0c18bff1b7a404f9d7342ab67df7affac`；违规状态从未运行 full gate，注入未留存。

冻结 11 个可执行路径（diff BASE union untracked 的 .py/.gradle/.yml/.yaml），sorted POSIX path LF raw bytes LF，SHA256 `ad4303c6ee7d1b202f4bda7153b23fb1130f8047af70539eeac47667512efd28`。该指纹为完整门实际测试的初次冻结；后续审查修复与最终指纹独立记录在下节。

仅运行一次新的 `python tools/verify_backend.py` 完整门，exit0：

| 项目 | 本轮状态 | 计数 |
|---|---|---|
| contracts:test | NO-SOURCE | 0（如实单列） |
| backend:test | UP-TO-DATE | 363 |
| ui:testDebugUnitTest | UP-TO-DATE | 310 |
| app:testDebugUnitTest | UP-TO-DATE | 356 |
| UI 真类路径、UI Debug、app Release/backendQa、双 AndroidTest、双 lint | EXECUTED | exit0 |
| app Debug | UP-TO-DATE | exit0 |

三个业务模块均 tests>0、tests-skipped>0、failures/errors/skipped=0；匹配本轮精确任务行后使用缓存 XML，没有把旧 XML 冒称新执行。摘要保留命令、task状态、common 按 absolute path 字符串排序的 path LF rawXML LF SHA，并独立保留既有 WindowsPath 排序、relative POSIX path LF rawXML LF SHA、双 lint 报告 SHA 与 APK SHA。实际 Release APK 直接用于 release/selftest.py：19/19 发布样本及 9 个 workflow 样本符合预期，未再运行 Gradle。workflow checker exit0，git diff --check exit0。

沿用 T13b2a 归档的 43 安全样本（含两历史 SQL baseline），不复制历史数据集。本轮业务测试 UP-TO-DATE，因此未重新执行原始输出；后续实际执行可能重写 raw 结果。contract snapshot/golden pin、签名版本规则与最终文件归属文档未改。设备未跑；SIM/待机/重启/升级/下载仍未验证。

## 规格审查 P2 修复与最终冻结

独立审查发现明确 OperationDefinition.id/getId().ordinal、OperationId.CONSTANT.ordinal 以及 Kotlin data class 的实际 Java metadata getters 未全部追踪。只修改 check_architecture.py/test_architecture.py：补显式 OperationDefinition receiver、ID 与数字局部别名和 getLegacyIndex/getDisplayOrder/getOperationCode 的行为/解析 sink；普通其他类型的 getId().ordinal/getDisplayOrder 和 enum ordinal 继续合法。不扩大到任意跨函数分析或业务生命周期代码。

新增真实 getter/属性/常量案例先 RED 11 failures（exit1），后 49 tests GREEN（exit0）且真实树 check_architecture.py PASS，raw 日志见 summary。根据负责人指令未重跑 Gradle；初次 full gate 的 ad4303c6 冻结与任务/XML证据保留，不能冒称旧 full gate 已测试新 guard bytes。最终同一 11 路径重新冻结 SHA `1e581ecd4eeb7cfc52ecf61c3e66de3981180ec7634083c9a447943ef664811e`，逐路径 manifest 及修复后针对性验证在 summary。T15 将按届时最终代码运行实际统一门。

负责人复核后再精确补入两个已删除的生产默认实现名 UnavailableCoreUnitOfWork/UnavailableQuietTransactions。新增一组正反例先 RED 2 failures（exit1）后 50 tests GREEN（exit0），真实架构脚本 PASS；测试 fake 与普通业务 Unavailable 状态合法。仅 guard/test 两 Python 文件变更，不再扩查其它名称、不再跑 Gradle。最终 11 路径冻结 SHA `a368c276da8a9adc3d16f343cb22625043991596b3f156204a71e2067c9e0bee`；前述初门和 ordinal 修复指纹作为对应历史测试范围保留，最终候选与 targeted 日志见 summary。
