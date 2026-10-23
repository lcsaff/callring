# 可配置来电号码规则与匹配策略隔离 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking. 本文只授权规划；执行须有后续实施指令，不自动开始编码、发包或发布。

**Goal:** 将“3 开头、5 位”改为页面与 AI 共用的可配置规则，并使今后在输入、配置及结果契约不变时替换匹配算法，仅需修改策略实现和对应测试。

**Architecture:** domain 定义纯匹配策略，application 管理配置事务、不可变快照、命令冲突及失效通知，app 实现存储和唯一装配，ui 与 AI 使用同一业务接口。来电、记录导入及号码查询均依赖策略抽象，不自行解释前缀或位数。只在首次接入时改动消费者，不以全局可变变量或 domain 读取设置实现配置化。

**Tech Stack:** Kotlin/JVM、Android、Compose、SQLite、现有 contracts / backend / ui / app 四模块与 AI OperationCatalog / CommandCommitter / QueryService。

**状态：** 修改计划，未实施；以下规则边界是本计划采用的方案，不表示已有代码具备这些能力。日期：2026-10-22。

---

## 1. 范围与设计决定

### 1.1 首期配置

- 单条规则：prefix（字符串）与 length（总位数）。
- 默认 prefix = "3"，length = 5，沿用现有匹配结果。
- prefix 只允许 ASCII 数字，可以为空；空表示不限前缀，界面明确标注。
- length 范围 1..30，prefix.length 不得大于 length。
- 合法性与业务匹配分开：合法号码仍支持现有加号规则；首期业务规则仅匹配规范化后的纯 ASCII 数字，不自动去除 +86，也不把国际号码隐式转成本地号码。
- 页面提供读取、编辑、显式保存和当前号码试匹配；试匹配复用现有号码检查能力，不新增独立 AI 预览工具。
- 不增加多规则、正则、排除规则、优先级、恢复默认工具或规则历史库。
- 已保存记录不删除、不重算；后续系统通话记录同步按捕获到的当前规则处理原有导入范围。新规则可能使过去未导入的历史通话被纳入，页面与 AI 说明须明确。
- prefix/length 不加入身份或号码主键，修改规则不改变已保存号码。

### 1.2 “以后只改算法”的承诺边界

下列变化只涉及策略实现、对应测试；若新增策略类，额外修改唯一装配点：
- 相同规范号码和 NumberRule 输入下，更换内部实现。
- 修正实现缺陷、优化匹配性能。

下列变化超出该承诺，必须重新评估契约：
- 新增后缀、多规则、正则等配置字段。
- 匹配需要联系人、SIM、联网数据、位置等新输入。
- 增加匹配原因、优先级、动作等结果语义。
- 修改号码规范化或格式合法性，不属于替换匹配策略。
- 对已有数据的历史重算或原有语义发生变化，不能用“仅替换策略”隐藏业务变更。

首期不为假想规则建立 DSL、动态插件、脚本执行器或新的 AI 执行框架。算法隔离不要求同时重构所有 AI 操作。

### 1.3 决策与生效

- 配置是一个事务中的完整值，禁止分别提交前缀和位数。
- 保存成功后，在现有协调锁内完成运行快照更新和失效发布，返回前后可观察到同一已提交值。
- 缺少数据库配置时采用默认值；存在但编码损坏时返回明确错误，不能悄悄当作默认配置。冷启动使用最近可用快照的条件须可证实；不存在有效快照时明确报告不可用，不伪造保存成功。
- 运行中的规则状态归 BackendGraph 生命周期所有；多个 commandApi 绑定共享，禁止每次命令创建新的规则状态。
- 来电首次评估捕获规则版本与匹配结果；异步记录及同一会话再次评估沿用该匹配事实。再次评估仍可刷新现有权限、静音、悬浮窗等非规则设置，避免冻结整个旧决策。
- 一批系统通话导入捕获一份规则。处理期间修改规则不使同一批次前后采用不同算法参数。
- 保存后开始的新来电、新导入批次、新号码检查采用新规则。
- 同步 checkNumber 读取已初始化的内存快照，不直接访问 SQLite；冷启动先沿正式初始化路径准备规则，不能隐式返回“默认命中”。

