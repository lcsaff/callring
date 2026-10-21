package com.greatwall.callring.application.ports.store

import com.greatwall.callring.domain.model.PositionRecord
import com.greatwall.callring.domain.model.StatisticsInterval
import com.greatwall.callring.domain.model.ScoreSettings

interface ScoreReader { fun score(): ScoreSettings }
interface ScoreWriter : ScoreReader { fun saveScore(value: ScoreSettings): Boolean }
interface ScoreTransactions {
    fun <T> readScore(block: (ScoreReader) -> T): T
    fun <T> writeScore(block: (ScoreWriter) -> T): T
}
interface OverlayReader { fun overlay(): Boolean }
interface OverlayWriter : OverlayReader { fun saveOverlay(value: Boolean): Boolean }
interface OverlayTransactions { fun <T> writeOverlay(block: (OverlayWriter) -> T): T }
data class OpenStatistics(val id: Long, val interval: StatisticsInterval)
data class StatisticsHead(val open: OpenStatistics?, val maxClosedEnd: Long?)
interface StatisticsReader { fun statisticsHead(): StatisticsHead }
interface StatisticsWriter : StatisticsReader {
    fun openStatistics(at: Long)
    fun closeStatistics(id: Long, at: Long)
}
interface StatisticsTransactions { fun <T> writeStatistics(block: (StatisticsWriter) -> T): T }
interface PositionReader {
    fun positions(): List<PositionRecord>
    fun position(id: Long): PositionRecord?
    fun myPositionId(): Long?
    fun positionNameExists(name: String, except: Long?): Boolean
    fun positionReferenced(id: Long): Boolean
    fun positionNumbers(id: Long): Set<String>
}
interface PositionWriter : PositionReader {
    fun putPosition(value: PositionRecord, insert: Boolean): PositionRecord
    fun deletePosition(id: Long)
    fun saveMyPosition(id: Long?)
}
interface PositionTransactions {
    fun <T> readPositions(block: (PositionReader) -> T): T
    fun <T> writePositions(block: (PositionWriter) -> T): T
}
data class CoreSettingsSummary(val statisticsEnabled: Boolean, val overlayEnabled: Boolean,
    val quietMode: com.greatwall.callring.domain.model.QuietMode, val myPosition: PositionRecord?,
    val zone: String, val score: ScoreSettings)
interface SummaryReader { fun settingsSummary(): CoreSettingsSummary }
interface SummaryTransactions { fun <T> readSummary(block: (SummaryReader) -> T): T }
