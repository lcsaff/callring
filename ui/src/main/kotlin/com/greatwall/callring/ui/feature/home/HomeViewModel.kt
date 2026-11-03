package com.greatwall.callring.ui.feature.home

import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.SnapshotViewModel
import kotlinx.coroutines.CoroutineScope

class HomeViewModel(private val api: CallringUiApi, testScope: CoroutineScope? = null) : SnapshotViewModel<HomeData>(
    api.changes, setOf(DataDomain.CALLS, DataDomain.WORK_CALENDAR, DataDomain.QUIET_HOURS,
        DataDomain.STATISTICS, DataDomain.RINGTONE, DataDomain.RUNTIME), api.home::load, testScope,
    entities = mapOf(DataDomain.RUNTIME to setOf("status", "ringtonePlayback"))
) {
    fun statistics(enabled: Boolean) = write({ api.settings.setStatisticsEnabled(enabled) })
    fun ringtone(enabled: Boolean) = write({ api.ringtone.setEnabled(enabled) })
}
