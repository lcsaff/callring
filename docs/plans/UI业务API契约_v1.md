# UI 业务 API 契约

日期：2026-10-15。契约版本：v1。

本契约供 UI 重构与业务解耦双方并行开发，依据[UI 重构设计](UI统一管理与彻底重构_设计.md)及现有业务行为确定。UI 使用 Kotlin、Compose、MVVM，通过以下接口获取数据和执行操作。本文交付的是接口设计，接口与后端实现尚未加入生产源码。

接口、入参和返回类型归属于 `com.greatwall.callring.application.api`，由业务侧提供；UI 侧注入并消费这些接口，可以先编写内存 Fake。下列 Kotlin 代码块合起来构成契约声明，依赖 Kotlin 标准库、`java.time` 和 `kotlinx.coroutines.flow.Flow`。生产代码落地时可以按类型拆文件。

完整声明文件：[CallringUiApi.kt](../contracts/CallringUiApi.kt)。该文件放在文档目录供双方接入，不参与当前 Android 构建；接入时由业务侧统一维护一份生产声明，UI 侧只依赖它，避免双方各建一套同名类型。文件与本契约的 Kotlin 声明保持一致。

## 对接规则

- 所有 `suspend` 方法都允许从主线程调用，阻塞 I/O 和重计算由实现切换到后台执行。UI 不额外包一层数据库线程，不自行组合多个仓库查询。
- 返回值为不可变快照。集合必须复制并以只读方式交付；实现不得将内部可变实体或集合暴露给 UI。UI 草稿与返回快照分开保存。
- `ApiResult.Failure` 表示可呈现的失败。协程取消按 `CancellationException` 传播，不包装成失败，也不显示“保存失败”。
- 一次聚合查询中的 SQLite 业务数据来自同一读取快照。铃声等独立偏好在本次查询中各读取一次；运行条件和当前时间有各自采样时间，不声称跨存储和系统拥有同一事务快照。
- 保存返回成功表示持久化已提交；需要历史重分类的修改，成功时本次分类更正也已完成。通知在提交后发布，不依赖页面或其协程是否存活。
- 取消等待不等于撤销已提交的写入。页面恢复后重新查询；禁止无条件重试新增职位、请假等非幂等操作。
- API 返回稳定枚举及业务数据。UI 负责文案、颜色、图标、日期和时长格式，不通过中文文本判断状态。
- 以下接口不暴露 `CallerRepository`、`SQLiteDatabase`、`Cursor`、`Context`、`Activity`、`Intent`、`View` 或 `WindowManager`。
- 组装入口注入 `CallringUiApi`；ViewModel、悬浮窗控制器和小组件数据加载器均可独立获得它。接口中不提供全局静态 `get()`。

## API 入口

