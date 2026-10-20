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
    private val settingsDomains = setOf(DataDomain.APPEARANCE, DataDomain.OVERLAY_SETTINGS, DataDomain.STATISTICS,
        DataDomain.QUIET_HOURS, DataDomain.POSITIONS, DataDomain.WORK_CALENDAR, DataDomain.SCORE_SETTINGS)
    private val settingsObservation = ChangeObserver(api.changes, ChangeSubscription(settingsDomains, mapOf(DataDomain.WORK_CALENDAR to setOf("schedule"))))
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
    private val mutableRuntimeRead = MutableStateFlow(0L)
    val runtimeRead = mutableRuntimeRead.asStateFlow()
    private val writer = MutationRunner(scope)
    val mutation = writer.state

    private val overviewDomains = setOf(DataDomain.CALLERS, DataDomain.CALLS, DataDomain.REPORTS,
        DataDomain.WORK_CALENDAR, DataDomain.QUIET_HOURS, DataDomain.SCORE_SETTINGS, DataDomain.STATISTICS, DataDomain.RUNTIME)
    private val numbered = numberSubscription(overviewDomains, request.number)
    private val overviewLoader = ObservedQuery(scope, api.changes, ChangeSubscription(overviewDomains,
        numbered.entities + (DataDomain.RUNTIME to setOf("capability:CALL_LOG"))),
        load = { api.callers.getOverview(OverviewQuery(request.number, request.startedAtEpochMillis)) })
    val overview = overviewLoader.state
    private val runtimeLoader = ObservedQuery(scope, api.changes,
        ChangeSubscription(setOf(DataDomain.RUNTIME), mapOf(DataDomain.RUNTIME to setOf("status", "ringtonePlayback"))),
        load = { api.runtime.getStatus() }, onSuccess = { mutableRuntimeRead.value++ }, onFailure = { mutableRuntimeRead.value++ })
    val runtime = runtimeLoader.state
    init {
        overviewLoader.start(scope)
        settingsObservation.start(scope) { settingsRefresh.request() }
        runtimeLoader.start(scope)
    }

    fun refreshOverview() = overviewLoader.request(force = true)
    fun refreshSettings() = settingsRefresh.request(force = true)
    fun refreshRuntime() = runtimeLoader.request(force = true)
    fun whitelist(enabled: Boolean) = writer.run({ api.callers.setWhitelisted(request.number, enabled) })
}
