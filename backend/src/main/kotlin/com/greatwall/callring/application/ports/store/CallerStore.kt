package com.greatwall.callring.application.ports.store

import com.greatwall.callring.domain.ProfileRecord
import com.greatwall.callring.domain.RawCallRecord

/** Search visits necessary stored-profile fields in number order; no call-derived callers. */
data class CallerSearchRow(val number: String, val name: String, val department: String)

interface CallerReader {
    fun caller(number: String): ProfileRecord?

    fun callerCount(): Long

    fun callerPage(offset: Long, limit: Int): List<ProfileRecord>

    fun visitCallerSearch(accept: (CallerSearchRow) -> Unit)

    fun callers(numbers: List<String>): List<ProfileRecord>

    fun matchingCallNumbers(number: String): List<String>

    fun callCount(numbers: List<String>): Long

    fun visitCallerCallIdentities(numbers: List<String>, accept: (Long, String) -> Unit)
}

interface CallerWriter : CallerReader {
    fun putCaller(value: ProfileRecord, insert: Boolean)

    fun removeCaller(number: String)

    fun removeCallerCalls(numbers: List<String>, expectedCount: Long)
}

interface CallerPositionReader {
    fun positions(): List<com.greatwall.callring.domain.PositionRecord>

    fun position(id: Long): com.greatwall.callring.domain.PositionRecord?

    fun myPositionId(): Long?

    fun callerPositions(ids: Set<Long>): List<com.greatwall.callring.domain.PositionRecord>
}

interface CallerProfileReader : CallerReader, CallerPositionReader

interface CallerProfileWriter : CallerWriter, CallerProfileReader

interface CallerReads {
    fun <T> readCaller(block: (CallerProfileReader) -> T): T
}

interface CallerTransactions : CallerReads {
    fun <T> writeCaller(block: (CallerProfileWriter) -> T): T
}

data class CallerOverviewFact(
    val id: Long,
    val time: Long,
    val duration: Long?,
    val result: com.greatwall.callring.domain.model.CallResult,
    val counted: Boolean,
    val working: Boolean,
    val calendarVerified: Boolean,
)

interface CallerCallReader {
    fun callerCall(id: Long): RawCallRecord?

    fun latestCallerCall(): RawCallRecord?

    fun visitVisibleCallerCalls(number: String, accept: (CallerOverviewFact) -> Unit)
}

interface CallerOverviewReader :
    CallerProfileReader, CallerCallReader, ScoreReader, QuietReader, StatisticsReader {
    fun workHistory(): WorkHistory
}

interface CallerQueryTransactions {
    fun <T> readCallerOverview(block: (CallerOverviewReader) -> T): T

    fun <T> readCallerCalls(block: (CallerCallReader) -> T): T
}
