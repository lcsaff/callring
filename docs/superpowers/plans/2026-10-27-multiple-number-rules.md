# 多条来电号码规则 Implementation Plan

> 本文件为本次实施记录，不是新的项目规则入口；当前规则集中维护于 AGENTS.md 与 PROJECT.md。用户已确认允许清空，空列表表示全部不匹配；延续减少重复审核、尽快完成的要求。

**Goal:** 每条规则并排编辑号码前缀与总长度，支持 + 添加、删除到零条、整组保存与 AI 完整读写。
**Architecture:** 保留单条 NumberMatcher 策略，规则快照集中执行任一条命中的 OR 组合；正式服务负责完整列表的校验、版本冲突、原子写入和精准失效；唯一存储解释器兼容旧 JSON。
**Tech Stack:** Kotlin/JVM、SQLite、Compose、既有四模块及 AI 目录/作用域/回执。

## 已确认设计

- 前缀已支持多位数字；只允许 ASCII 0–9，长度不超过该条号码总长度（1..30）。空前缀继续表示不限前缀，前导零作为字符串保留。页面提示改为明确可输入多位数字。
- 任一条命中即匹配；零条永不匹配。规则按展示顺序保存；允许重叠，重复或重叠不会产生多次来电处理。已有记录不删除、不重算。
- 每行两个输入框并排，提供删除动作；+ 添加未填写的新行，未填有效总长度不能保存。空列表本身可保存，页面明确提示不会匹配号码。
- 整组显式保存，base 保留加载时的完整列表与 revision；并发冲突保留所有草稿行和重新加载入口；同次来电/同批导入继续捕获同一不可变快照。
- contracts: NumberRuleEntry(prefix:String,length:Int); NumberRuleSettings(rules:List<NumberRuleEntry>,revision:Long); SaveNumberRule(base:NumberRuleSettings,rules:List<NumberRuleEntry>)。
- domain: NumberRule 保持单条值与 NumberMatcher 接口；NumberRuleSnapshot 改为 rules 列表和 revision，并集中提供匹配任一规则的逻辑，消费者不各写一套列表匹配。
- 持久 JSON version=2 存 rules/revision；version=1 的 prefix/length/revision 解释为单条列表并保留 revision。缺键默认单条 3/5，显式 version=2 空列表不回退默认；损坏配置明确失败。无 SQL 结构变更。
- AI 继续使用现有 X10/X11 工具身份，参数/结果正式改为 rules 数组；保存仍须 base，继续精确 numberRule 作用域、D10 来源观察和持久化读回。此次规则由单条变为列表是显式协议增量，精准更新冻结基线与摘要，不保留第二套旧工具实现。
- 单条比较最多检查 30 位。N 条规则最坏 O(N×30)，长度不同提前跳过、命中即停止；无需为前缀增加不必要的一位数限制。

## 任务与验证

### 1. 后端、迁移、真实消费者
Files: contracts/settings/SettingsApi.kt；domain/calling/NumberMatcher.kt；application/mapping/NumberRuleMappings.kt；application/settings/NumberRuleSettingsService.kt；application/calling；app/data/codec/NumberRuleCodec.kt；app/data/settings/SqliteSimpleSettings.kt；相应存储/生命周期/来电测试。
- [x] 先添加零条、任一命中、多位前缀/前导零、旧 JSON 无损读取、version=2 空列表重开、坏行不写入、列表冲突与取消/回滚测试，运行确认红。
- [x] 实现正式列表契约与不可变快照，只在唯一组合处遍历匹配；事务保存完整列表，校验错误携带 rules[index].prefix/length。
- [x] 更新实际号码检查、概览、来电和导入消费者与测试，保留原有首次匹配事实和锁边界。
- [x] 定向运行 :backend:test 相关号码规则测试及 :app:testDebugUnitTest 存储/调用/装配测试，记录红绿证据。

### 2. AI 列表读写与兼容
Files: conversation/operation/OperationCatalog.kt；execution/ToolExecutor.kt、ReceiptVerifier.kt；query/ScopedToolReader.kt、SafeProjection.kt；AI 测试、协议冻结 JSON 与摘要。
- [x] 先测试发现/读取多条、保存/增加/删除/清空、旧 base 冲突、读回列表或 revision 不符未验证、替代 matcher 与 root_checkNumber 三字段兼容。
- [x] rules 与 base.rules 为严格数组，元素为 prefix/length；来源仍是 D10，回执比较完整持久列表，不以执行器返回值自证。
- [x] 精准更新此次明确变化的参数/结果约定、固定基线及守卫摘要，保留其余工具编号与字段。

### 3. 页面并排编辑和精准刷新
Files: NumberRuleViewModel.kt、NumberRuleScreen.kt、SettingsRoute.kt、UiFakeApi.kt 及 UI/入口测试。
- [x] 先测试并排两输入、多位前缀不截断、+ 添加/删除最后一条、空列表说明与保存、非法行提示、冲突保留完整草稿和无关 loader 增量零。
- [x] 复用正式读写与号码检查，空列表可试匹配且必不匹配；原单条草稿恢复兼容，不让删除行导致输入/焦点串行。
- [x] 定向 UI 测试通过后由最终统一门覆盖完整 UI，不重复安排审查轮次。

### 4. 集成与交付
- [ ] PROJECT.md 同步多条配置、AI 协议及迁移/验收摘要；README 更新用户说明。
- [ ] 更新审查后的契约声明摘要；运行 python tools/verify_backend.py，保留精确任务状态/日志和未测设备项。
- [ ] 按 PROJECT.md 确定版本、提交、合并 main、推送并确认该提交 CI。当前请求未要求发布新 Release。

## 实施进度

定向红测确认旧接口缺少列表能力；完成实现后 targeted-3.log 通过：backend 47、UI 39、app 60 项，均零失败/错误/跳过。证据位于工作树 build/multiple-rules-evidence，完整统一门与最终交付状态在收尾更新。当前未进行设备/真实模型测试。
