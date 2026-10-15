package com.greatwall.callring.ui.designsystem

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class ScorePaletteTest {
    @Test fun everyScoreBoundaryHasItsSemanticBand() {
        for (points in listOf(0, 59)) assertEquals(ScoreBand.HIGH_UNWELCOME, ScorePalette.band(points))
        for (points in listOf(60, 79)) assertEquals(ScoreBand.MEDIUM_UNWELCOME, ScorePalette.band(points))
        for (points in listOf(80, 100)) assertEquals(ScoreBand.LOW_UNWELCOME, ScorePalette.band(points))
        assertEquals(ScoreBand.UNAVAILABLE, ScorePalette.band(null))
    }

    @Test fun semanticColorsRemainRedYellowGreenAndNeutralInBothModes() {
        for (dark in listOf(false, true)) {
            val red = ScorePalette.colors(59, dark).foreground
            val yellow = ScorePalette.colors(60, dark).foreground
            val green = ScorePalette.colors(80, dark).foreground
            val neutral = ScorePalette.colors(null, dark).foreground
            assertTrue(red.red > red.green && red.red > red.blue)
            assertTrue(yellow.red > yellow.blue && yellow.green > yellow.blue)
            assertTrue(green.green > green.red && green.green > green.blue)
            assertEquals(neutral.red, neutral.green, 0.02f)
            assertEquals(neutral.green, neutral.blue, 0.02f)
        }
    }

    @Test fun scoresIncludeTextualMeaningRatherThanColorAlone() {
        assertEquals("不受欢迎程度低", ScorePalette.band(100).label)
        assertEquals("不受欢迎程度中", ScorePalette.band(60).label)
        assertEquals("不受欢迎程度高", ScorePalette.band(0).label)
        assertEquals("暂无评分", ScorePalette.band(null).label)
    }

    @Test fun scoreTextIsReadableOnItsOwnContainerInBothModes() {
        for (dark in listOf(false, true)) for (points in listOf(null, 0, 60, 100)) {
            val colors = ScorePalette.colors(points, dark)
            val a = colors.foreground.luminance()
            val b = colors.background.luminance()
            assertTrue((maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05) >= 4.5)
        }
    }
}