```kotlin
package com.greatwall.callring.application.api

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

interface CallringUiApi {
    val home: HomeApi
    val callers: CallersApi
    val reports: ReportsApi
    val settings: SettingsApi
    val ringtone: RingtoneApi
    val runtime: RuntimeApi
    val widgets: WidgetBindingsApi
    val changes: ChangeFeed

    // 纯计算，可同步调用；不拨号、不记录事件、不访问系统通话记录。
    fun checkNumber(raw: String): NumberCheck
}

interface HomeApi {
    suspend fun load(): ApiResult<HomeData>
}

interface CallersApi {
    suspend fun search(query: CallerSearch): ApiResult<Page<CallerProfile>>
    suspend fun getEditor(number: String?): ApiResult<CallerEditorData>
    suspend fun getOverview(query: OverviewQuery): ApiResult<CallerOverview>
    suspend fun getLatestCall(): ApiResult<CallDetail?>
    suspend fun saveProfile(command: SaveCallerProfile): ApiResult<CallerProfile>
    suspend fun setWhitelisted(number: String, enabled: Boolean): ApiResult<CallerProfile>
}

interface ReportsApi {
    suspend fun load(query: ReportQuery): ApiResult<CallerReport>
    suspend fun exportCsv(
        query: ReportExportQuery,
        destination: DocumentRef
    ): ApiResult<ExportReceipt>
}

interface SettingsApi {
    suspend fun getSummary(): ApiResult<SettingsSummary>
    suspend fun setStatisticsEnabled(enabled: Boolean): ApiResult<Unit>
    suspend fun setOverlayEnabled(enabled: Boolean): ApiResult<Unit>

    suspend fun getQuietHours(): ApiResult<QuietSettings>
    suspend fun saveQuietHours(settings: QuietSettings): ApiResult<QuietSettings>
    suspend fun setQuietMode(mode: QuietMode): ApiResult<QuietSettings>

    suspend fun getPositions(): ApiResult<PositionSettings>
    suspend fun savePosition(command: SavePosition): ApiResult<PositionOption>
    suspend fun deletePosition(id: Long): ApiResult<Unit>
    suspend fun setMyPosition(id: Long?): ApiResult<PositionSettings>

    suspend fun getWorkSchedule(): ApiResult<WorkScheduleSettings>
    suspend fun saveWorkSchedule(command: SaveWorkSchedule): ApiResult<WorkScheduleSettings>
    suspend fun resetWorkSchedule(): ApiResult<WorkScheduleSettings>

    suspend fun getCalendar(): ApiResult<CalendarInfo>
    suspend fun importCalendar(source: DocumentRef): ApiResult<CalendarInfo>
    suspend fun saveDateOverride(command: DateOverride): ApiResult<CalendarInfo>
    suspend fun deleteDateOverride(date: LocalDate): ApiResult<CalendarInfo>

    suspend fun getLeaves(): ApiResult<List<LeaveEntry>>
    suspend fun saveLeave(command: SaveLeave): ApiResult<LeaveEntry>
    suspend fun deleteLeave(id: Long): ApiResult<Unit>

    suspend fun getScoreSettings(): ApiResult<ScoreSettings>
    suspend fun saveScoreSettings(settings: ScoreSettings): ApiResult<ScoreSettings>
    suspend fun resetScoreSettings(): ApiResult<ScoreSettings>

    suspend fun getAppearance(): ApiResult<AppearanceSettings>
    suspend fun saveAppearance(settings: AppearanceSettings): ApiResult<AppearanceSettings>
}

interface RingtoneApi {
    suspend fun getSettings(): ApiResult<RingtoneSettings>
    suspend fun setEnabled(enabled: Boolean): ApiResult<RingtoneSettings>
    suspend fun importAudio(source: DocumentRef): ApiResult<RingtoneSettings>
    suspend fun restoreBuiltIn(): ApiResult<RingtoneSettings>
    suspend fun startPreview(): ApiResult<Unit>
    suspend fun stopPlayback(): ApiResult<Unit>
}

interface RuntimeApi {
    suspend fun getStatus(): ApiResult<RuntimeStatus>
    suspend fun getDiagnostics(): ApiResult<List<DiagnosticEntry>>
    suspend fun requestCallLogSync(): ApiResult<SyncRequestReceipt>
}

interface WidgetBindingsApi {
    suspend fun getBinding(widgetId: Int): ApiResult<WidgetBinding?>
    suspend fun saveBinding(widgetId: Int, number: String): ApiResult<WidgetBinding>
    suspend fun removeBinding(widgetId: Int): ApiResult<Unit>
}

interface ChangeFeed {
    fun observe(): Flow<DataChange>
}
```

`CallringUiApi` 是能力集合，业务实现可以由多个独立服务组装；不要求建立一个承载全部逻辑的实现类。`HomeApi.load()`、`CallersApi.getOverview()` 和 `ReportsApi.load()` 负责 UI 需要的聚合，UI 无需调用多个子接口自行计算结果。

## 公共结果与查询参数

```kotlin
sealed class ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>()
    data class Failure(val error: ApiError) : ApiResult<Nothing>()
}

data class ApiError(
    val code: ErrorCode,
    val message: String,
    val fieldErrors: Map<String, String> = emptyMap(),
    val requiredCapabilities: Set<Capability> = emptySet(),
    val retryable: Boolean = false
)

enum class ErrorCode {
    VALIDATION, NOT_FOUND, IN_USE, PERMISSION_REQUIRED, ROLE_REQUIRED,
    SYSTEM_RESTRICTED, SYSTEM_UNAVAILABLE, IO_FAILURE, STORAGE_FAILURE, INTERNAL
}

data class PageRequest(val pageIndex: Int = 0, val pageSize: Int = 50)

data class Page<T>(
    val items: List<T>,
    val pageIndex: Int,
    val pageSize: Int,
    val totalItems: Long,
    val meta: SnapshotMeta
)

data class SnapshotMeta(
    val dataRevision: Long,
    val loadedAtEpochMillis: Long
)

data class CallerSearch(
    val text: String = "",
    val page: PageRequest = PageRequest()
)

data class OverviewQuery(
    val number: String,
    val workAtEpochMillis: Long? = null
)

data class DateRange(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
    val zoneId: String? = null
)

data class TimeRange(
    val startInclusiveEpochMillis: Long,
    val endExclusiveEpochMillis: Long,
    val zoneId: String
)

data class ReportQuery(
    val number: String,
    val range: DateRange,
    val filter: CallFilter = CallFilter.ALL,
    val page: PageRequest = PageRequest()
)

data class ReportExportQuery(val number: String, val range: DateRange)

enum class CallFilter {
    ALL, WORKING, PERSONAL_LEAVE, HOLIDAY, REST_DAY, OUTSIDE_WORK_HOURS
}

data class DocumentRef(val uri: String)

data class NumberCheck(
    val normalized: String,
    val validProfileNumber: Boolean,
    val matchesRule: Boolean
)
```

