package com.greatwall.callring.contracts.callers

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.command.*

interface HomeApi {
    suspend fun load(): ApiResult<HomeData>
}

interface CallersApi {
    suspend fun getCallDetail(callId: Long): ApiResult<CallDetail> = aiUnavailable()
    suspend fun search(query: CallerSearch): ApiResult<Page<CallerProfile>>
    suspend fun getEditor(number: String?): ApiResult<CallerEditorData>
    suspend fun getOverview(query: OverviewQuery): ApiResult<CallerOverview>
    suspend fun getLatestCall(): ApiResult<CallDetail?>
    suspend fun saveProfile(command: SaveCallerProfile): ApiResult<CallerProfile>
    suspend fun setWhitelisted(number: String, enabled: Boolean): ApiResult<CallerProfile>
    /** 删除前预览：档案是否存在、该号码的通话记录条数。 */
    suspend fun numberDataImpact(number: String): ApiResult<NumberCoreImpact> = aiUnavailable()
    /** 单事务删除档案与该号码全部通话记录；expectedCallCount 不一致时拒绝。 */
    suspend fun deleteNumberCore(number: String, expectedCallCount: Int): ApiResult<Unit> = aiUnavailable()
}

data class CallerSearch(
    val text: String = "",
    val page: PageRequest = PageRequest()
)

data class OverviewQuery(
    val number: String,
    val workAtEpochMillis: Long? = null
)

data class NumberCheck(
    val normalized: String,
    val validProfileNumber: Boolean,
    val matchesRule: Boolean
)

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
    val whitelisted: Boolean? = null,
    val baseline:CallerDraft?=null,
    val dirtyFields:Set<String>?=null,
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

enum class WorkDayKind { WORKDAY, REST_DAY, HOLIDAY }

data class WorkStatus(
    val evaluatedAtEpochMillis: Long,
    val classification: ClassificationFact,
    val nextBoundaryEpochMillis: Long?,
    val dayKind: WorkDayKind = when (classification.reason) {
        WorkReason.HOLIDAY -> WorkDayKind.HOLIDAY
        WorkReason.REST_DAY -> WorkDayKind.REST_DAY
        else -> WorkDayKind.WORKDAY
    },
    val holidayName: String? = classification.holidayName,
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

interface IncomingCallUiSink {
    fun show(request: IncomingCallUiRequest)
}

data class IncomingCallUiRequest(
    val sessionId: String,
    val number: String,
    val startedAtEpochMillis: Long,
    val decision: CallDecision
)
