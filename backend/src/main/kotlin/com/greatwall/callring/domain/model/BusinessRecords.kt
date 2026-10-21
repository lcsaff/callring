package com.greatwall.callring.domain.model

import com.greatwall.callring.domain.model.*

import java.time.LocalDate

data class ProfileRecord(
    val number: String,
    val name: String = "",
    val department: String = "",
    val positionId: Long? = null,
    val whitelisted: Boolean = false,
)

data class PositionRecord(val id: Long, val name: String, val level: Int, val active: Boolean)

data class LeaveRecord(val id: Long, val startMillis: Long, val endMillis: Long, val note: String)

data class StatisticsInterval(val startMillis: Long, val endMillis: Long?)

data class CalendarImport(
    val metadata: CalendarYear,
    val holidays: Map<LocalDate, String>,
    val makeupDates: Set<LocalDate>,
    val rawJson: String? = null,
)

data class AuditRecord(
    val kind: String,
    val target: String,
    val raw: String,
    val createdMillis: Long,
)

data class RawCallRecord(
    val id: Long,
    val number: String,
    val time: Long,
    val duration: Long?,
    val result: CallResult,
    val decision: CallDecision,
    val source: CallSource,
    val logId: String?,
    val screeningKey: String?,
    val counted: Boolean,
    val classification: ClassificationFact,
    val originalClassification: ClassificationFact,
    val account: String? = null,
    val hidden: Boolean = false,
    val linkedScreenId: Long? = null,
    val decisionNote: String? = null,
    val legacyResult: String? = null,
    val legacyDecision: String? = null,
    val legacySource: String? = null,
)
