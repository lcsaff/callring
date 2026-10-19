package com.greatwall.callring.ui.feature.update

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Drives the update state machine. The UI never reaches the network or the installer directly. */
class AppUpdateViewModel(
    private val api: AppUpdateApi,
    testScope: CoroutineScope? = null,
) : UiViewModel(testScope) {
    private val mutableState = MutableStateFlow(LoadState<UpdateStatus>())
    val state = mutableState.asStateFlow()
    private val writer = MutationRunner(scope)
    val mutation = writer.state
    private var loadJob: Job? = null

    fun refresh() {
        if (writer.state.value.busy) return
        loadJob?.cancel()
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        loadJob = scope.launch {
            when (val result = attempt { api.status() }) {
                is ApiResult.Success -> mutableState.value = LoadState(result.value, loaded = true)
                is ApiResult.Failure -> mutableState.value = mutableState.value.copy(loading = false, error = result.error)
            }
        }
    }

    fun check() = operate { api.check() }
    fun download() = operate { api.download() }
    fun cancel() = operate { api.cancel() }
    fun install() = operate { api.install() }

    private fun operate(request: suspend () -> ApiResult<UpdateStatus>) =
        writer.run(request, "更新状态已刷新") { latest -> mutableState.value = LoadState(latest, loaded = true) }

    private suspend fun <T> attempt(request: suspend () -> ApiResult<T>): ApiResult<T> = try {
        request()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "update.install_failed", retryable = true))
    }
}
