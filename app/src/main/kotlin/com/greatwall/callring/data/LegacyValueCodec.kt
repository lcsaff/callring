package com.greatwall.callring.data

import com.greatwall.callring.domain.model.CallResult
import com.greatwall.callring.domain.model.CallDecision
import com.greatwall.callring.domain.model.CallSource
import com.greatwall.callring.domain.model.ClassificationFact
import com.greatwall.callring.domain.model.WorkReason
import com.greatwall.callring.contracts.callers.*

/** The sole compatibility boundary for human-readable values in schema versions 1–3. */
object LegacyValueCodec {
    fun result(raw: String): CallResult =
        when (raw) {
            "已接听" -> CallResult.ANSWERED
            "未接" -> CallResult.MISSED
            "拒接" -> CallResult.REJECTED
            "被拦截" -> CallResult.BLOCKED
            "其他设备接听" -> CallResult.ANSWERED_ELSEWHERE
            "待补全" -> CallResult.PENDING
            "待核对·未计入" -> CallResult.UNRESOLVED
            else -> CallResult.UNKNOWN
        }

    fun encodeResult(value: CallResult, legacy: String? = null): String =
        legacy
            ?: when (value) {
                CallResult.ANSWERED -> "已接听"
                CallResult.MISSED -> "未接"
                CallResult.REJECTED -> "拒接"
                CallResult.BLOCKED -> "被拦截"
                CallResult.ANSWERED_ELSEWHERE -> "其他设备接听"
                CallResult.PENDING -> "待补全"
                CallResult.UNRESOLVED -> "待核对·未计入"
                CallResult.UNKNOWN -> "未知"
            }

    fun decision(raw: String): CallDecision =
        when {
            raw == "正常响铃" || raw == "正常放行" -> CallDecision.NORMAL_RINGING
            raw == "白名单放行" -> CallDecision.WHITELIST_ALLOWED
            raw == "免打扰静音" -> CallDecision.QUIET_SILENCED
            raw == "规则铃声" -> CallDecision.CUSTOM_RINGTONE
            raw == "系统记录补全" -> CallDecision.SYSTEM_LOG_ONLY
            raw.contains("保留系统铃声") -> CallDecision.SYSTEM_RINGTONE_FALLBACK
            else -> CallDecision.UNKNOWN
        }

    fun encodeDecision(value: CallDecision, legacy: String? = null, note: String? = null): String =
        legacy
            ?: note
            ?: when (value) {
                CallDecision.NORMAL_RINGING -> "正常响铃"
                CallDecision.WHITELIST_ALLOWED -> "白名单放行"
                CallDecision.QUIET_SILENCED -> "免打扰静音"
                CallDecision.CUSTOM_RINGTONE -> "规则铃声"
                CallDecision.SYSTEM_LOG_ONLY -> "系统记录补全"
                CallDecision.SYSTEM_RINGTONE_FALLBACK -> "保留系统铃声"
                CallDecision.UNKNOWN -> "未知"
            }

    fun source(raw: String): CallSource =
        when (raw) {
            "筛选" -> CallSource.SCREENING
            "系统通话记录" -> CallSource.SYSTEM_CALL_LOG
            "筛选＋系统通话记录" -> CallSource.MERGED
            else -> CallSource.UNKNOWN
        }

    fun encodeSource(value: CallSource, legacy: String? = null): String =
        legacy
            ?: when (value) {
                CallSource.SCREENING -> "筛选"
                CallSource.SYSTEM_CALL_LOG -> "系统通话记录"
                CallSource.MERGED -> "筛选＋系统通话记录"
                CallSource.UNKNOWN -> "未知"
            }

    fun reason(raw: String): WorkReason =
        when {
            raw == "工作中" -> WorkReason.WORKING
            raw == "调休上班" -> WorkReason.MAKEUP_WORKING
            raw == "请假期间" -> WorkReason.PERSONAL_LEAVE
            raw.startsWith("节假日·") -> WorkReason.HOLIDAY
            raw == "休息日" -> WorkReason.REST_DAY
            raw == "工作时段外" -> WorkReason.OUTSIDE_WORK_HOURS
            else -> WorkReason.UNKNOWN
        }

    fun holidayName(raw: String): String? = if (raw.startsWith("节假日·")) raw.substring(4) else null

    fun encodeReason(fact: ClassificationFact): String =
        when (fact.reason) {
            WorkReason.WORKING -> "工作中"
            WorkReason.MAKEUP_WORKING -> "调休上班"
            WorkReason.PERSONAL_LEAVE -> "请假期间"
            WorkReason.HOLIDAY -> "节假日·${fact.holidayName.orEmpty()}"
            WorkReason.REST_DAY -> "休息日"
            WorkReason.OUTSIDE_WORK_HOURS -> "工作时段外"
            WorkReason.UNKNOWN -> "未知"
        }

    fun calendarVersion(raw: String?): String? = raw?.takeUnless { it == "未核验" }

    fun encodeCalendarVersion(raw: String?): String = raw ?: "未核验"

    const val MAKEUP_DAY_NAME = "调休上班"
}
