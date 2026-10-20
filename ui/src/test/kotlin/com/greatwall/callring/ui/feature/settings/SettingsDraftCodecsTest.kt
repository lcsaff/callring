package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.contracts.settings.*
import org.junit.Assert.*
import org.junit.Test

class SettingsDraftCodecsTest {
    @Test fun appearanceRoundTripPreservesUnknownIdCustomSeedAndMode() {
        val value = AppearanceSettings(ThemeSelection.PRESET, "future:主题", 0, ColorMode.DARK)
        assertEquals(value, SettingsDraftCodecs.appearance.decode(SettingsDraftCodecs.appearance.encode(value)))
    }

    @Test fun quietRoundTripPreservesCrossMidnightWindows() {
        val value = QuietSettings(QuietMode.CUSTOM, true, mapOf(7 to listOf(TimeWindow(1380, 60)), 1 to emptyList()))
        assertEquals(value, SettingsDraftCodecs.quiet.decode(SettingsDraftCodecs.quiet.encode(value)))
    }

    @Test fun workRoundTripPreservesZoneMakeupAndVersion() {
        val value = WorkScheduleSettings("Europe/Paris", mapOf(1 to listOf(TimeWindow(540, 720), TimeWindow(780, 1080))), listOf(TimeWindow(600, 1200)), 42, 50)
        assertEquals(value, SettingsDraftCodecs.work.decode(SettingsDraftCodecs.work.encode(value)))
    }

    @Test fun malformedRestoredDraftIsDiscarded() {
        assertNull(SettingsDraftCodecs.appearance.decode("corrupt"))
        assertNull(SettingsDraftCodecs.work.decode("corrupt"))
        assertNull(SettingsDraftCodecs.quiet.decode("corrupt"))
    }
}
