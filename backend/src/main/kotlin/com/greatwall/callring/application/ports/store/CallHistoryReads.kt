package com.greatwall.callring.application.ports.store

data class CallHistoryBoundary(val time: Long, val id: Long)

interface CallHistoryReader {
    fun callHistoryNames(numbers: Set<String>): Map<String, String>
    fun visibleCallHistory(after: CallHistoryBoundary?, limit: Int): List<ReportCallFact>
    fun visibleCallHistoryDetail(id: Long): ReportCallFact?
}

interface CallHistoryReads {
    fun <T> readCallHistory(block: (CallHistoryReader) -> T): T
}
