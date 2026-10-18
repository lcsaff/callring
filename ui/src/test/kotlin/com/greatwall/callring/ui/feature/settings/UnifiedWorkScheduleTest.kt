package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.application.api.TimeWindow
import com.greatwall.callring.application.api.WorkScheduleSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedWorkScheduleTest {
    @Test fun normalizationPreservesVersionAndZoneAndSharesMondayWindows() {
        val common = listOf(TimeWindow(510, 720), TimeWindow(780, 1050))
        val legacy = WorkScheduleSettings("UTC", mapOf(1 to common, 2 to listOf(TimeWindow(1, 2))), emptyList(), 12, 34)
        val result = normalizeWorkSchedule(legacy)
        assertEquals("UTC", result.zoneId)
        assertEquals(12L, result.configVersion)
        assertEquals(34L, result.effectiveFromEpochMillis)
        (1..5).forEach { assertEquals(common, result.weekly[it]) }
        (6..7).forEach { assertEquals(emptyList<TimeWindow>(), result.weekly[it]) }
        assertEquals(common, result.makeup)
    }

    @Test fun firstAvailableLegacyDaySuppliesStartAndFinishWithLunchInside() {
        val legacy = WorkScheduleSettings("UTC", mapOf(3 to listOf(TimeWindow(540, 1080))), emptyList(), 1, 0)
        assertEquals(listOf(TimeWindow(540, 720), TimeWindow(780, 1080)), sharedWorkWindows(legacy))
    }

    @Test fun overnightLegacyWindowsBecomeValidSameDayDefaults() {
        val legacy = WorkScheduleSettings("UTC", mapOf(1 to listOf(TimeWindow(1320, 360))), emptyList(), 1, 0)
        assertEquals(listOf(TimeWindow(540, 720), TimeWindow(780, 1080)), sharedWorkWindows(legacy))
    }
}