## 2. 稳定的算法接口

拟新增 backend/src/main/kotlin/com/greatwall/callring/domain/calling/NumberMatcher.kt：

```kotlin
package com.greatwall.callring.domain.calling

data class NumberRule(val prefix: String, val length: Int) {
    init {
        require(prefix.all { it in '0'..'9' })
        require(length in 1..30 && prefix.length <= length)
    }

    companion object {
        val DEFAULT = NumberRule("3", 5)
    }
}

fun interface NumberMatcher {
    fun matches(normalized: String, rule: NumberRule): Boolean
}

object PrefixLengthNumberMatcher : NumberMatcher {
    override fun matches(normalized: String, rule: NumberRule): Boolean =
        normalized.length == rule.length &&
            normalized.all { it in '0'..'9' } &&
            normalized.startsWith(rule.prefix)
}
```

- NumberIdentity.parse 只负责规范化与格式合法性；删除其旧 matchesRule 字段，所有最后消费者迁出后不保留固定规则默认重载。
- contracts 的 NumberCheck 三字段保持兼容：normalized、validProfileNumber、matchesRule。
- domain 不导入 contracts；契约 NumberRuleSettings 与 domain NumberRule 在 application 显式映射。
- NumberMatcher 必须无 IO、无可变配置、线程安全。消费方只调用 matches，不读取 prefix/length 作决策。
- 在 BackendConfiguration 注入 NumberMatcher，默认 PrefixLengthNumberMatcher；CoreAssembly 向业务根传入，业务根和 DecisionMirror、快照、导入与号码检查共享同一实例。
- 规则校验失败在业务入口转换为 VALIDATION 与明确字段错误，不把 require 异常当作对外业务响应。
- 号码检查、来电、导入共用相同匹配逻辑；不为了 AI 再复制一次规则算法。

拟新增 application/calling/NumberRuleState.kt：持有不可变 NumberRuleSnapshot(rule, revision)，可被测试替换策略；不持有 Context、数据库连接或完整 BackendGraph。初始化必须显式完成；关闭后不得重新加载。持久化服务和决策快照通过明确窄入口协作。规则状态的读取与更新使用同一业务协调 fence，不能裸读 AtomicReference 后绕过发布边界。

## 3. 文件地图

路径均相对仓库根 D:/Users/GreatWall/projects/callring。新增文件名是计划，不是现有文件。表中“接线”不等于重复业务实现；具体变更必须由后续测试证明需要，不为凑文件数修改。

### 3.1 新增

| 路径 | 唯一职责 |
|---|---|
| backend/src/main/kotlin/com/greatwall/callring/domain/calling/NumberMatcher.kt | 规则值、策略接口、默认实现 |
| backend/src/main/kotlin/com/greatwall/callring/application/calling/NumberRuleState.kt | 生命周期内共享的不可变规则快照 |
| backend/src/main/kotlin/com/greatwall/callring/application/ports/store/NumberRuleTransactions.kt | 规则配置窄读写会话 |
| backend/src/main/kotlin/com/greatwall/callring/application/settings/NumberRuleSettingsService.kt | 查询、校验、并发检查、事务和发布 |
| backend/src/main/kotlin/com/greatwall/callring/application/mapping/NumberRuleMappings.kt | domain/contract 映射及正式号码检查函数 |
| ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/NumberRuleViewModel.kt | 加载、草稿、保存、冲突与订阅 |
| ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/NumberRuleScreen.kt | 规则编辑与匹配结果表现 |

### 3.2 修改：匹配消费者与运行生命周期

