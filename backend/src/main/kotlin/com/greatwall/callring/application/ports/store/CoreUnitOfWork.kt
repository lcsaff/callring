package com.greatwall.callring.application.ports.store

import com.greatwall.callring.domain.model.QuietSettings
import com.greatwall.callring.domain.model.QuietMode

data class QuietConfiguration(val settings: QuietSettings, val lastMode: QuietMode)
interface QuietReader { fun quiet(): QuietConfiguration }
interface QuietWriter : QuietReader { fun saveQuiet(value: QuietConfiguration): Boolean }
interface QuietTransactions {
    fun <T> readQuiet(block: (QuietReader) -> T): T
    fun <T> writeQuiet(block: (QuietWriter) -> T): T
}
interface CoreReadSession : QuietReader, ScoreReader, OverlayReader, StatisticsReader, PositionReader, SummaryReader {
}
interface CoreWriteSession : CoreReadSession, QuietWriter, ScoreWriter, OverlayWriter, StatisticsWriter, PositionWriter {
}

interface CoreUnitOfWork : QuietTransactions, ScoreTransactions, OverlayTransactions, StatisticsTransactions, PositionTransactions, SummaryTransactions {
    fun <T> readSession(block: (CoreReadSession) -> T): T
    fun <T> writeSession(block: (CoreWriteSession) -> T): T
    override fun <T> readScore(block: (ScoreReader) -> T): T = readSession { block(it) }
    override fun <T> writeScore(block: (ScoreWriter) -> T): T = writeSession { block(it) }
    override fun <T> writeOverlay(block: (OverlayWriter) -> T): T = writeSession { block(it) }
    override fun <T> writeStatistics(block: (StatisticsWriter) -> T): T = writeSession { block(it) }
    override fun <T> readPositions(block: (PositionReader) -> T): T = readSession { block(it) }
    override fun <T> writePositions(block: (PositionWriter) -> T): T = writeSession { block(it) }
    override fun <T> readSummary(block: (SummaryReader) -> T): T = readSession { block(it) }
}
