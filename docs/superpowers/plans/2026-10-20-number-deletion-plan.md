# 删除号码及其全部关联数据：实施计划（2026-10-20）

状态：待确认后实施。目标：在号码档案编辑页提供「删除该号码」入口，**删除该号码本身及其全部关联数据**，并要求**两次确认**；同时给 AI 一个受二次确认约束的删除工具。

## 1. 删除范围（"全部关联信息"的定义）

以归一化号码（`checkNumber(raw).normalized`）为唯一键，一次删除：

| # | 数据 | 存储 | 说明 |
| --- | --- | --- | --- |
| 1 | 号码档案（姓名/部门/职位/白名单） | `BackendState.profiles` | 整条记录 |
| 2 | 通话记录（含未计分、隐藏、被标记记录） | `BackendState.calls`（按 number 归一匹配） | 统计/报表随之减少；**不修改系统通话记录** |
| 3 | 录音归档（含转写与 AI 分析结果） | 录音存储（`CallArchive`） | 逐条按现有 `RecordingApi.delete(callId)` 语义删除 |
| 4 | 本地音频文件 | app 专属目录 | 随归档删除物理文件 |
| 5 | 小组件绑定 | Widget 绑定存储 | 删除该号码的绑定（小组件本身保留并显示未绑定） |
| 6 | 统计/评分派生数据 | 由 2 派生 | 读取时按剩余通话重算，不单独存储 |
| 7 | 待处理任务引用 | 内存（转写/分析队列） | 删除前若该号码有进行中的录音/转写/分析，返回冲突并提示稍后重试，避免删到一半 |

保留（不删）：审计日志（仅记录"已删除一个号码及其 N 条通话、M 条录音"，不落明文号码）；其他号码；职位/请假/日历/免打扰等全局设置（免打扰无按号码规则）。

## 2. 非目标

- 不改系统通话记录（系统数据库超出应用权限与设计范围）。
- 不提供撤销/回收站（不可恢复，确认文案必须写明）。
- 不删除其他号码的录音或统计；不做批量删除；不做跨号码清理。

## 3. 交互设计（两次确认）

入口：`CallerEditorScreen` 底部危险操作区「删除该号码及其全部数据」（红色文字按钮）。点击后：

**第一次确认（范围确认）**：调用 `impact(number)` 并展示将删除的数量与体积：
> 将永久删除：号码档案 1 条、通话记录 12 条、录音归档 3 条（音频 48.2 MB）、小组件绑定 1 个。此操作不可恢复。

按钮：`取消` / `继续`。

**第二次确认（防误触确认）**：要求输入该号码**后 4 位**（不足 4 位输入完整号码），输入匹配后「永久删除」才可点击；并展示 `删除后无法恢复` 复选（默认未勾选）。确认后执行并显示进度；成功后返回档案列表并提示"已删除"，失败时按错误码提示（冲突→刷新后重试）。

AI 侧：AI 调用删除工具时，由前台 `ForegroundActionHost` 弹出**同一套两次确认弹窗**（第一次范围 + 第二次输后 4 位），AI 不能自动通过；用户取消则工具结果记为 `DENIED`。

## 4. 接口设计

新增后端契约 `NumberDeletionApi`（`backend/.../application/api/NumberDeletionApi.kt`），挂在 `CallringUiApi.numberDeletion`，默认实现 `UnavailableNumberDeletionApi`：

```kotlin
data class NumberDeletionImpact(
    val number: String,            // 归一化号码
    val profileExists: Boolean,
    val callCount: Int,
    val archiveCount: Int,
    val audioBytes: Long,
    val widgetBindingCount: Int,
    val busy: Boolean,             // 有进行中的转写/分析/播放
)
data class DeleteNumberCommand(
    val number: String,
    val expectedCallCount: Int,
    val expectedArchiveCount: Int,
    val expectedWidgetBindingCount: Int,
)
interface NumberDeletionApi {
    suspend fun impact(number: String): ApiResult<NumberDeletionImpact>
    suspend fun delete(command: DeleteNumberCommand): ApiResult<Unit>
}
```

错误码：`number.not_found`（档案与关联数据都不存在）、`number.delete_busy`（进行中的录音/转写/分析/播放）、`number.delete_conflict`（计数与 `expected*` 不一致，需重新预览）、`number.delete_storage_failed`（音频文件删除失败，已回滚核心数据或标记孤儿文件）。

## 5. 执行顺序与一致性

删除跨两个存储（核心状态 + 录音存储），采用**幂等、可重试**的顺序：

1. `impact` 重新计数并与 `expected*` 比对，不一致 → `number.delete_conflict`（防止误删新数据）。
2. 检查 `busy`：有进行中的录音/转写/分析/播放 → `number.delete_busy`，不删任何数据。
3. 先删录音归档：逐个 `RecordingApi.delete(callId)`（每条本身原子；失败则停止并在错误中返回已删数量，重试时会重新计数）。音频文件删除失败 → 返回 `number.delete_storage_failed`，核心数据**尚不删除**。
4. 再在**单次 `store.transaction`** 中删除档案与全部通话记录，并写入一条审计；提交后 `coordinator.changed(...)` 标记 `CALLERS, CALLS, REPORTS, STATISTICS, RECORDING_ARCHIVES, WIDGET_BINDINGS`。
5. 最后删除该号码的小组件绑定（无绑定则跳过）。
6. 若第 5 步失败：核心数据已删，返回失败并提示"部分清理未完成，可在档案页重试删除以清理剩余关联"；重试时 `impact` 为 0 档案但仍有绑定 → 仅清理残留后成功。