| 路径 | 原因 |
|---|---|
| backend/src/main/kotlin/com/greatwall/callring/domain/model/NumberIdentity.kt | 移除固定业务匹配，只留身份解析 |
| backend/src/main/kotlin/com/greatwall/callring/domain/model/DecisionInputs.kt | 携带规则快照所需纯数据 |
| backend/src/main/kotlin/com/greatwall/callring/domain/calling/DecisionSnapshot.kt | 注入匹配策略；IncomingDecision 携带当次规则匹配事实 |
| backend/src/main/kotlin/com/greatwall/callring/application/calling/DecisionMirror.kt | 更新规则输入；保留同一匹配器 |
| backend/src/main/kotlin/com/greatwall/callring/application/calling/IncomingCallService.kt | 接收当次匹配事实，不能在异步入库时按新规则重算；保留现有统计区间检查 |
| backend/src/main/kotlin/com/greatwall/callring/application/calling/CallLogImportService.kt | 批次捕获规则与策略，循环内不重新取配置 |
| backend/src/main/kotlin/com/greatwall/callring/application/callers/CallerValidation.kt | 删除旧 callerCheck 或迁至唯一正式号码检查函数；保留 callerNumber 合法性逻辑 |
| backend/src/main/kotlin/com/greatwall/callring/application/callers/CallerQueryService.kt | 使用统一号码检查，规则纳入查询一致性键 |
| backend/src/main/kotlin/com/greatwall/callring/application/CallringBackend.kt | 共享规则状态、装配服务、保留 commandApi 语义 |
| app/src/main/kotlin/com/greatwall/callring/bootstrap/BackendConfiguration.kt | 唯一匹配策略注入参数 |
| app/src/main/kotlin/com/greatwall/callring/bootstrap/CoreAssembly.kt | 创建/初始化共享规则状态、传递策略及关闭约束 |
| app/src/main/kotlin/com/greatwall/callring/bootstrap/BackendGraph.kt | 对齐来电记录函数引用签名与初始化、关闭顺序 |
| app/src/main/kotlin/com/greatwall/callring/platform/calling/IncomingCallCoordinator.kt | 首次评估匹配事实传入异步记录；inspectAgain 保留同次来电规则事实 |

ScreeningController.kt 与 IncomingCallController.kt 仅当实际调用签名受影响才调整，不提前重写。DecisionQueryService.kt 仅当快照工厂签名必须显式传策略才调整；不能遗漏其冷启动 refresh 路径。若 matcher 由 DecisionMirror 持有并在工厂封装，则保持原文件不变。

注意：前次 39 个触点清单没有展开本节的异步来电一致性与注入生命周期，因此不是完整实施清单；本计划取代其数量承诺。

### 3.3 修改：契约、存储、转接

| 路径 | 原因 |
|---|---|
| contracts/src/main/kotlin/com/greatwall/callring/contracts/settings/SettingsApi.kt | NumberRuleSettings、SaveNumberRule 与读取/保存接口 |
| contracts/src/main/kotlin/com/greatwall/callring/contracts/change/ChangeFeed.kt | NUMBER_RULES 精准失效领域 |
| backend/src/main/kotlin/com/greatwall/callring/application/ports/store/CoreUnitOfWork.kt | 聚合规则窄端口与会话 |
| app/src/main/kotlin/com/greatwall/callring/data/settings/SqliteSimpleSettings.kt | settings 表单条 number_rule 配置原子读写，固定 JSON 编码 |
| app/src/main/kotlin/com/greatwall/callring/data/database/SqliteCoreUnitOfWork.kt | 会话委派与决策输入取值 |
| backend/src/main/kotlin/com/greatwall/callring/application/settings/SettingsApiAssembly.kt | 接线正式服务 |

contracts 数据形状：
```kotlin
data class NumberRuleSettings(val prefix: String, val length: Int, val revision: Long)
data class SaveNumberRule(val base: NumberRuleSettings, val prefix: String, val length: Int)
```

在 SettingsApi 增加：
```kotlin
suspend fun getNumberRule(): ApiResult<NumberRuleSettings>
suspend fun saveNumberRule(command: SaveNumberRule): ApiResult<NumberRuleSettings>
```

NumberRuleSettings.revision 是独立的持久规则版本，缺失配置对应 0；变更时在同一事务内加 1，Long 溢出失败且事务回滚。它不等于 coordinator 全局业务 revision，也不从独立内存计数猜测已提交版本；coordinator marker 继续用于 AI 作用域。数据库值与规则快照均保存同一规则版本。

### 3.4 修改：AI 接入与数据投影

