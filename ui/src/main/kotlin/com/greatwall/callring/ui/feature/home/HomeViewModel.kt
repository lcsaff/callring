package com.greatwall.callring.ui.feature.home

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.SnapshotViewModel

class HomeViewModel(private val api: CallringUiApi) : SnapshotViewModel<HomeData>(
    api.changes, setOf(DataDomain.CALLS, DataDomain.WORK_CALENDAR, DataDomain.QUIET_HOURS,
        DataDomain.STATISTICS, DataDomain.OVERLAY_SETTINGS, DataDomain.RINGTONE, DataDomain.RUNTIME), api.home::load
) {
    fun statistics(enabled: Boolean) = write({ api.settings.setStatisticsEnabled(enabled) })
    fun overlay(enabled: Boolean) = write({ api.settings.setOverlayEnabled(enabled) })
    fun ringtone(enabled: Boolean) = write({ api.ringtone.setEnabled(enabled) })
    fun quiet(mode: QuietMode) = write({ api.settings.setQuietMode(mode) })
    fun synchronize() = write({ api.runtime.requestCallLogSync() })
}
