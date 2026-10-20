package com.greatwall.callring.application.ports.store

import com.greatwall.callring.domain.RawCallRecord
import com.greatwall.callring.domain.model.DecisionInputs

interface CallingReader {
    fun statisticsContains(at: Long): Boolean

    fun statisticsStart(): Long

    fun hasRecentPending(start: Long, end: Long): Boolean

    fun callingWorkHistory(): WorkHistory

    fun screeningCall(key: String): RawCallRecord?

    fun callingCall(id: Long): RawCallRecord?

    fun providerCalls(logIds: Set<String>): List<RawCallRecord>

    fun correlationCalls(number: String): List<RawCallRecord>
}

interface CallingWriter : CallingReader {
    fun ensureCallingProfile(number: String): Boolean

    fun insertCallingCall(row: RawCallRecord): RawCallRecord

    fun updateCallingCalls(changes: List<Pair<RawCallRecord, RawCallRecord>>)
}

interface CallingTransactions {
    fun <T> readCalling(block: (CallingReader) -> T): T

    fun <T> writeCalling(block: (CallingWriter) -> T): T
}

interface DecisionInputsReader {
    fun decisionInputs(): DecisionInputs
}

interface DecisionInputsTransactions {
    fun <T> readDecisionInputs(block: (DecisionInputsReader) -> T): T
}
