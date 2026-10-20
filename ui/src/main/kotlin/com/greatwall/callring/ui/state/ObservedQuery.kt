package com.greatwall.callring.ui.state

import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.common.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** A subscribed query has one initial owner and one in-flight read across parameter changes. */
class ObservedQuery<T>(
    scope: CoroutineScope,
    changes: ChangeFeed,
    subscription: ChangeSubscription,
    private val parameter: () -> Any? = { Unit },
    load: suspend () -> ApiResult<T>,
    onSuccess: (T) -> Unit = {},
    onFailure: (ApiError) -> Unit = {},
) {
    private val mutable = MutableStateFlow(LoadState<T>())
    val state = mutable.asStateFlow()
    private val observation = ChangeObserver(changes, subscription)
    private val coordinator = RefreshCoordinator(scope, observation::token, {
        try { load() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "加载未完成，请重试", retryable = true)) }
    }, { result ->
        when (result) {
            is ApiResult.Success -> { mutable.value = LoadState(result.value, loaded = true); onSuccess(result.value) }
            is ApiResult.Failure -> { mutable.value = mutable.value.copy(loading = false, error = result.error); onFailure(result.error) }
        }
    }, { loading -> mutable.value = mutable.value.copy(loading = loading) })
    fun start(scope: CoroutineScope, invalidated: () -> Unit = {}): Job {
        var initial = true
        return observation.start(scope) {
            if (!initial) invalidated()
            initial = false
            coordinator.request(parameter())
        }
    }
    fun request(force: Boolean = false) = coordinator.request(parameter(), force)
}

fun numberSubscription(domains: Set<DataDomain>, number: String?) = ChangeSubscription(domains,
    if (number == null) emptyMap() else domains.filter { it in setOf(DataDomain.CALLERS, DataDomain.CALLS, DataDomain.REPORTS) }
        .associateWith { setOf(number) })