分页从 0 开始，`pageSize` 为 1–200。超出末页返回空 `items` 和真实 `totalItems`，不自动回跳；UI 根据总数修正页码。档案按规范号码升序；明细按开始时间降序、记录 ID 降序。搜索对去除首尾空白后的号码、姓名、部门进行包含匹配，空字符串表示全部。

`DateRange` 两端日期都包含，内部转换为开始日零点至结束日次日零点，采用指定时区；`zoneId=null` 使用本次查询时配置的日历时区。结果返回实际使用的 `TimeRange`，保证 UI 能显示区间和时区。非法日期顺序、时区、页码或号码返回 `VALIDATION`。

每次查询保证其 SQLite 数据内部一致；不同分页请求之间不维持长事务或冻结数据库。UI 收到相关数据变化后，从第 0 页重新查询。`dataRevision` 是当前应用进程内的业务变更序号，用于变更关联，不代替 UI 的请求序号，也不作为持久化记录版本。进程重建后允许重新计数，不跨进程比较。分页的版本与所属聚合结果版本相同。

`DocumentRef.uri` 仅接受 UI 通过系统文件选择器获得、当前进程有相应读写授权的 `content://` URI。实现负责打开和关闭流；不接受任意本地文件路径。业务计算不接触 URI，基础设施适配器将其转换为流。

`fieldErrors` 的 key 使用入参属性路径，如 `number`、`positionId`、`level`、`range.endDateInclusive`、`weekly.1.0.endMinute`、`customSeedRgb`。UI 依据 `code`、字段路径和能力枚举处理错误，`message` 及字段值只用于提示，不作为逻辑条件。失败不得泄露 SQL、号码日志、文件路径或原始系统异常堆栈。

## 档案与号码概览

```kotlin
data class PositionOption(
    val id: Long,
    val name: String,
    val level: Int,
    val active: Boolean,
    val relationToMe: PositionRelation
)

enum class PositionRelation { HIGHER, LOWER, SAME, UNKNOWN }

data class CallerProfile(
    val number: String,
    val stored: Boolean,
    val name: String,
    val department: String,
    val position: PositionOption?,
    val relation: PositionRelation,
    val whitelisted: Boolean
)

data class CallerDraft(
    val number: String,
    val name: String,
    val department: String,
    val positionId: Long?,
    val whitelisted: Boolean
)

data class CallerEditorData(
    val draft: CallerDraft,
    val selectablePositions: List<PositionOption>,
    val myPosition: PositionOption?
)

data class SaveCallerProfile(
    val number: String,
    val name: String,
    val department: String,
    val positionId: Long?,
    val whitelisted: Boolean? = null
)

data class CallerOverview(
    val profile: CallerProfile,
    val rollingScore: ScoreSummary,
    val lifetimeCallCount: Long,
    val latestCall: CallDetail?,
    val work: WorkStatus,
    val quietMode: QuietMode,
    val numberCheck: NumberCheck,
    val quality: DataQuality,
    val meta: SnapshotMeta
)
```

号码规范化沿用现有规则：仅去除空白、连字符和括号等已有分隔符，不截取末五位，不删除国家码。档案号码须满足 `\+?[0-9]{1,30}`；参与规则统计仍须完整匹配 `3[0-9]{4}`。能建立档案不等于参与统计。

`getEditor(null)` 返回空号码的新建草稿；传入有效但未存档的号码，返回该规范号码的空档案草稿。`getOverview()` 对有效但未存档的号码返回 `stored=false` 的空档案及实际记录情况，查询不创建档案。`getLatestCall()` 无记录时成功返回 `null`。

`selectablePositions` 包括所有启用职位及当前档案已引用的停用职位。空列表合法。`myPosition` 可能为空；比较基于当前职位配置，UI 将 `HIGHER/LOWER/SAME/UNKNOWN` 映射为 `↑/↓/＝/—` 和可访问说明。编辑时选择不同选项可直接使用该选项的 `relationToMe` 预览，UI 无需自行实现层级比较规则。

`saveProfile()` 创建或更新规范号码对应的档案，修改和白名单字段处理在一次事务内完成。`whitelisted=null` 保留保存时的白名单；新档案默认为 `false`。编辑表单仅在用户修改了白名单时传明确布尔值，避免保存姓名时覆盖组件刚更新的白名单。`setWhitelisted()` 只修改该字段，不回写旧姓名、部门或职位；有效但未存档的号码会建立空档案。两个设置方法都采用目标值，重复设置同一目标值不反转状态。

