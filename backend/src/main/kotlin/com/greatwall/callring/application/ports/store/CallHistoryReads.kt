package com.greatwall.callring.application.ports.store

data class CallHistoryBoundary(val time: Long, val id: Long)

data class CallHistoryCaller(
    val name: String,
    val region: String,
    val selfReportedPosition: String,
    val configuredPosition: String?,
)

interface CallHistoryReader {
    fun callHistoryCallers(numbers: Set<String>): Map<String, CallHistoryCaller>
    fun visibleCallHistory(number: String?, after: CallHistoryBoundary?, limit: Int): List<ReportCallFact>
    fun visibleCallHistoryDetail(id: Long): ReportCallFact?
}

interface CallHistoryReads {
    fun <T> readCallHistory(block: (CallHistoryReader) -> T): T
}
