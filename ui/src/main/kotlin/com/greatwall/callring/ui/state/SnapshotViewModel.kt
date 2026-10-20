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
    private val refreshFromChanges: Boolean = false,
    entities: Map<DataDomain, String> = emptyMap()
) : UiViewModel(testScope) {
    private val loader = RequestLoader<T>(scope)
    private val mutableState = MutableStateFlow(LoadState<T>())
    val state = if (refreshFromChanges) mutableState.asStateFlow() else loader.state
    private val observation = ChangeObserver(changes, ChangeSubscription(domains, entities))
    private val refreshCoordinator = RefreshCoordinator(scope, observation::token, {
        try { load() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "设置未完成，请重试", retryable = true)) }
    }, { result ->
        mutableState.value = when (result) {
            is ApiResult.Success -> LoadState(result.value, loaded = true)
            is ApiResult.Failure -> mutableState.value.copy(loading = false, error = result.error)
        }
    }, { loading -> mutableState.value = mutableState.value.copy(loading = loading) })
    private val writer = MutationRunner(scope)
    val mutation = writer.state

    init {
        if (refreshFromChanges) observation.start(scope) { refreshCoordinator.request(force = observation.token() == null) }
        else observeChanges(changes, domains, refresh = ::refresh)
    }

    fun refresh() {
        if (refreshFromChanges) refreshCoordinator.request(force = true)
        else loader.load { load() }
    }

    protected fun <R> write(operation: suspend () -> ApiResult<R>, successMessage: String = "设置已更新", onSuccess: (R) -> Unit = { if (!refreshFromChanges) refresh() }) =
        writer.run(operation, successMessage, onSuccess)

    // Useful for composed presentation models whose operations are injected alongside their query.
    fun <R> execute(operation: suspend () -> ApiResult<R>) = write(operation)
}