| 路径 | 原因 |
|---|---|
| backend/src/main/kotlin/com/greatwall/callring/application/conversation/operation/OperationId.kt | 规则读取与保存的具名身份 |
| backend/src/main/kotlin/com/greatwall/callring/application/conversation/operation/OperationCatalog.kt | 新工具定义；root_checkNumber 不再标为不依赖状态的纯计算 |
| backend/src/main/kotlin/com/greatwall/callring/application/conversation/execution/ToolExecutor.kt | 保存转接；不包含匹配算法 |
| backend/src/main/kotlin/com/greatwall/callring/application/conversation/execution/OperationScopePlanner.kt | 写操作登记及 numberRule 作用域 |
| backend/src/main/kotlin/com/greatwall/callring/application/conversation/query/ScopedToolReader.kt | 规则读取及带版本依据的号码检查 |
| backend/src/main/kotlin/com/greatwall/callring/application/conversation/execution/ReceiptVerifier.kt | 保存后正式查询读回 |
| backend/src/main/kotlin/com/greatwall/callring/application/conversation/query/SafeProjection.kt | 新契约类型的显式安全序列化 |
| app/src/main/kotlin/com/greatwall/callring/data/query/SqliteScopedReadAdapter.kt | D10.configuration 增加规则，复用相同规则编码解释器 |
| backend/src/main/kotlin/com/greatwall/callring/application/conversation/query/SourceDependencies.kt | D10 包含 numberRule 版本依赖 |
| backend/src/main/kotlin/com/greatwall/callring/application/ports/store/FullSnapshotReader.kt | 完整事实携带规则 |
| app/src/main/kotlin/com/greatwall/callring/data/database/SqliteFullSnapshotReader.kt | 同事务捕获规则 |
| backend/src/main/kotlin/com/greatwall/callring/application/conversation/query/FullSnapshotProjection.kt | 完整 D10 与局部查询采用同一规则投影 |

工具名拟定 settings_getNumberRule、settings_saveNumberRule；既有 Oxx / legacyIndex 不重排，新编号在实施时取已使用最大值之后的连续空闲值并由兼容测试固定。不复用现有工具身份。

保存参数包含 base、prefix、length；对 base 作正式业务校验，不信任模型提交的“已确认”“已生效”标签。普通规则设置明确使用 NO_SEPARATE_CONFIRM，仍经过命令授权、作用域和读回验证；无需修改 ConfirmationTargets。

root_checkNumber 保留既有名称、raw 参数及 normalized/validProfileNumber/matchesRule 三字段结果；从 D10 读取规则并保留来源版本观察，用同一 matcher 计算，不能在 ScopedToolReader 复制 startsWith 逻辑。若现有 SOURCE 执行路径只构造无来源的普通 ToolReceipt，应在规则工具适配中携带来源观察证据，不另造假 stamp。

AI 参数和查询输出只描述规则数据，不暴露具体策略类名、正则表达式或装配方式。算法升级后不修改 AI schema。

### 3.5 修改：页面

| 路径 | 原因 |
|---|---|
| ui/src/main/kotlin/com/greatwall/callring/ui/feature/settings/SettingsOverviewScreen.kt | 来电处理分组增加号码规则入口 |
| ui/src/main/kotlin/com/greatwall/callring/ui/app/SettingsRoute.kt | 新页面接线 |
| ui/src/main/kotlin/com/greatwall/callring/ui/feature/callers/CallerListViewModel.kt | 规则通知只重算匹配提示，不触发 search loader |
| ui/src/main/kotlin/com/greatwall/callring/ui/feature/callers/NumberLookupViewModel.kt | 订阅规则快照通知，显示最新匹配 |
| ui/src/main/kotlin/com/greatwall/callring/ui/app/CallringApp.kt | 提供订阅依赖并收集查询状态 |

UiDestination 现有通用设置路由可复用，不新增独立导航系统。规则编辑显式保存，加载后持有 base；并发修改失败保留用户草稿，提供重新加载，不静默覆盖。

### 3.6 不预先修改

- 不改 Gradle 模块、Manifest、电话系统组件身份、权限或发布身份。
- 不新增数据库表/列，因而不预先修改 CallringDatabase 与 StorageMigration；通过旧库缺 key 的兼容测试验证默认行为。
- 不改 CallingTransactions 或 CallerStore 以传播 matcher；算法与共享规则状态通过业务窄依赖注入。只有需要同事务读取规则的存储消费者才扩展规则读取端口。
- 不改 QueryService、RefreshCoordinator 的通用算法，不重写所有旧 AI 操作。
- 不改 ToolRegistry 的通用 schema 校验器；新规则参数的业务边界由服务校验。
- 不改 UI/AI 使它们知道 PrefixLengthNumberMatcher。
- 不为文件数制造新聚合文件；最终报告按实际 diff 区分业务、接线、投影、测试和文档。

