package com.greatwall.callring.ui.overlay

import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OverlayViewModel(
    val request: IncomingCallUiRequest,
    private val api: CallringUiApi,
    testScope: CoroutineScope? = null
) : UiViewModel(testScope) {
    private val overviewLoader = RequestLoader<CallerOverview>(scope)
    val overview = overviewLoader.state
    private val settingsDomains = setOf(DataDomain.APPEARANCE, DataDomain.OVERLAY_SETTINGS, DataDomain.STATISTICS,
        DataDomain.QUIET_HOURS, DataDomain.POSITIONS, DataDomain.WORK_CALENDAR, DataDomain.SCORE_SETTINGS)
    private val settingsObservation = ChangeObserver(api.changes, ChangeSubscription(settingsDomains, mapOf(DataDomain.WORK_CALENDAR to "schedule")))
    private val mutableSettings = MutableStateFlow(LoadState<SettingsSummary>())
    val settings = mutableSettings.asStateFlow()
    private val settingsRefresh = RefreshCoordinator(scope, settingsObservation::token, {
        try { api.settings.getSummary() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "设置未完成，请重试", retryable = true)) }
    }, { result ->
        mutableSettings.value = when (result) {
            is ApiResult.Success -> LoadState(result.value, loaded = true)
            is ApiResult.Failure -> mutableSettings.value.copy(loading = false, error = result.error)
        }
    }, { loading -> mutableSettings.value = mutableSettings.value.copy(loading = loading) })
    private val runtimeLoader = RequestLoader<RuntimeStatus>(scope)
    val runtime = runtimeLoader.state
    private val mutableRuntimeRead = MutableStateFlow(0L)
    val runtimeRead = mutableRuntimeRead.asStateFlow()
    private val writer = MutationRunner(scope)
    val mutation = writer.state

    init {
        observeChanges(api.changes, setOf(DataDomain.CALLERS, DataDomain.POSITIONS, DataDomain.CALLS, DataDomain.REPORTS,
            DataDomain.WORK_CALENDAR, DataDomain.QUIET_HOURS, DataDomain.SCORE_SETTINGS, DataDomain.STATISTICS), request.number, ::refreshOverview)
        settingsObservation.start(scope) { settingsRefresh.request(force = settingsObservation.token() == null) }
        observeChanges(api.changes, setOf(DataDomain.RUNTIME), refresh = ::refreshRuntime)
    }

    fun refreshOverview() = overviewLoader.load { api.callers.getOverview(OverviewQuery(request.number, request.startedAtEpochMillis)) }
    fun refreshSettings() = settingsRefresh.request(force = true)
    fun refreshRuntime() = runtimeLoader.load(onSuccess = { mutableRuntimeRead.value++ }) { api.runtime.getStatus() }

    fun whitelist(enabled: Boolean) = writer.run({ api.callers.setWhitelisted(request.number, enabled) }) { refreshOverview() }
}