`workAtEpochMillis=null` 表示当前工作状态；悬浮窗传本次来电开始时间时，使用当时生效的常规工作配置及适用的日历、请假更正。滚动评分始终以本次查询时间为结束，观察天数取评分设置；`lifetimeCallCount` 为全部历史中计入汇总的来电数。

## 来电事实与报表

```kotlin
enum class WorkReason {
    WORKING, MAKEUP_WORKING, PERSONAL_LEAVE, HOLIDAY,
    REST_DAY, OUTSIDE_WORK_HOURS, UNKNOWN
}

data class ClassificationFact(
    val working: Boolean,
    val reason: WorkReason,
    val holidayName: String?,
    val calendarVerified: Boolean,
    val zoneId: String,
    val configVersion: Long,
    val calendarVersion: String?,
    val leaveVersion: Long
)

data class WorkStatus(
    val evaluatedAtEpochMillis: Long,
    val classification: ClassificationFact,
    val nextBoundaryEpochMillis: Long?
)

enum class CallResult {
    ANSWERED, MISSED, REJECTED, BLOCKED, ANSWERED_ELSEWHERE,
    PENDING, UNRESOLVED, UNKNOWN
}

enum class CallDecision {
    NORMAL_RINGING, WHITELIST_ALLOWED, QUIET_SILENCED,
    CUSTOM_RINGTONE, SYSTEM_RINGTONE_FALLBACK, SYSTEM_LOG_ONLY, UNKNOWN
}

enum class CallSource { SCREENING, SYSTEM_CALL_LOG, MERGED, UNKNOWN }

data class CallDetail(
    val id: Long,
    val number: String,
    val startedAtEpochMillis: Long,
    val screeningAtEpochMillis: Long?,
    val durationSeconds: Long?,
    val result: CallResult,
    val decision: CallDecision,
    val source: CallSource,
    val counted: Boolean,
    val classification: ClassificationFact,
    val originalClassification: ClassificationFact,
    val corrected: Boolean,
    val decisionNote: String?
)

data class ScoreSummary(
    val points: Int?,
    val totalCalls: Long,
    val offHoursCalls: Long,
    val repeatedCalls: Long,
    val offHoursWeight: Int,
    val repeatWeight: Int,
    val repeatMinutes: Int,
    val range: TimeRange
)

data class ReportTotals(
    val totalCalls: Long,
    val workingCalls: Long,
    val offHoursCalls: Long,
    val knownDurationSeconds: Long,
    val unknownDurationCount: Long,
    val missedCount: Long,
    val rejectedCount: Long,
    val pendingCount: Long
)

data class DataQuality(
    val statisticsEnabled: Boolean,
    val callLogAccess: AccessState,
    val hasUnknownDuration: Boolean,
    val hasPendingResults: Boolean,
    val hasUnresolvedRecords: Boolean,
    val hasUnverifiedCalendar: Boolean
)

data class CallerReport(
    val profile: CallerProfile,
    val range: TimeRange,
    val totals: ReportTotals,
    val score: ScoreSummary,
    val details: Page<CallDetail>,
    val quality: DataQuality,
    val meta: SnapshotMeta
)

data class ExportReceipt(
    val number: String,
    val range: TimeRange,
    val recordCount: Long,
    val mimeType: String
)
```

报表的 `totals`、`score`、`quality` 依据整个号码和日期区间计算；`filter` 只影响 `details`，`details.totalItems` 是筛选后条数。UI 不用当前页明细重新统计汇总或评分。`counted=false` 的待核对记录仍可展示，不计入次数、时长和评分；`pendingCount` 沿用现有报表口径，包含待补全及待核对明细。

评分沿用 `max(0, 100 - 非工作次数 × 权重 - 短时间重复次数 × 权重)`，范围内没有计入记录时 `points=null`。`durationSeconds=null` 为未知，`0` 为真实零秒。`UNRESOLVED` 表示关联不确定，UI 不能将其显示为已去重完成。`rejectedCount` 包括拒接和被拦截，与现有口径一致。

`classification` 是当前报表分类，`originalClassification` 保留首次登记事实；合并展示时原始分类和决策取筛选事件。`screeningAtEpochMillis` 用于需要解释筛选与系统时间差的展示，不取代系统记录的 `startedAtEpochMillis`。`corrected` 由应用层依据分类和版本差异确定，UI 不解析原因文案判断。无法解析的旧值映射为 `UNKNOWN`，可在 `decisionNote` 保留固定诊断说明，展示层不依赖它分支。

`nextBoundaryEpochMillis` 是可选的下次状态重查提示；不能可靠提供时返回 `null`。它用于前台展示重查，不承担后台来电判定。来电仍按实际时刻重新计算。