## 4. 执行任务与验证顺序

每项先添加行为测试、运行确认失败，再做最小实现、重跑对应测试。只提交本任务范围内文件，阶段提交不宣称最终可发布；最终集成时运行统一门。不得在本次规划阶段执行以下命令。

### Task 1：建立算法替换边界

**Files:** 新增 NumberMatcher.kt；修改 NumberIdentity.kt 的迁移先在分支内保留至消费者任务完成，禁止把不能编译的中间状态作为已完成交付。
**Test:** 新增 backend/src/test/kotlin/com/greatwall/callring/NumberMatcherTest.kt。

- [ ] 添加并运行以下域行为测试：
```kotlin
package com.greatwall.callring

import com.greatwall.callring.domain.calling.*
import org.junit.Assert.*
import org.junit.Test

class NumberMatcherTest {
    @Test fun defaultRulePreservesExistingResults() {
        val matcher: NumberMatcher = PrefixLengthNumberMatcher
        assertTrue(matcher.matches("31234", NumberRule.DEFAULT))
        listOf("81234", "3123", "312345", "+31234", "３１２３４")
            .forEach { assertFalse(it, matcher.matches(it, NumberRule.DEFAULT)) }
    }

    @Test fun configurationIsData() {
        assertTrue(PrefixLengthNumberMatcher.matches("812345", NumberRule("8", 6)))
        assertTrue(PrefixLengthNumberMatcher.matches("12345", NumberRule("", 5)))
        assertFalse(PrefixLengthNumberMatcher.matches("81234", NumberRule("8", 6)))
    }

    @Test fun invalidConfigurationIsRejected() {
        listOf({ NumberRule("a", 5) }, { NumberRule("123", 2) },
            { NumberRule("", 0) }, { NumberRule("", 31) }).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { invalid() }
        }
    }
}
```
- [ ] Run: .\gradlew.bat :backend:test --tests com.greatwall.callring.NumberMatcherTest
- [ ] 失败预期：新增类型不存在；实现第 2 节代码后，预期全部通过。
- [ ] 自查：纯 domain 不导入 application/contracts/Android；默认结果无变化。
- [ ] 记录独立提交：feat: define configurable number matching strategy。

### Task 2：实现配置事务与共享状态

**Files:** 新增 NumberRuleTransactions.kt、NumberRuleState.kt、NumberRuleSettingsService.kt、NumberRuleMappings.kt；修改 3.3 文件和 CallringBackend、CoreAssembly、BackendConfiguration。
**Tests:** 新增 backend/src/test/kotlin/com/greatwall/callring/NumberRuleSettingsServiceTest.kt、app/src/test/kotlin/com/greatwall/callring/data/NumberRuleStorageTest.kt。

- [ ] 按下表建立精确断言，先运行红测：

| 测试名 | 输入/安排 | 必须断言 |
|---|---|---|
| missingKeyUsesDefaultWithoutWriting | 旧数据库没有 number_rule | 返回 3/5/revision=0，写入次数 0 |
| savePersistsAtomicValue | base=3/5/0，保存 8/6 | 读回 8/6/1，重开数据库仍一致 |
| staleBaseCannotOverwrite | A、B 同读 revision=0，A 保存后 B 保存 | B 返回 CONFLICT，数据库仍是 A 的值 |
| repeatedSameValueIsNoOp | 当前 8/6，重复同值保存 | revision 不增，不发布新失效 |
| cancelledWriteDoesNotPublish | 已取消命令 | SQL 写入及发布均为 0 |
| malformedStoredValueIsReported | 非法 JSON 或长度字段错误 | 明确失败，不用默认值伪装成功 |
| publishFollowsCommit | 注入事务失败 | 快照、版本、通知保持旧值 |
| commandBindingsShareState | 创建两个 commandApi 绑定 | 一方保存后另一方号码检查返回新结果 |
| ruleVersionOverflowRollsBack | revision=Long.MAX_VALUE | 保存失败、持久值和快照不变 |
| closedOwnerDoesNotReload | 关闭后查询/保存 | 不重开 SQL，不重新初始化状态 |

