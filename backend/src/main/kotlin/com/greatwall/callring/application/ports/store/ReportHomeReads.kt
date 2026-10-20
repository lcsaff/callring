package com.greatwall.callring.application.ports.store

import com.greatwall.callring.domain.PositionRecord
import com.greatwall.callring.domain.ProfileRecord
import com.greatwall.callring.domain.RawCallRecord
import com.greatwall.callring.domain.model.WorkScheduleSettings

/** A visible current row and its direct screening origin, captured in one SQL projection. */
data class ReportCallFact(val current: RawCallRecord, val screening: RawCallRecord?)

interface ReportReader : ScoreReader, OpenStatisticsReader {
    fun caller(number: String): ProfileRecord?

    fun position(id: Long): PositionRecord?

    fun myPositionId(): Long?

    fun latestWorkSchedule(): WorkScheduleSettings?

    fun visitReportCalls(
        number: String,
        start: Long,
        end: Long,
        ascending: Boolean,
        accept: (ReportCallFact) -> Unit,
    )
}

interface ReportReads {
    fun <T> readReport(block: (ReportReader) -> T): T
}

interface OpenStatisticsReader {
    fun statisticsEnabled(): Boolean
}

interface HomeReader : OpenStatisticsReader, OverlayReader {
    fun homeQuietMode(): com.greatwall.callring.domain.model.QuietMode

    fun workHistory(): WorkHistory

    fun latestVisibleReportCall(): ReportCallFact?
}

interface HomeReads {
    fun <T> readHome(block: (HomeReader) -> T): T
}
