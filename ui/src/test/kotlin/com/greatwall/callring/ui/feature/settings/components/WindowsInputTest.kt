package com.greatwall.callring.ui.feature.settings.components

import com.greatwall.callring.application.api.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowsInputTest {
    @Test fun midnightMustUseZeroInsteadOfOutOfRangeMinute1440() {
        assertNull(parseWindows("09:00-24:00"))
        assertEquals(listOf(TimeWindow(1320, 0)), parseWindows("22:00-00:00"))
        assertEquals(listOf(TimeWindow(0, 1439)), parseWindows("00:00-23:59"))
    }
    @Test fun emptySegmentsAndMalformedTimesAreRejected() {
        listOf(",", "09:00-12:00,", ",09:00-12:00", "09:00-12:00,,13:00-18:00",
            "09:60-12:00", "25:00-12:00", "09:00-09:00", "not a time").forEach {
            assertNull("Invalid input: $it", parseWindows(it))
        }
        assertEquals(emptyList<TimeWindow>(), parseWindows("  "))
    }
    @Test fun eachDaysMultipleAndOvernightWindowsSurviveEditingTextRoundTrip() {
        val weekly = (1..7).associateWith { day -> if (day == 7) emptyList() else
            listOf(TimeWindow(540, 720), TimeWindow(780, 1080), TimeWindow(1320, 0)) }
        assertEquals(weekly, weekly.mapValues { parseWindows(windowsText(it.value)) })
        assertEquals(listOf(TimeWindow(540, 720), TimeWindow(780, 1080)), parseWindows("09:00-12:00，13:00-18:00"))
    }
}
