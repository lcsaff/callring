package com.greatwall.callring.ui.state

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import kotlinx.coroutines.CoroutineScope

open class SnapshotViewModel<T>(
    changes: ChangeFeed,
    domains: Set<DataDomain>,
    private val load: suspend () -> ApiResult<T>,
    testScope: CoroutineScope? = null
) : UiViewModel(testScope) {
    private val loader = RequestLoader<T>(scope)
    val state = loader.state
    private val writer = MutationRunner(scope)
    val mutation = writer.state

    init { observeChanges(changes, domains, refresh = ::refresh) }

    fun refresh() = loader.load { load() }

    protected fun <R> write(operation: suspend () -> ApiResult<R>, successMessage: String = "设置已更新", onSuccess: (R) -> Unit = { refresh() }) =
        writer.run(operation, successMessage, onSuccess)

    // Useful for composed presentation models whose operations are injected alongside their query.
    fun <R> execute(operation: suspend () -> ApiResult<R>) = write(operation)
}
