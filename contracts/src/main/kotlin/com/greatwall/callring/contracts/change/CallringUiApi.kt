package com.greatwall.callring.contracts.change

import kotlinx.coroutines.flow.Flow

interface ChangeFeed {
    fun observe(): Flow<DataChange>
}

enum class DataDomain {
    CALLERS, POSITIONS, CALLS, REPORTS, WORK_CALENDAR,
    QUIET_HOURS, SCORE_SETTINGS, STATISTICS, OVERLAY_SETTINGS,
    RINGTONE, APPEARANCE, WIDGET_BINDINGS, RUNTIME,
    RECORDING_SETTINGS, AI_SETTINGS, RECORDING_ARCHIVES
}

data class DataChange(
    val dataRevision: Long,
    val domains: Set<DataDomain>,
    val numbers: Set<String>?,
    val widgetIds: Set<Int>?
)
