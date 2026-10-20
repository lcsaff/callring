# T05 实施准备（非验收证据）

当前检查点：339ccd0，T04 正在实施。此文记录主负责人对真实代码的只读分析，不能据此勾选 T05 完成。

## 先建可比较探针

旧 StorageSession 在构造 ProfileStore / SettingsStore / ScheduleStore / CallStore 时，由 TableSnapshot 对各表执行全量查询，load 又执行一轮查询。因此不能只统计各 load 循环，更不能用 stateLoads 代替 SQL 返回行数。

探针必须覆盖 TableSnapshot 初始化、load、scalar/nextId、实际 scoped query；数据库初始化、夹具 seed、审计读取和测量预热都在被测窗口之外。仅记录表、操作、返回行/消费行等安全指标，不记录绑定参数、号码或密钥。不把返回行数等同于 SQLite 内部扫描行数。明确 JOIN/聚合的归属口径，避免重复计量。一千/一万夹具先跑旧实现，保存真实旧结果，再接入窄会话。

优先用显式 SQL 访问包装/观测依赖贯穿旧、新适配器；不要为了探针引入全局数据库注册表，或依赖 SQLiteQuery.toString 的内部格式提取 SQL。新增探针的第一提交只做观测，不改变过滤、排序、事务、schema 和业务结果。

## 同资源与锁

LazyBackendStore 当前三处泄露：stateLoadCount 的安全强转、scopedReads 的强制 SQLite 强转、close 的 AutoCloseable 强转。替换资源应显式提供 legacy（过渡）、scoped、窄事务、full snapshot、metrics、close 能力，首次打开只创建一份，所有端口共享生命周期保护。

尤其 scoped query 目前在取得实例后脱离 LazyBackendStore 监视器；并发 close 可以关闭正在使用的数据库。新资源锁覆盖完整操作，close 等待已进入的操作结束；关闭后拒绝所有端口新操作，metrics 不触发懒打开。资源工厂失败可重试但不能泄露半初始化资源。

固定 fence → 资源/SQL；ScopedReadPort.version 取得 coordinator 令牌时不能持有资源/SQL 锁。查询结束后的版本复核亦在 SQL 事务退出后执行。不要在关闭路径反向等待业务 fence。

## 第一片要贯通

窄配置切片可选 quiet，必须包括 get/save/mode/enabled 和 UiEditsApi.quiet，避免同一配置的 UI 编辑仍走旧全量入口。Quiet 的记忆模式 lastQuietMode 是实际持久化语义；不能只保存公开 QuietSettings。保留原值比较、dirty merge、命令冲突与无操作证据。

CoreUnitOfWork 可作为同库事务的内部聚合，服务只注入 QuietTransactions 等有类型窄投影，不直接拿到所有表能力。按当前切片建立实际用到的能力，不预造通用 repository 框架。跨表回滚测试必须真实更改两个表后抛错，不能仅改 settings 的两个 key 充当跨表验证。

已预热 DecisionInputs 在 quiet 提交后按新值更新不可变镜像，禁止重载所有配置/通话；未预热保持既有准备流程。首次真实精确提交必须同时有真实 UI 累计订阅与单航班重载测试。

## 通知过渡

累计快照与 ConflictScope 分开。每域 lastAny/wide/entity 含义、1024 项超限提升 wide、epoch、无操作零变化、提交后取消不丢发布都需测试。

允许旧 DataChange 暂时作为明确过渡接口，但首个新消费者必须直接使用累计快照投影令牌。不能将最近一次精确小事件发给旧 StateFlow 消费者而声称不会漏刷；其余消费者在 T11 彻底迁移。若提前统一修改 ChangeFeed 签名，应同步所有生产和测试实现，并记录阶段范围调整。

旧 core 写入口两次推进（publishScopes + changed）仅留未迁移片；新 CommandCommitter 单次提交只推进一次。无操作不推进数据版本，仍可 record 命令完成，保留 incarnation / domainMarker / own commit 保护。
