package com.greatwall.callring.contracts.change

import kotlinx.coroutines.flow.StateFlow

interface ChangeFeed {
    fun snapshots(): StateFlow<InvalidationSnapshot>
}

enum class DataDomain {
    CALLERS, POSITIONS, CALLS, REPORTS, WORK_CALENDAR,
    QUIET_HOURS, SCORE_SETTINGS, STATISTICS, OVERLAY_SETTINGS,
    RINGTONE, APPEARANCE, WIDGET_BINDINGS, RUNTIME,
    RECORDING_SETTINGS, AI_SETTINGS, RECORDING_ARCHIVES
}