导出始终包含所选区间的全部明细和对应汇总，不受页面分页或明细筛选影响；`recordCount` 是导出的明细行数，包含未计入汇总的可见待核对记录，不含表头和摘要。编码为 UTF-8 BOM，MIME 为 `text/csv`，沿用号码文本、公式转义、原始及更正版本、时区和当前职位比较基准。成功表示文件已写入并关闭；导出不产生业务数据变更通知。写入失败或取消可能留下部分目标文件，UI 提供覆盖重试，不将其当作成功报表。

## 设置模型

```kotlin
enum class QuietMode { OFF, NON_WORK, CUSTOM }

data class TimeWindow(val startMinute: Int, val endMinute: Int)

data class QuietSettings(
    val mode: QuietMode,
    val allNumbers: Boolean,
    val weeklyWindows: Map<Int, List<TimeWindow>>
)

data class PositionSettings(
    val options: List<PositionOption>,
    val myPositionId: Long?
)

data class SavePosition(
    val id: Long? = null,
    val name: String,
    val level: Int,
    val active: Boolean
)

data class WorkScheduleSettings(
    val zoneId: String,
    val weekly: Map<Int, List<TimeWindow>>,
    val makeup: List<TimeWindow>,
    val configVersion: Long,
    val effectiveFromEpochMillis: Long
)

data class SaveWorkSchedule(
    val zoneId: String,
    val weekly: Map<Int, List<TimeWindow>>,
    val makeup: List<TimeWindow>
)

data class CalendarYear(
    val year: Int,
    val source: String,
    val published: String,
    val version: String
)

enum class DateOverrideKind { REST, WORK }

data class DateOverride(
    val date: LocalDate,
    val kind: DateOverrideKind,
    val windows: List<TimeWindow>
)

data class CalendarInfo(
    val years: List<CalendarYear>,
    val overrides: List<DateOverride>
)

enum class LeaveState { UPCOMING, ACTIVE, FINISHED }

sealed class LeavePeriod {
    data class AllDay(
        val startDate: LocalDate,
        val endDateInclusive: LocalDate,
        val zoneId: String
    ) : LeavePeriod()

    data class Timed(
        val startInclusiveEpochMillis: Long,
        val endExclusiveEpochMillis: Long
    ) : LeavePeriod()
}

data class SaveLeave(
    val id: Long? = null,
    val period: LeavePeriod,
    val note: String
)

data class LeaveEntry(
    val id: Long,
    val startInclusiveEpochMillis: Long,
    val endExclusiveEpochMillis: Long,
    val note: String,
    val state: LeaveState
)

data class ScoreSettings(
    val offHoursWeight: Int,
    val repeatWeight: Int,
    val repeatMinutes: Int,
    val observationDays: Int
)

enum class ThemeSelection { PRESET, CUSTOM }
enum class ColorMode { FOLLOW_SYSTEM, LIGHT, DARK }

data class AppearanceSettings(
    val selection: ThemeSelection,
    val presetId: String,
    val customSeedRgb: Int?,
    val colorMode: ColorMode
)

data class SettingsSummary(
    val statisticsEnabled: Boolean,
    val overlayEnabled: Boolean,
    val quietMode: QuietMode,
    val myPosition: PositionOption?,
    val workZoneId: String,
    val score: ScoreSettings,
    val appearance: AppearanceSettings,
    val meta: SnapshotMeta
)
```

| 操作 | 业务约定 |
| --- | --- |
| 统计开关 | 默认关闭。开启以提交时刻建立启用区间，关闭保留历史；再次设置同一值不建立重复区间。开启成功后由应用层安排同步 |
| 悬浮窗开关 | 开启需悬浮窗权限，缺失返回 `PERMISSION_REQUIRED`；关闭始终可保存，UI 悬浮窗协调器收到变化后关闭活动窗口 |
| 免打扰 | 默认 `OFF`，`allNumbers=false`；`CUSTOM` 至少一个时段。`setQuietMode()` 保留范围和时段，同样校验；白名单始终绕过应用免打扰 |
| 职位 | `id=null` 新建，正 ID 更新。名称去除首尾空白后非空且唯一，层级为正整数。引用中的职位删除返回 `IN_USE`；停用保留既有引用。空我的职位 ID 表示清除 |
| 工作时段 | 星期 1–7；分钟 0–1439，起止不同，跨午夜归开始日，开始包含、结束不含。缺少的星期视为休息 |
| 工作配置 | 常规周安排及时区修改仅影响以后来电。恢复默认也建立新版本，不清除日历、日期调整、请假或历史 |
| 日期调整 | `REST` 的 `windows` 必须为空；`WORK` 至少一个合法时段。保存同一天替换其调整，删除恢复日历规则 |
| 年度日历 | UTF-8 JSON，最多 1 MiB，校验年份、来源、版本和放假/补班冲突后事务保存；保留手动日期调整 |
| 请假 | `id=null` 新建，正 ID 更新。全天包含结束日期，按指定时区转换为次日零点排他边界；时段结束须晚于开始。状态以查询时刻计算 |
| 历史更正 | 日历、日期调整和请假变更更正适用历史，保存原始分类和当时响铃事实。成功结果与随后通知包含更正后的可查询状态 |
| 评分设置 | 权重为非负整数，重复分钟、观察天数为正整数；默认 `5/2/10/30`。修改影响后续查询评分，不改原始来电事实 |
| 外观设置 | 保存选择方式、预设 ID、自定义 RGB、深浅模式；默认联通红、跟随系统。切换预设保留已保存自定义色，RGB 为 `0x000000..0xFFFFFF`，`CUSTOM` 必须有色值 |