- [ ] 实现 get/save 两个正式入口。save 使用持久 base 校验；numberRule 为精确冲突键；MutationDelta 只发布 NUMBER_RULES。
- [ ] 采用单条 settings JSON 记录 version/prefix/length/revision，由一个解释器编码/解码；SQLite 查询投影复用解释器，不维护第二份缺省解释。
- [ ] CoreAssembly 显式初始化快照；初始化复用既有生命周期入口，不把数据库打开提前到原本无 IO 的图构造阶段。
- [ ] 复用现有 CommandCommitter：SQL 成功后 publishCore，再在 afterCommit 安装预先准备好的不可变规则值，均位于同一 fence。所有规则状态读取先取得同一 fence，因此收到通知的消费者不能在 afterCommit 之前取到旧规则。afterCommit 只安装已准备好的值，不解析配置、不做 IO。通过并发测试证实此顺序，不改通用提交协议；domain matcher 的纯计算不持锁、不访问存储。
- [ ] Run: .\gradlew.bat :backend:test --tests com.greatwall.callring.NumberRuleSettingsServiceTest :app:testDebugUnitTest --tests com.greatwall.callring.data.NumberRuleStorageTest
- [ ] 预期：表中断言全部通过，绑定命令不新建规则状态。
- [ ] 记录独立提交：feat: persist and publish number rule configuration。

### Task 3：迁移消费者与同次来电一致性

**Files:** 3.2 所有必改消费者、必要签名调用点；最终删除 NumberIdentity.matchesRule 与旧 callerCheck。
**Tests:** 新增 backend/src/test/kotlin/com/greatwall/callring/NumberRuleConsumerTest.kt；扩展 DecisionPublicationTest、app 的 IncomingCallCoordinatorTest、ScreeningControllerTest、CallingSqlSemanticsTest。

- [ ] 添加拒绝所有号码的替代策略，并经 BackendConfiguration 注入：
```kotlin
val rejectAll = NumberMatcher { _, _ -> false }
val acceptAllDigits = NumberMatcher { number, _ ->
    number.isNotEmpty() && number.all { it in '0'..'9' }
}
```
- [ ] 对同一组 31234/812345 断言：直接号码检查、号码概览、来电 matched、统计入库资格和导入过滤均使用注入策略；消费方不得包含新算法。
- [ ] 用可控执行器暂停来电 writer：旧规则下评估 31234 → 改为 8/6 → 放行 writer；该会话保留旧匹配事实，下一次来电按新规则。inspectAgain 不重新套用新规则。
- [ ] 暂停导入批次中间步骤并保存新规则，断言本批次保持一致，下一批使用新规则；不修改统计开关与通话去重的既有语义。
- [ ] 删除全部旧固定匹配消费者；对需要判断业务命中的地方注入 NumberMatcher，不用默认参数悄悄补回旧规则。
- [ ] Run: .\gradlew.bat :backend:test --tests com.greatwall.callring.NumberRuleConsumerTest --tests com.greatwall.callring.DecisionPublicationTest
- [ ] Run: .\gradlew.bat :app:testDebugUnitTest --tests com.greatwall.callring.platform.calling.IncomingCallCoordinatorTest --tests com.greatwall.callring.platform.calling.ScreeningControllerTest --tests com.greatwall.callring.data.CallingSqlSemanticsTest
- [ ] 预期：匹配事实固定，既有权限/静音复核仍生效，默认统计去重结果不变。
- [ ] 记录独立提交：refactor: route call consumers through number matching strategy。

### Task 4：接通 AI 查询与完整快照

**Files:** 3.4 中 SafeProjection、SqliteScopedReadAdapter、SourceDependencies、FullSnapshotReader、SqliteFullSnapshotReader、FullSnapshotProjection。
**Tests:** 扩展 app 的 SqliteScopedReadTest、CoreSnapshotSourceTest；backend 的 CoreSnapshotProjectionTest、FullSnapshotConsistencyTest。

