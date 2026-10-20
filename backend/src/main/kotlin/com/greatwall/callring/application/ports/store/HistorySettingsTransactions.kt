package com.greatwall.callring.application.ports.store

import com.greatwall.callring.domain.*
import com.greatwall.callring.domain.model.*
import java.time.LocalDate

/** Detached configuration history; contains no profiles, calls, recordings or runtime settings. */
data class WorkHistory(override val schedules: List<WorkScheduleSettings>, override val calendarYears: Map<Int, CalendarYear>,
    override val holidays: Map<LocalDate, String>, override val makeupDates: Set<LocalDate>, override val overrides: Map<LocalDate, DateOverride>,
    override val leaves: Map<Long, LeaveRecord>, override val leaveVersion: Long) : WorkInputs

data class HistoricalClassification(val id: Long, val number: String, val time: Long, val current: ClassificationFact)
interface HistoryCorrectionWriter {
    fun workHistory(): WorkHistory
    fun classificationBatch(afterId: Long, limit: Int): List<HistoricalClassification>
    fun updateClassification(before: HistoricalClassification, current: ClassificationFact)
    fun appendAudit(value: AuditRecord)
}
data class CalendarOverview(val years: List<CalendarYear>, val overrides: List<DateOverride>)
interface CalendarReader { fun calendarOverview(): CalendarOverview }
interface CalendarWriter : CalendarReader, HistoryCorrectionWriter {
    fun calendarYear(year: Int): CalendarImport?
    fun replaceCalendar(value: CalendarImport)
    fun dateOverride(date: LocalDate): DateOverride?
    fun putDateOverride(value: DateOverride)
    fun removeDateOverride(date: LocalDate)
}
interface CalendarTransactions {
    fun <T> readCalendar(block: (CalendarReader) -> T): T
    fun <T> writeCalendar(block: (CalendarWriter) -> T): T
}
interface LeaveReader { fun leaves(): List<LeaveRecord> }
interface LeaveWriter : LeaveReader, HistoryCorrectionWriter {
    fun leave(id: Long): LeaveRecord?
    fun putLeave(value: LeaveRecord, insert: Boolean): LeaveRecord
    fun removeLeave(id: Long)
    fun incrementLeaveVersion()
}
interface LeaveTransactions {
    fun <T> readLeaves(block: (LeaveReader) -> T): T
    fun <T> writeLeaves(block: (LeaveWriter) -> T): T
}
