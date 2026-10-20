package com.greatwall.callring.application.ports.store

import com.greatwall.callring.domain.model.QuietMode
import com.greatwall.callring.domain.model.QuietSettings

data class QuietConfiguration(val settings: QuietSettings, val lastMode: QuietMode)

interface QuietReader {
    fun quiet(): QuietConfiguration
}

interface QuietWriter : QuietReader {
    fun saveQuiet(value: QuietConfiguration): Boolean
}

interface QuietTransactions {
    fun <T> readQuiet(block: (QuietReader) -> T): T

    fun <T> writeQuiet(block: (QuietWriter) -> T): T
}

interface CoreReadSession :
    QuietReader,
    ScoreReader,
    OverlayReader,
    StatisticsReader,
    PositionReader,
    SummaryReader,
    WorkScheduleReader,
    CalendarReader,
    LeaveReader,
    CallerOverviewReader {}

interface CoreWriteSession :
    CoreReadSession,
    QuietWriter,
    ScoreWriter,
    OverlayWriter,
    StatisticsWriter,
    PositionWriter,
    WorkScheduleWriter,
    CalendarWriter,
    LeaveWriter,
    CallerProfileWriter {}

interface CoreUnitOfWork :
    QuietTransactions,
    ScoreTransactions,
    OverlayTransactions,
    StatisticsTransactions,
    PositionTransactions,
    SummaryTransactions,
    WorkScheduleTransactions,
    CalendarTransactions,
    LeaveTransactions,
    CallerTransactions,
    CallerQueryTransactions {
    override fun <T> readCaller(block: (CallerProfileReader) -> T): T = readSession { block(it) }

    override fun <T> writeCaller(block: (CallerProfileWriter) -> T): T = writeSession { block(it) }

    override fun <T> readCallerOverview(block: (CallerOverviewReader) -> T): T = readSession {
        block(it)
    }

    override fun <T> readCallerCalls(block: (CallerCallReader) -> T): T = readSession { block(it) }

    override fun <T> readWorkSchedule(block: (WorkScheduleReader) -> T): T = readSession {
        block(it)
    }

    override fun <T> writeWorkSchedule(block: (WorkScheduleWriter) -> T): T = writeSession {
        block(it)
    }

    override fun <T> readCalendar(block: (CalendarReader) -> T): T = readSession { block(it) }

    override fun <T> writeCalendar(block: (CalendarWriter) -> T): T = writeSession { block(it) }

    override fun <T> readLeaves(block: (LeaveReader) -> T): T = readSession { block(it) }

    override fun <T> writeLeaves(block: (LeaveWriter) -> T): T = writeSession { block(it) }

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