职位选项按层级、名称、ID 排序；日历年份和日期调整按年份、日期升序；请假按开始时间、ID 降序。删除不存在的职位或请假返回 `NOT_FOUND`；删除不存在的日期调整成功且无副作用。引用完整性、参数校验和事务原子性由业务侧保证，UI 的输入检查只用于交互。

全天是请假的输入方式，既有存储只保留实际起止时刻；返回值不补造过去的原始录入类型。编辑已有请假可以使用 `Timed` 原样保存，需要改为全天时由用户明确选择。

主题 ID 和预设注册表由 UI 侧维护并交接给业务侧默认配置；接口按不透明字符串存储 `presetId`。UI 对无法识别的预设使用联通红显示兜底，不静默覆盖已保存 ID。颜色生成、评分分档颜色和深浅色样式属于 UI；业务侧仅负责持久化外观偏好，评分公式不受主题影响。

## 首页与运行条件

```kotlin
enum class Capability {
    PHONE_STATE, NOTIFICATIONS, CALL_LOG, CONTACTS,
    OVERLAY, CALL_SCREENING_ROLE, BATTERY_OPTIMIZATION_EXEMPTION
}

enum class AccessState { GRANTED, DENIED, NOT_REQUIRED, UNAVAILABLE, UNKNOWN }
enum class Audibility { AUDIBLE, SILENT, VIBRATE, SYSTEM_DND, ZERO_VOLUME, UNKNOWN }
enum class PhoneActivity { IDLE, RINGING, ACTIVE, UNKNOWN }
enum class PlaybackMode { NONE, PREVIEW, RINGTONE }
enum class PlaybackState { IDLE, PREPARING, PLAYING, UNKNOWN }

data class PlaybackStatus(
    val mode: PlaybackMode,
    val state: PlaybackState
)

data class RuntimeStatus(
    val capabilities: Map<Capability, AccessState>,
    val audibility: Audibility,
    val phoneActivity: PhoneActivity,
    val playback: PlaybackStatus,
    val pinWidgetSupported: Boolean,
    val checkedAtEpochMillis: Long
)

enum class AudioSource { BUILT_IN, IMPORTED }

data class RingtoneSettings(
    val enabled: Boolean,
    val source: AudioSource,
    val displayName: String,
    val previewSeconds: Int
)

data class HomeData(
    val ringtone: RingtoneSettings,
    val runtime: RuntimeStatus,
    val currentWork: WorkStatus,
    val latestCall: CallDetail?,
    val statisticsEnabled: Boolean,
    val overlayEnabled: Boolean,
    val quietMode: QuietMode,
    val meta: SnapshotMeta
)

data class DiagnosticEntry(
    val occurredAtEpochMillis: Long?,
    val legacyTimeText: String?,
    val message: String
)

enum class SyncRequestState { QUEUED, ALREADY_QUEUED }

data class SyncRequestReceipt(
    val state: SyncRequestState,
    val requestedAtEpochMillis: Long
)

data class WidgetBinding(val widgetId: Int, val number: String)
```

`capabilities` 必须包含全部 `Capability`。不需要通知运行时权限的系统版本返回 `NOT_REQUIRED`，无法读取实际通话状态返回 `UNKNOWN`，不伪造为空闲。`audibility` 是当前响铃条件提示，不表示应用已经获得绕过系统勿扰的能力。多个限制同时存在时，按系统勿扰、静音、振动、零音量、可响铃的顺序选取提示；读取失败为 `UNKNOWN`。

启用规则铃声需来电识别角色和电话状态权限；不具备时分别返回 `ROLE_REQUIRED` 或 `PERMISSION_REQUIRED`，不改变原开关。关闭规则铃声仍保留独立的统计和免打扰设置，并停止当前播放。试听沿用 8 秒上限，遵守权限、系统响铃状态与后台启动限制。

