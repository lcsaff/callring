package com.greatwall.callring.ui.feature.update

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
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
    private var pollJob: Job? = null

    fun refresh() {
        if (writer.state.value.busy) return
        loadJob?.cancel()
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        loadJob = scope.launch {
            when (val result = attempt { api.status() }) {
                is ApiResult.Success -> {
                    mutableState.value = LoadState(result.value, loaded = true)
                    startPolling()
                }
                is ApiResult.Failure -> mutableState.value = mutableState.value.copy(loading = false, error = result.error)
            }
        }
    }

    fun check() = operate { api.check() }
    fun download() = operate { api.download() }
    fun cancel() = operate { api.cancel() }
    fun install() = operate { api.install() }

    private fun operate(request: suspend () -> ApiResult<UpdateStatus>): Unit {
        pollJob?.cancel()
        pollJob = null
        writer.run(request, "更新状态已刷新") { latest ->
            mutableState.value = LoadState(latest, loaded = true)
            startPolling()
        }
    }

    /** 下载/校验由平台后台执行，页面打开期间按固定间隔读取进度，进入终态后停止。 */
    private fun startPolling() {
        if (!needsPolling(mutableState.value.data)) return
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                when (val result = attempt { api.status() }) {
                    is ApiResult.Success -> {
                        mutableState.value = LoadState(result.value, loaded = true)
                        if (!needsPolling(result.value)) break
                    }
                    is ApiResult.Failure -> break
                }
            }
        }
    }

    private fun needsPolling(status: UpdateStatus?): Boolean = status != null && when (status.phase) {
        UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING -> true
        else -> false
    }

    private suspend fun <T> attempt(request: suspend () -> ApiResult<T>): ApiResult<T> = try {
        request()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "update.install_failed", retryable = true))
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}

private const val POLL_INTERVAL_MS = 500L
