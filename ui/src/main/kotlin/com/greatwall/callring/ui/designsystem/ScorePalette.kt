package com.greatwall.callring.ui.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class ScoreBand(val label: String) {
    LOW_UNWELCOME("不受欢迎程度低"),
    MEDIUM_UNWELCOME("不受欢迎程度中"),
    HIGH_UNWELCOME("不受欢迎程度高"),
    UNAVAILABLE("暂无评分"),
}

@Immutable
data class ScoreColors(val foreground: Color, val background: Color)

/** Score hues express facts independently from the user's selected UI theme. */
object ScorePalette {
    fun band(points: Int?): ScoreBand = when (points) {
        in 80..100 -> ScoreBand.LOW_UNWELCOME
        in 60..79 -> ScoreBand.MEDIUM_UNWELCOME
        in 0..59 -> ScoreBand.HIGH_UNWELCOME
        else -> ScoreBand.UNAVAILABLE
    }

    fun colors(points: Int?, dark: Boolean): ScoreColors = when (band(points)) {
        ScoreBand.LOW_UNWELCOME -> if (dark) ScoreColors(Color(0xFFA5D6A7), Color(0xFF143A23))
            else ScoreColors(Color(0xFF1B5E20), Color(0xFFE8F5E9))
        ScoreBand.MEDIUM_UNWELCOME -> if (dark) ScoreColors(Color(0xFFFFE082), Color(0xFF3E3200))
            else ScoreColors(Color(0xFF715600), Color(0xFFFFF8D8))
        ScoreBand.HIGH_UNWELCOME -> if (dark) ScoreColors(Color(0xFFFFB4AB), Color(0xFF5C1916))
            else ScoreColors(Color(0xFF9C2424), Color(0xFFFFEDEA))
        ScoreBand.UNAVAILABLE -> if (dark) ScoreColors(Color(0xFFCCCCCC), Color(0xFF303030))
            else ScoreColors(Color(0xFF555555), Color(0xFFF0F0F0))
    }
}
