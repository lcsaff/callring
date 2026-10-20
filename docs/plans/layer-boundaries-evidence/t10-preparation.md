# T10 录音与组合操作准备（非验收）

独立只读复核基于 `b99c5ae`。未修改业务代码或运行 Gradle；实施前须与后续窄端口复核。

## 业务归属与窄端口

- `platform/ai/AiSettingsService` 的字段合并、baseline 冲突、密钥作用域、验证、复制规则、安全投影和组合 patch 迁 backend。配置端口在一次提交中基于当前值计算并持久化，返回实际变化；adapter 不再承担第二次业务发布。
- `AndroidRecordingRuntime` 的身份解析、导入条件、generation 复核、attach/retry/delete 编排迁 backend；沿用既有 RecordingWorkflow 状态机。归档端口提供目标 id、generation CAS、tombstone、单号码 metadata、最早待执行时间/到期候选，替代普通操作的 `all()`。
- `NumberDeletionService` 的影响统计、预期数量/busy 验证、顺序和失败重试迁 backend。绑定端口只需按号码查绑定及删除指定 widget；音频大小使用归档标识端口，业务层不直接访问文件。
- Android Context、授权/流、复制上限、fsync/rename、元数据、owned-file 检查、媒体播放/录制、加密、持久编解码、目录观察、HTTP 与系统调度仍属 platform/data。

## 必须保持的边界行为

- `sameNumber` 现为去除非数字后比较；单号码 SQL 不得遗漏历史格式。录音独立身份为 recording event id 的负值，statistics link 可选；保留历史正 id、screening 关联优先级和 tombstone 防自动恢复。
- AI Keep 使用当前值，Set 才检查 base=current；总 revision 变化不能代替字段冲突。密钥需检查 slot marker，不能用 keyConfigured 推断未被替换。复制检查源/目标各字段，保留 AUTO、thinking DISABLED 和 OPENAI 等现有规则。
- 录音与 AI 组合 patch 一次验证/保存，保留现有麦克风权限规则。原 store 的验证、发布与 command record 迁出后删除重复实现。
- 组合删除严格按录音→核心→绑定，保持三个能力共同绑定 `continueOwnCommits=true`。中途失败保留已成功步骤并停止后续步骤；跨库删除不是原子事务。
- 先删除 owned 音频，成功后才删除归档/tombstone；文件删除失败保留可重试持久路径。导入先复制验证，提交复核 generation/command/tombstone，新归档成功后清理旧文件。
- adoptIdentity 只采用 name/organization，空值保留旧字段；expectedGeneration 检查先于 profile/edit，仍需档案 dirty merge。
- 稳定版本 backend 没有 `File.length()`；大小计算在 app 的 NumberDeletionService。backend RecordingWorkflow 的 `File(audioPath)` 应改音频引用/端口，不能机械搬运新增文件耦合。

优先回归：AiSettingsServiceTest、RecordingFolderViewTest、SpecialSecretWireRegressionTest、RecordingCommandContextTest、RecordingPublicationTest、IndependentRecordingRuntimeTest、AndroidRecordingStoreTest、NumberDeletionIntegrationTest、NumberDeletionRegressionTest、NumberDeletionToolTest、RecordingAssociationTest、RecordingWorkflowTest、SharedBusinessCommandsTest。

建议先迁配置，再做单号码查询/大小端口、组合删除/身份业务，最后收口 runtime 装配。各链路替换后删除旧 app 业务实现及兼容转发，不留下双实现。