- [ ] 红测：保存 8/6 后，局部 D10 与完整 D10 中 numberRule 均为 8/6，规则 revision 相同。
- [ ] 红测：修改规则使 D10 版本变化；D01 人员目录、D16 录音归档无写入时其版本不变。
- [ ] 用正式规则映射形成投影；不在完整投影与局部投影各实现一遍默认规则和字段解释。
- [ ] Run: .\gradlew.bat :backend:test --tests com.greatwall.callring.CoreSnapshotProjectionTest --tests com.greatwall.callring.FullSnapshotConsistencyTest :app:testDebugUnitTest --tests com.greatwall.callring.data.SqliteScopedReadTest --tests com.greatwall.callring.data.CoreSnapshotSourceTest
- [ ] 预期：两种读取一致，完整快照仍只在显式入口加载。
- [ ] 记录独立提交：feat: expose number rule through authoritative query sources。

### Task 5：接通 AI 工具、冲突保护与回执

**Files:** 3.4 中其余操作与执行文件。
**Tests:** 扩展 ToolRegistryTest、ScopedToolReaderTest、OperationScopePlannerTest、ReceiptVerifierTest、ToolCompatibilityBaselineTest；新增 backend/src/test/kotlin/com/greatwall/callring/NumberRuleToolTest.kt。

- [ ] 红测：工具目录发现 get/save，参数类型和 base 校验有效；保存不会接受模型提供的策略类名。
- [ ] 红测：AI 已读旧规则后页面修改，旧 AI 保存失败；只改外观时规则保存不会因无关作用域被拒。
- [ ] 红测：AI 保存后数据库读回不符返回 COMMITTED_UNVERIFIED，禁止宣称 VERIFIED；取消、失败、重复提交结果各自明确。
- [ ] 红测：root_checkNumber 原参数/三字段结果兼容；规则读取来源被记录为 D10 观察；替代 matcher 经唯一注入影响其结果。
- [ ] 工具执行只调用正式服务；作用域键统一 numberRule；回执以持久值及提交标记验证，不以工具返回值自证。
- [ ] Run: .\gradlew.bat :backend:test --tests com.greatwall.callring.NumberRuleToolTest --tests com.greatwall.callring.ToolRegistryTest --tests com.greatwall.callring.ScopedToolReaderTest --tests com.greatwall.callring.OperationScopePlannerTest --tests com.greatwall.callring.ReceiptVerifierTest
- [ ] 审核外部协议增量：保留旧身份/编号/字段，对新增操作与 root_checkNumber 读取策略变化写明理由。仅在本方案审核通过后更新兼容基线及其固定 hash，不能先重生成让测试变绿。
- [ ] 记录独立提交：feat: integrate number rule tools with verified business commands。

### Task 6：页面与精准刷新

**Files:** 新增 NumberRuleScreen/ViewModel；修改 3.5 文件。
**Tests:** 新增 ui/src/test/kotlin/com/greatwall/callring/ui/feature/settings/NumberRuleViewModelTest.kt、ui/src/test/kotlin/com/greatwall/callring/ui/feature/settings/NumberRuleScreenTest.kt；扩展 CallerListViewModelTest、NumberLookupViewModelTest、RefreshIsolationTest。

- [ ] 红测：prefix 非数字、长度不足和超范围均显示对应字段错误，不写入；空前缀标注不限前缀。
- [ ] 红测：页面 base 过期保存失败保留草稿；AI 修改后未编辑页面刷新，正在编辑页面显示冲突且不覆盖输入。
- [ ] 红测：规则变更时 CallerList.search loader 增量 0，号码匹配提示更新；号码查询无需修改输入即可更新。
- [ ] 红测：无关录音、报表、人员搜索的 loader 增量为 0；已保存历史记录数量不变。
- [ ] 页面使用显式保存和正式服务；说明“已有记录不重算；后续同步可能补入历史通话”。不展示策略接口等技术细节。
- [ ] Run: .\gradlew.bat :ui:testDebugUnitTest --tests com.greatwall.callring.ui.feature.settings.NumberRuleViewModelTest --tests com.greatwall.callring.ui.feature.settings.NumberRuleScreenTest --tests com.greatwall.callring.ui.feature.callers.CallerListViewModelTest --tests com.greatwall.callring.ui.feature.callers.NumberLookupViewModelTest --tests com.greatwall.callring.ui.state.RefreshIsolationTest
- [ ] 预期：双向可见、冲突明确，精准刷新通过。
- [ ] 记录独立提交：feat: add number rule settings and targeted refresh。

