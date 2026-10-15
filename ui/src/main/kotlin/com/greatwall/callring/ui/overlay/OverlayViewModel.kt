package com.greatwall.callring.ui.overlay

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OverlayViewModel(
    val request: IncomingCallUiRequest,
    private val api: CallringUiApi,
    testScope: CoroutineScope? = null
) : UiViewModel(testScope) {
    private val overviewLoader = RequestLoader<CallerOverview>(scope)
    val overview = overviewLoader.state
    private val settingsLoader = RequestLoader<SettingsSummary>(scope)
    val settings = settingsLoader.state
    private val runtimeLoader = RequestLoader<RuntimeStatus>(scope)
    val runtime = runtimeLoader.state
    private val mutableRuntimeRead = MutableStateFlow(0L)
    val runtimeRead = mutableRuntimeRead.asStateFlow()
    private val writer = MutationRunner(scope)
    val mutation = writer.state

    init {
        observeChanges(api.changes, setOf(DataDomain.CALLERS, DataDomain.POSITIONS, DataDomain.CALLS, DataDomain.REPORTS,
            DataDomain.WORK_CALENDAR, DataDomain.QUIET_HOURS, DataDomain.SCORE_SETTINGS, DataDomain.STATISTICS), request.number, ::refreshOverview)
        observeChanges(api.changes, setOf(DataDomain.APPEARANCE, DataDomain.OVERLAY_SETTINGS), refresh = ::refreshSettings)
        observeChanges(api.changes, setOf(DataDomain.RUNTIME), refresh = ::refreshRuntime)
    }

    fun refreshOverview() = overviewLoader.load { api.callers.getOverview(OverviewQuery(request.number, request.startedAtEpochMillis)) }
    fun refreshSettings() = settingsLoader.load { api.settings.getSummary() }
    fun refreshRuntime() = runtimeLoader.load(onSuccess = { mutableRuntimeRead.value++ }) { api.runtime.getStatus() }

    fun whitelist(enabled: Boolean) = writer.run({ api.callers.setWhitelisted(request.number, enabled) }) { refreshOverview() }
}