`startPreview()` 成功表示播放请求已接受，异步准备和实际播放状态从 `getStatus()` 获取；准备失败更新运行状态及诊断。`stopPlayback()` 可重复调用。`restoreBuiltIn()` 与导入音频都通过业务侧协调播放停止及资源替换。

音频导入最多 20 MiB，先验证可播放且时长有效，再替换应用私有文件；失败保留原有效音频。并发导入、恢复内置和离开页面导致的过期导入必须有明确串行或失效处理，旧导入不得覆盖之后已成功提交的选择。返回 `displayName` 不包含私有绝对路径；旧导入可显示通用名称“已选择的音频”。

`requestCallLogSync()` 成功仅代表系统任务已安排或已存在，不代表通话记录已补全；需要通话记录权限。完成后以数据变更通知触发重查，手机正在通话时允许继续显示未知时长。统计暂停后也可补全历史启用区间，不补算暂停区间。

诊断按最近优先返回固定状态文字，不含号码和文件 URI；既有诊断只有时分秒时，`occurredAtEpochMillis=null`，使用 `legacyTimeText`，不推断日期。

小组件接口只定义每个正 `widgetId` 的独立号码绑定；未绑定成功返回 `null`，重复移除成功。保存仅持久化并通知，不直接调用具体 WidgetProvider。系统入口负责确认该 ID 属于当前 Provider；业务侧校验 ID 和规范号码。展示字段和快捷操作仍按 UI 设计另行确定，可复用 `getOverview()`，不因本接口固定小组件字段。

## 变化通知和 UI 生命周期

```kotlin
enum class DataDomain {
    CALLERS, POSITIONS, CALLS, REPORTS, WORK_CALENDAR,
    QUIET_HOURS, SCORE_SETTINGS, STATISTICS, OVERLAY_SETTINGS,
    RINGTONE, APPEARANCE, WIDGET_BINDINGS, RUNTIME
}

data class DataChange(
    val dataRevision: Long,
    val domains: Set<DataDomain>,
    val numbers: Set<String>?,
    val widgetIds: Set<Int>?
)
```

`observe()` 在每次开始收集时先发出一次全部领域失效通知，UI 查询初始数据；随后发送提交后的变化。`numbers=null`、`widgetIds=null` 表示不限定对象，不表示没有变化。接收方依据自身订阅领域决定是否重查，禁止把 `DataChange` 当作可直接展示的完整数据。

业务变更序号在当前进程内成功提交后递增；查询采样和递增由应用层协调，相同值的无效修改可以不递增，无需为通知机制改造既有数据库结构。运行状态变化可以使用当前序号并携带 `RUNTIME`，所以不能只按序号去重所有事件。通知可合并，但必须合并领域和对象集合；无法保留精度时发送全部领域失效，不能直接丢弃最后一次相关变化。

通知不承诺跨进程死亡投递。应用恢复前台、权限结果返回、小组件系统更新、悬浮窗新会话均重新查询。时间经过本身不增加业务修订号，UI 在 `nextBoundaryEpochMillis` 到达、日期或时区系统事件后重查；提示为空时，活动界面可采用有生命周期的适度定时重查。核心来电判断不依赖这些刷新。

每个 ViewModel 维护自己的请求序号；号码、筛选、日期范围变化时取消旧查询，并检查结果仍对应当前参数。收到变化只更新已保存状态，不能直接替换编辑草稿。写入已提交后，业务侧即使因调用方取消而无法返回结果，也仍负责通知或确保恢复时可查询到结果。

| 变更 | 必须失效的领域 |
| --- | --- |
| 档案或白名单 | `CALLERS`、相关号码的 `REPORTS`；白名单影响后续来电判断 |
| 职位或我的职位 | `POSITIONS`、`CALLERS`、`REPORTS`，对象范围为全部 |
| 来电登记或系统补全 | `CALLS`、`REPORTS`、`CALLERS`，附相关号码 |
| 常规工作配置 | `WORK_CALENDAR`；当前概览和首页需重查 |
| 日历、请假或日期调整 | `WORK_CALENDAR`、`CALLS`、`REPORTS`、`CALLERS` |
| 评分设置 | `SCORE_SETTINGS`、`REPORTS`、`CALLERS` |
| 免打扰 | `QUIET_HOURS` |
| 统计开关 | `STATISTICS`、`CALLERS`、`REPORTS` |
| 悬浮窗开关 | `OVERLAY_SETTINGS` |
| 铃声开关、音频选择 | `RINGTONE`，停止或启动播放时同时更新 `RUNTIME` |
| 外观 | `APPEARANCE` |
| 小组件绑定 | `WIDGET_BINDINGS`，附相关 `widgetIds` |

