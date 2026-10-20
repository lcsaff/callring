package com.greatwall.callring.application.ports.query

import com.greatwall.callring.application.ports.*
import com.greatwall.callring.application.ports.store.WorkHistory
import com.greatwall.callring.contracts.ai.AggregateSpec
import com.greatwall.callring.domain.model.*
import java.time.LocalDate

data class StatisticsQueryFact(
    val number: String,
    val at: Long,
    val counted: Boolean,
    val working: Boolean,
    val duration: Long?,
    val result: CallResult,
    val calendarVerified: Boolean,
)

interface StatisticsQueryFacts {
    fun scoreSettings(): ScoreSettings

    fun latestScheduleZone(): String

    fun visit(accept: (StatisticsQueryFact) -> Unit)
}

interface WorkQueryFacts {
    fun scheduleCandidates(at: Long): List<WorkScheduleSettings>

    fun history(at: Long, until: Long, startDate: LocalDate, endDate: LocalDate): WorkHistory
}

/** Leased SELECT facts only. Visitors consume rows before the source transaction ends. */
interface CoreQueryFactsPort : ScopedReadPort {
    fun <T> readStatistics(spec: AggregateSpec, block: (StatisticsQueryFacts) -> T): T

    fun <T> readWork(block: (WorkQueryFacts) -> T): T
}
