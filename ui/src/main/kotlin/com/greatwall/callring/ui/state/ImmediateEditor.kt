package com.greatwall.callring.ui.state

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Snapshot editor for settings that apply field by field.
 *
 * Every accepted edit is committed against the last confirmed snapshot, never against the draft typed by the
 * user, so concurrent writers keep their own fields. Queued edits are coalesced: when a write is still running
 * only the latest value survives. A conflict reloads the confirmed snapshot but keeps the local value, and the
 * screen keeps rendering what the user chose. There is no whole-page save step and no draft restore.
 */
open class ImmediateEditor<T : Any>(
    changes: ChangeFeed,
    domains: Set<DataDomain>,
    private val load: suspend () -> ApiResult<T>,
    private val write: suspend (base: T, value: T) -> ApiResult<T>,
    testScope: CoroutineScope? = null,
    private val editDebounceMillis: Long = 0L,
    entities: Map<DataDomain, Set<String>> = emptyMap(),
) : UiViewModel(testScope) {
    private sealed interface Request<T : Any> {
        data class Edit<T : Any>(val value: T) : Request<T>
        data class Reset<T : Any>(val operation: suspend () -> ApiResult<T>) : Request<T>
    }

    private val mutableState = MutableStateFlow(LoadState<T>())
    val state = mutableState.asStateFlow()
    private val mutableDraft = MutableStateFlow<T?>(null)
    val draft = mutableDraft.asStateFlow()
    private val mutableMutation = MutableStateFlow(MutationState())
    val mutation = mutableMutation.asStateFlow()

    private var base: T? = null
    private val queue = ArrayDeque<Request<T>>()
    private var writing = false
    private var debounce: Job? = null
    private var refreshRequested = false
    private val observation = ChangeObserver(changes, ChangeSubscription(domains, entities))
    private val refreshCoordinator = RefreshCoordinator(scope, observation::token, { attempt { load() } }, { result ->
        when (result) {
            is ApiResult.Failure -> mutableState.value = mutableState.value.copy(loading = false, error = result.error)
            is ApiResult.Success -> {
                mutableState.value = LoadState(result.value, loaded = true)
                val previous = base
                base = result.value
                if (!writing && queue.isEmpty() && (mutableDraft.value == null || mutableDraft.value == previous)) mutableDraft.value = result.value
            }
        }
    }, { loading -> mutableState.value = mutableState.value.copy(loading = loading) })

    init { observation.start(scope, ::refresh) }

    fun refresh() {
        if (writing) { refreshRequested = true; return }
        refreshCoordinator.request()
    }

    /** A discrete change (switch, chip, picker) is committed as soon as the shared writer is free. */
    fun edit(value: T) = schedule(value, debounceMillis = editDebounceMillis)

    /** Continuous input (text, sliders) is coalesced and committed once the user pauses. */
    protected fun editCoalesced(value: T, debounceMillis: Long) = schedule(value, debounceMillis)

    /** Flushes or retries the latest draft against the last confirmed snapshot. */
    fun save() { draft.value?.let { schedule(it, debounceMillis = 0) } }

    protected fun submitReset(operation: suspend () -> ApiResult<T>) {
        queue.addLast(Request.Reset(operation))
        startWriter()
    }

    private fun schedule(value: T, debounceMillis: Long) {
        mutableDraft.value = value
        val last = queue.lastOrNull()
        if (last is Request.Edit) queue.removeLast()
        queue.addLast(Request.Edit(value))
        mutableMutation.value = mutation.value.copy(error = null)
        debounce?.cancel()
        if (debounceMillis <= 0L) startWriter() else debounce = scope.launch {
            delay(debounceMillis)
            startWriter()
        }
    }

    private fun startWriter() {
        if (writing) return
        writing = true
        mutableMutation.value = mutation.value.copy(busy = true, error = null, message = null)
        scope.launch {
            try {
                while (queue.isNotEmpty()) {
                    when (val request = queue.removeFirst()) {
                        is Request.Edit -> {
                            val snapshot = base ?: continue
                            when (val result = attempt { write(snapshot, request.value) }) {
                                is ApiResult.Success -> {
                                    base = result.value
                                    mutableMutation.value = mutation.value.copy(error = null)
                                    if (queue.isEmpty()) mutableDraft.value = result.value
                                }
                                is ApiResult.Failure -> reportFailure(result.error)
                            }
                        }
                        is Request.Reset -> when (val result = attempt(request.operation)) {
                            is ApiResult.Success -> {
                                base = result.value
                                mutableMutation.value = mutation.value.copy(error = null,
                                    completed = mutation.value.completed + 1, message = "设置已恢复")
                                if (queue.isEmpty()) mutableDraft.value = result.value
                            }
                            is ApiResult.Failure -> reportFailure(result.error)
                        }
                    }
                }
            } finally {
                writing = false
                mutableMutation.value = mutation.value.copy(busy = queue.isNotEmpty())
                if (queue.isEmpty() && refreshRequested) { refreshRequested = false; refresh() }
            }
        }
    }

    private suspend fun reportFailure(error: ApiError) {
        val message = if (error.code == ErrorCode.CONFLICT) "已更新，请重新修改。当前输入已保留。" else error.message
        mutableMutation.value = mutation.value.copy(error = error.copy(message = message))
        if (error.code != ErrorCode.CONFLICT) return
        // Keep the local value, but base the next commit on what the server now holds.
        when (val latest = attempt { load() }) {
            is ApiResult.Success -> {
                base = latest.value
                mutableState.value = LoadState(latest.value, loaded = true)
            }
            is ApiResult.Failure -> Unit
        }
    }

    private suspend fun <R> attempt(request: suspend () -> ApiResult<R>): ApiResult<R> = try {
        request()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "设置未完成，请重试", retryable = true))
    }
}