UI 的应用级协调器负责订阅并安排小组件刷新、活动悬浮窗更新；数据层不依赖这两类具体实现。后台系统入口仍须能在页面未启动时装配 API、查询和渲染。`RuntimeStatus` 通过显式查询确保准确，不能假定系统授予、撤销权限或电话状态变动一定会出现在进程内通知流中。

## Android 系统操作与来电展示边界

| 操作 | UI 或系统适配器职责 | 业务接口衔接 |
| --- | --- | --- |
| 请求电话、通知、通讯录、通话记录权限 | Activity Result 和系统授权交互 | 返回后重查 `runtime.getStatus()`；如需同步调用 `requestCallLogSync()` |
| 请求来电识别角色、悬浮窗权限、电池设置 | 系统页面跳转和结果处理 | 返回后重查运行条件，再提交开关操作 |
| 选取音频、年度日历 | 打开系统文件选择器，获得读授权 | 将 `DocumentRef` 交给导入接口，UI 不读文件内容 |
| 保存 CSV | 创建系统文档，获得写授权 | 将目标 `DocumentRef` 交给 `reports.exportCsv()` |
| 添加桌面组件 | 当前 UI 提供 Provider 的系统 pin 请求 | 不向业务 API 传 Provider 类名 |
| 显示最近来电卡片 | 查询最近来电，再交给 UI 悬浮窗协调器 | `getLatestCall()` 无记录时提示；数据通过 `getOverview()` 获取 |
| 页面跳转 | 新 UI 自有导航与外部路由 | 传规范号码等业务参数，不暴露旧 Activity 类 |

为替换后端对旧 `CallerOverlayService` 的直接调用，UI 侧实现以下出站端口，并由组装入口注入来电处理流程。该端口归属于相同 API 包，数据和存储实现不得持有具体 UI 类。

```kotlin
interface IncomingCallUiSink {
    fun show(request: IncomingCallUiRequest)
}

data class IncomingCallUiRequest(
    val sessionId: String,
    val number: String,
    val startedAtEpochMillis: Long,
    val decision: CallDecision
)
```

`show()` 可从任意线程调用，UI 实现负责切换线程和合法启动窗口服务，必须迅速返回，不等待数据加载或窗口显示。后端在最终来电决策确定、规则号码匹配且悬浮窗设置开启后调用；失败不能改变已提交给 Telecom 的来电响应。`sessionId` 是每次来电独立的稳定标识，不使用号码代替；UI 按会话隔离请求，并保留锁屏隐藏、结束不重弹、草稿保护和过期请求丢弃行为。

来电处理调用方隔离该端口的异常并记录固定诊断；UI 实现不向外抛出窗口和服务启动异常。查询、通知或展示失败均不能阻塞系统响应期限。

UI 通过 `getOverview(OverviewQuery(number, startedAtEpochMillis))` 加载该次分类及当前档案、评分；即时决策采用请求中的 `decision`，不能用之后的设置重算来冒充当时决策。电话生命周期、窗口关闭和屏幕隐私由 UI 系统协调器处理，不要求 ViewModel 或页面存活。

## 页面接入对照

| UI 场景 | 首次读取 | 操作 |
| --- | --- | --- |
| 首页 | `home.load()`、`runtime.getDiagnostics()` | 铃声开关、导入与试听、请求同步、权限及导航协调 |
| 档案列表 | `callers.search()` | 搜索、分页、导航 |
| 档案编辑 | `callers.getEditor(number)` | `saveProfile()`；只切白名单用 `setWhitelisted()` |
| 报表 | `reports.load()` | 改参数重查、`exportCsv()`、`requestCallLogSync()` |
| 设置总览 | `settings.getSummary()` | 统计、悬浮窗开关及设置子页导航 |
| 各设置子页 | 对应 `get...()` | 对应 `save...()`、`delete...()`、`reset...()` |
| 悬浮窗 | `getOverview()`、编辑时 `getEditor()` | 档案保存和白名单操作；窗口行为由 UI 协调 |
| 小组件 | `widgets.getBinding()`，需要时 `getOverview()` | 已确认的业务操作、小组件系统刷新 |
| 小组件配置 | `callers.search()`、`widgets.getBinding()` | `widgets.saveBinding()` 后由 UI 安排渲染 |

UI 可以先完成接口注入、内存 Fake、UiState 和交互；业务侧按此契约提供实现。Fake 至少覆盖无记录、未知时长、真实零秒、权限缺失、保存失败、停用职位、关联待核对、历史分类更正和旧查询晚返回。现有 Java 仓库、静态事件类或中文状态字符串均不作为新 UI 的依赖。