事务说明：核心状态（档案 + 通话）必须原子；录音归档无法与核心状态同一事务，因此用"先归档、后核心、再绑定"的有序删除 + 重试幂等来保证最终一致，不承诺跨存储瞬时原子。

## 6. 落地位置

| 层 | 文件 | 改动 |
| --- | --- | --- |
| backend 契约 | `application/api/NumberDeletionApi.kt`（新）、`CallringUiApi.kt` | 新接口与 `numberDeletion` 默认实现 |
| app 实现 | `app/.../platform/callers/NumberDeletionService.kt`（新） | 编排：计数、busy 检查、归档删除、核心事务、绑定清理、审计 |
| app 装配 | `app/.../bootstrap/BackendGraph.kt` | 装配 `numberDeletion` |
| 核心删除 | `application/CallersService.kt` 或新 `NumberDeletionWorkflow` | 单事务删 profile + calls；审计 |
| UI | `ui/.../feature/callers/CallerEditorScreen.kt`、`CallerEditorForm.kt`、`CallerEditorViewModel.kt`（新删除状态）、`ui/.../feature/callers/NumberDeletionDialogs.kt`（新） | 入口、两次确认弹窗、调用与错误处理 |
| AI | `conversation/ToolRegistry.kt`（工具 81）、`ToolExecutor`、`OperationContract`、`OperationScopePlanner`、`CapabilityCatalog`、`ReceiptVerifier` | 新工具 `callers_deleteNumber`；`confirm()` 前台二次确认；删除后校验不复存在 |
| 测试 | backend/app/ui 对应测试 + `ToolOperationCoverageTest` | 见 §8 |

## 7. AI 工具设计（工具 81）

- 名称：`callers_deleteNumber`；`write=true`；schema：`number`（必填）、`expectedCallCount`/`expectedArchiveCount`/`expectedWidgetBindingCount`（必填，来自 `impact` 读数），保证"确认后数据未被改动"。
- 描述：`删除号码及其全部关联数据（档案、通话记录、录音归档、小组件绑定）；执行前必须前台二次确认，不可恢复`。
- 执行：`confirm()` 触发前台确认（复用现有删除确认通道，展示 `impact` 数量 + 输入后 4 位）→ 调用 `numberDeletion.delete`。
- 校验：删除成功后 `absent(D01, number)`、`absent(D16, callIds...)`、绑定查询为空；任一仍存在 → `COMMITTED_UNVERIFIED` 并提示核对，不重复删除。
- 操作契约/作用域：`OperationContract` 标 `number.delete`；`OperationScopePlanner` 观察 `profile:<n>:exists`、`calls:<n>`、`recordingArchive:<callId>`、`widgetBinding:<n>` 集合。
- 工具总数从 80 → 81，更新 `ToolOperationCoverageTest` 的期望集合与数量（该测试目前断言 72 个基础操作，需按现有扩展方式加入新编号）。

## 8. 测试矩阵

| 层 | 用例 |
| --- | --- |
| backend | `impact` 计数正确（含隐藏/未计分通话、多个归档、绑定）；删除后 profile/calls 都查不到；`expected*` 不一致 → conflict 且不删；busy → 拒绝；审计写入一条且不含明文号码；重复删除 → `number.not_found`（幂等） |
| app | 归档删除先于核心删除；音频文件失败时核心数据保留；绑定清理失败后可重试清理；变更域包含 CALLERS/CALLS/RECORDING_ARCHIVES/WIDGET_BINDINGS |
| AI | 工具 schema 接受必填计数；未确认 → DENIED；确认后删除成功且读回不存在；`ToolOperationCoverageTest` 更新为 81；能力目录含新工具描述 |
| UI | 入口仅在编辑已有档案时可见；第一次确认展示数量；第二次输入后 4 位不匹配时按钮禁用；勾选不可恢复前禁用；取消不调用 API；成功返回列表；冲突提示重新预览 |

## 9. 分阶段步骤

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| S1 | 契约 `NumberDeletionApi` + app 编排 + 核心事务 + 审计 + 单测 | `:backend:test` / `:app:testDebugUnitTest` |
| S2 | UI 入口 + 两次确认弹窗 + ViewModel + UI 测试 | `:ui:testDebugUnitTest` |
| S3 | AI 工具 81 + 确认通道 + 校验 + 覆盖率测试与能力目录 | `:backend:test` |
| S4 | 集成 + lint + 真机手测（删除后列表/报表/录音/小组件一致；中断重试） | 全量命令 + 设备验收 |
| S5 | 文档同步（A §4 档案页 + 能力目录/操作契约） | 文档提交 |

## 10. 风险

- **误删**：靠两次确认 + 输入后 4 位 + 计数一致性校验降低；仍无法撤销，文案必须明确。
- **跨存储一致性**：采用有序删除 + 幂等重试；中途失败会留下可重试的残留，不会出现"档案还在但录音已删光"的部分可见状态（录音先删，核心后删，档案存在期间用户仍可重试）。
- **性能**：按号码过滤全量通话/归档；现有数据量（本机数百条）可接受；`impact` 与删除都为 IO 线程执行，不阻塞 UI。
- **AI 滥用**：工具必须前台确认；工具描述明确不可恢复；确认弹窗展示计数，用户可拒绝。

## 11. 待确认（实施前请拍板）

1. 是否**同时删除应用内通话记录**（默认：是；系统通话记录不动）。
2. 是否**物理删除本地音频**（默认：是）。
3. 是否**允许 AI 触发删除**（默认：允许，但必须前台两次确认）。
4. 审计日志是否保留一条"已删除"记录（**已定：不保留审计**，删除后不留任何可关联痕迹；诊断日志随轮转自然消失）。
