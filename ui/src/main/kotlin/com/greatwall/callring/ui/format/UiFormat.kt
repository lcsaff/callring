package com.greatwall.callring.ui.format

import com.greatwall.callring.application.api.CallDecision
import com.greatwall.callring.application.api.CallResult
import com.greatwall.callring.application.api.PositionRelation
import com.greatwall.callring.application.api.ScoreSummary
import com.greatwall.callring.application.api.WorkReason
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure presentation of application API facts; no scoring or classification is performed here. */
object UiFormat {
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)

    fun position(relation: PositionRelation): String = when (relation) {
        PositionRelation.HIGHER -> "上级"
        PositionRelation.LOWER -> "下级"
        PositionRelation.SAME -> "同级"
        PositionRelation.UNKNOWN -> "关系未知"
    }

    fun positionAccessibility(relation: PositionRelation): String = "相对职位：${position(relation)}"

    fun duration(seconds: Long?): String {
        if (seconds == null || seconds < 0) return "时长未知"
        val hours = seconds / 3600
        val minutes = seconds % 3600 / 60
        val remainder = seconds % 60
        return buildList {
            if (hours > 0) add("$hours 小时")
            if (minutes > 0) add("$minutes 分")
            if (remainder > 0 || isEmpty()) add("$remainder 秒")
        }.joinToString(" ")
    }

    fun time(epochMillis: Long, zoneId: ZoneId): String =
        dateTime.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))

    fun workReason(reason: WorkReason): String = when (reason) {
        WorkReason.WORKING -> "工作时间"
        WorkReason.MAKEUP_WORKING -> "调休上班"
        WorkReason.PERSONAL_LEAVE -> "请假期间"
        WorkReason.HOLIDAY -> "法定节假日"
        WorkReason.REST_DAY -> "休息日"
        WorkReason.OUTSIDE_WORK_HOURS -> "非工作时段"
        WorkReason.UNKNOWN -> "工作状态未知"
    }

    fun decision(decision: CallDecision): String = when (decision) {
        CallDecision.NORMAL_RINGING -> "正常响铃"
        CallDecision.WHITELIST_ALLOWED -> "白名单放行"
        CallDecision.QUIET_SILENCED -> "免打扰静音"
        CallDecision.CUSTOM_RINGTONE -> "自定义铃声"
        CallDecision.SYSTEM_RINGTONE_FALLBACK -> "系统铃声兜底"
        CallDecision.SYSTEM_LOG_ONLY -> "仅系统记录"
        CallDecision.UNKNOWN -> "处理方式未知"
    }

    fun result(result: CallResult): String = when (result) {
        CallResult.ANSWERED -> "已接听"
        CallResult.MISSED -> "未接听"
        CallResult.REJECTED -> "已拒接"
        CallResult.BLOCKED -> "已拦截"
        CallResult.ANSWERED_ELSEWHERE -> "其他设备已接听"
        CallResult.PENDING -> "待确认"
        CallResult.UNRESOLVED -> "待核对"
        CallResult.UNKNOWN -> "通话结果未知"
    }

    fun scoreReason(summary: ScoreSummary): String = if (summary.points == null) "暂无评分记录" else
        "共 ${summary.totalCalls} 次来电，非工作时间 ${summary.offHoursCalls} 次（权重 ${summary.offHoursWeight}），" +
            "${summary.repeatMinutes} 分钟内重复 ${summary.repeatedCalls} 次（权重 ${summary.repeatWeight}）"
}
