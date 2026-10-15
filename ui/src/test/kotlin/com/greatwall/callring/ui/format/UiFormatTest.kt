package com.greatwall.callring.ui.format

import com.greatwall.callring.application.api.*
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class UiFormatTest {
    @Test fun unknownDurationAndKnownZeroAreDifferent() {
        assertEquals("时长未知", UiFormat.duration(null))
        assertEquals("0 秒", UiFormat.duration(0))
        assertEquals("1 分 1 秒", UiFormat.duration(61))
        assertEquals("1 小时 1 分 1 秒", UiFormat.duration(3661))
    }

    @Test fun positionsHaveReadableAccessibleLabelsIncludingUnknown() {
        assertEquals("上级", UiFormat.position(PositionRelation.HIGHER))
        assertEquals("同级", UiFormat.position(PositionRelation.SAME))
        assertEquals("下级", UiFormat.position(PositionRelation.LOWER))
        assertEquals("关系未知", UiFormat.position(PositionRelation.UNKNOWN))
        assertEquals("相对职位：上级", UiFormat.positionAccessibility(PositionRelation.HIGHER))
    }

    @Test fun epochTimeUsesExplicitZoneInsteadOfHostDefaults() {
        assertEquals("1970-01-01 00:00", UiFormat.time(0, ZoneId.of("UTC")))
        assertEquals("1970-01-01 08:00", UiFormat.time(0, ZoneId.of("Asia/Shanghai")))
    }

    @Test fun unknownEnumsUseNeutralLanguage() {
        assertEquals("工作状态未知", UiFormat.workReason(WorkReason.UNKNOWN))
        assertEquals("处理方式未知", UiFormat.decision(CallDecision.UNKNOWN))
        assertEquals("通话结果未知", UiFormat.result(CallResult.UNKNOWN))
        assertEquals("待核对", UiFormat.result(CallResult.UNRESOLVED))
        assertEquals("待确认", UiFormat.result(CallResult.PENDING))
    }

    @Test fun scoreReasonPresentsBackendFactsWithoutRecalculatingScore() {
        val summary = ScoreSummary(99, 8, 3, 2, 5, 7, 10, TimeRange(0, 100, "UTC"))
        assertEquals("共 8 次来电，非工作时间 3 次（权重 5），10 分钟内重复 2 次（权重 7）", UiFormat.scoreReason(summary))
        assertEquals("暂无评分记录", UiFormat.scoreReason(summary.copy(points = null)))
    }
}