### Task 7：防止算法重新散落与交付验收

**Files:** tools/check_architecture.py、tools/test_architecture.py；按需 tools/check_backend_boundaries.py、tools/test_backend_boundaries.py；README.md、docs/contracts/current-api.md、docs/contracts/ai-operation-compatibility.json。
**Tests:** 新增 backend/src/test/kotlin/com/greatwall/callring/NumberMatchingIsolationTest.kt；新增 app/src/test/kotlin/com/greatwall/callring/bootstrap/NumberMatcherInjectionTest.kt。

- [ ] 在主调用链注入 rejectAll/acceptAllDigits，证明不修改 UI/AI/存储文件即可替换算法；覆盖重建决策快照与绑定命令，防止工厂重新取默认 matcher。
- [ ] 架构守卫只约束明确边界：domain 无跨层依赖；UI/AI executor 不引用 PrefixLengthNumberMatcher；消费方不得恢复旧 3[0-9]{4} 固定业务规则。必须有违规样例失败、合规样例通过的守卫测试；不做会误伤其它 startsWith 用途的全仓字符串禁令。
- [ ] 运行规则算法测试和边界守卫，随后运行一次统一完整门：
```powershell
python tools/check_architecture.py
python tools/check_backend_boundaries.py
python -m unittest discover -s tools -p test_architecture.py
python -m unittest discover -s tools -p test_backend_boundaries.py
python tools/verify_backend.py
```
- [ ] 预期：规则边界与外部协议检查通过，contracts/backend/ui/app JVM、真实 UI 类路径、构建及 lint 按唯一任务表完成；不额外重复同一完整门。
- [ ] 隔离设备验收：默认匹配、改规则后新来电、来电处理中改规则、AI 保存、重启、旧库升级。没有设备时标记未验证，不能将 JVM 通过写成真实 SIM 验收。
- [ ] 核对最终 diff，报告生产算法/业务、接口接线、投影、测试及文档的实际文件数。范围变化按原因记录，不能用原估计作为删减验证的理由。
- [ ] 实施完成后使用 callring-delivery 技能完成版本、提交、合并推送与 CI 核验；是否发包遵循后续用户指令。当前仅规划，不递增版本、不构建 APK。

## 5. 最终验收标准

- [ ] 默认规则对现有号码结果兼容，旧库无配置不触发写入。
- [ ] 页面、AI、来电及导入共用唯一算法与配置业务。
- [ ] 调整配置不需要改代码；重启后保留；非法配置不会发布。
- [ ] 相同输入/配置/输出契约下替换策略，只修改策略实现与测试，或新增策略时另改唯一装配点。
- [ ] 一次来电/一批导入的规则事实稳定，无异步新旧规则混用。
- [ ] 取消/并发/重复提交/关闭均符合项目约束；规则修改只触发必要刷新。
- [ ] AI 能发现、查询、修改及验证规则，旧工具协议兼容，无第二套业务逻辑。
- [ ] 局部与完整快照一致；所有已有固定匹配入口迁出并删除。
- [ ] 文档、自动验证、设备未验证项与 CI 状态有据可查。

## 6. 计划自审记录

- 已将“改算法不改其它地方”限定为稳定输入、配置及结果契约，未承诺新需求永远只改一个文件。
- 首期固定单条规则，没有把多规则/正则/历史重算混入必须项。
- AI 完整接入包含发现、读取来源、执行、作用域、验证、兼容与取消重试。
- 加入前一清单遗漏的异步来电一致性、策略注入和初始化生命周期；不沿用 39 文件作为完整数量承诺。
- 运行快照和 commandApi 绑定共用同一所有者，无 domain 数据库访问、全局可变默认规则或第二匹配实现。
- 尚未执行任何编码、构建或测试；上面的命令和测试名是实施验收步骤，不是已通过证据。
