package com.greatwall.callring.ui.feature.home

import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.SnapshotViewModel
import com.greatwall.callring.ui.state.RequestLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class HomeViewModel(private val api: CallringUiApi, testScope: CoroutineScope? = null) : SnapshotViewModel<HomeData>(
    api.changes, setOf(DataDomain.CALLS, DataDomain.WORK_CALENDAR, DataDomain.QUIET_HOURS,
        DataDomain.STATISTICS, DataDomain.OVERLAY_SETTINGS, DataDomain.RINGTONE, DataDomain.RUNTIME), api.home::load, testScope,
    entities = mapOf(DataDomain.RUNTIME to setOf("status", "ringtonePlayback"))
) {
    private val latestLoader = RequestLoader<CallDetail?>(scope)
    val latestPreview = latestLoader.state
    private val pending = MutableStateFlow<CallDetail?>(null)
    val pendingPreview = pending.asStateFlow()
    fun previewLatest() = latestLoader.load(preserveData = false, onSuccess = { pending.value = it }) { api.callers.getLatestCall() }
    fun consumePreview() { pending.value = null }
    fun statistics(enabled: Boolean) = write({ api.settings.setStatisticsEnabled(enabled) })
    fun overlay(enabled: Boolean) = write({ api.settings.setOverlayEnabled(enabled) })
    fun ringtone(enabled: Boolean) = write({ api.ringtone.setEnabled(enabled) })
}
