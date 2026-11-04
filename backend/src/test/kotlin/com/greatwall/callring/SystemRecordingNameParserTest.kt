package com.greatwall.callring

import com.greatwall.callring.domain.recording.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SystemRecordingNameParserTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    @Test fun explicitNumberAndBothTimestampShapesAreAccepted() {
        val expected = ParsedSystemRecording("12345", Instant.parse("2026-11-04T04:00:00Z").toEpochMilli())
        for (name in listOf("示例(12345)_20261104120000.mp3", "12345_20261104_120000.wav", "12345_20261104-120000.mp3"))
            assertEquals(name, expected, SystemRecordingNameParser.parse(name, zone))
    }
    @Test fun malformedOrAmbiguousIdentityNeverBecomesACandidate() {
        for (name in listOf("示例(12345)_20260230120000.mp3", "12345_20261104_250000.wav", "12345_20261104_12000.mp3",
            "示例_20261104120000.mp3", "12345_67890_20261104_120000.mp3", "甲(12345)(67890)_20261104120000.mp3",
            "12345_20261104_120000_20261104_130000.mp3"))
            assertNull(name, SystemRecordingNameParser.parse(name, zone))
    }
    @Test fun daylightSavingGapAndOverlapAreRejected() {
        val ny = ZoneId.of("America/New_York")
        assertNull(SystemRecordingNameParser.parse("12345_20260308_023000.mp3", ny))
        assertNull(SystemRecordingNameParser.parse("12345_20261101_013000.mp3", ny))
    }
    @Test fun fourDigitYearIsNotLimitedToThisCentury() {
        assertNotNull(SystemRecordingNameParser.parse("12345_19990101_120000.mp3", zone))
    }
    @Test fun windowUsesElapsedDaysInclusiveBoundsAndLongArithmetic() {
        val now = Instant.parse("2026-11-04T04:00:00Z").toEpochMilli()
        val window = SystemRecordingWindow(now, 7)
        assertTrue(window.contains(now)); assertTrue(window.contains(now - 7L * 86_400_000))
        assertFalse(window.contains(now + 1)); assertFalse(window.contains(window.since - 1))
        assertEquals(now - Int.MAX_VALUE.toLong() * 86_400_000, SystemRecordingWindow(now, Int.MAX_VALUE).since)
        assertThrows(IllegalArgumentException::class.java) { SystemRecordingWindow(now, 0) }
    }
}
