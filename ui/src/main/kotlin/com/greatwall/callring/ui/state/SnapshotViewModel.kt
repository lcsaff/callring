package com.greatwall.callring.ui.state

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

open class SnapshotViewModel<T>(
    changes: ChangeFeed,
    domains: Set<DataDomain>,
    private val load: suspend () -> ApiResult<T>,
    testScope: CoroutineScope? = null,
    entities: Map<DataDomain, Set<String>> = emptyMap(),
    clock: java.time.Clock = SystemLocalClock
) : UiViewModel(testScope) {
    internal val timeRefresh = TimeRefreshSchedule(clock)
    private var readStartedAt = timeRefresh.captureRead()
    private val mutableState = MutableStateFlow(LoadState<T>())
    val state = mutableState.asStateFlow()
    private val observation = ChangeObserver(changes, ChangeSubscription(domains, entities))
    private val refreshCoordinator = RefreshCoordinator(scope, observation::token, {
        readStartedAt = timeRefresh.captureRead()
        try { load() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "设置未完成，请重试", retryable = true)) }
    }, { result ->
        mutableState.value = when (result) {
            is ApiResult.Success -> { timeRefresh.readSucceeded(readStartedAt); LoadState(result.value, loaded = true) }
            is ApiResult.Failure -> mutableState.value.copy(loading = false, error = result.error)
        }
    }, { loading -> mutableState.value = mutableState.value.copy(loading = loading) })
    private val writer = MutationRunner(scope)
    val mutation = writer.state

    init { observation.start(scope) { refreshCoordinator.request() } }

    fun refresh() = refreshCoordinator.request(force = true)

    protected fun <R> write(operation: suspend () -> ApiResult<R>, successMessage: String = "设置已更新", onSuccess: (R) -> Unit = {}) =
        writer.run(operation, successMessage, onSuccess)

    // Useful for composed presentation models whose operations are injected alongside their query.
    fun <R> execute(operation: suspend () -> ApiResult<R>) = write(operation)
}
