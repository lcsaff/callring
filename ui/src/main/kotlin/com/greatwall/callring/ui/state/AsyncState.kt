package com.greatwall.callring.ui.state

import com.greatwall.callring.application.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class LoadState<T>(
    val data: T? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: ApiError? = null
)

/** Cancellation is an optimization; the ticket is what rejects obsolete responses. */
class RequestLoader<T>(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(LoadState<T>())
    val state: StateFlow<LoadState<T>> = mutable.asStateFlow()
    private var sequence = 0L
    private var job: Job? = null

    fun load(
        preserveData: Boolean = true,
        onSuccess: (T) -> Unit = {},
        request: suspend () -> ApiResult<T>
    ) {
        val ticket = ++sequence
        job?.cancel()
        mutable.value = (if (preserveData) mutable.value else LoadState()).copy(loading = true, error = null)
        job = scope.launch {
            try {
                val result = request()
                if (ticket != sequence || !isActive) return@launch
                when (result) {
                    is ApiResult.Success -> {
                        mutable.value = LoadState(result.value, loaded = true)
                        onSuccess(result.value)
                    }
                    is ApiResult.Failure -> mutable.value = mutable.value.copy(loading = false, error = result.error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (ticket == sequence) mutable.value = mutable.value.copy(loading = false, error = unexpectedError())
            } finally {
                if (ticket == sequence) mutable.value = mutable.value.copy(loading = false)
            }
        }
    }
}

data class MutationState(
    val busy: Boolean = false,
    val error: ApiError? = null,
    val completed: Long = 0,
    val message: String? = null
)

/** Never retries a write: cancelling its caller does not imply the transaction rolled back. */
class MutationRunner(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(MutationState())
    val state: StateFlow<MutationState> = mutable.asStateFlow()

    fun <T> run(request: suspend () -> ApiResult<T>, successMessage: String = "操作已完成", onSuccess: (T) -> Unit = {}) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null, message = null)
        scope.launch {
            try {
                when (val result = request()) {
                    is ApiResult.Success -> {
                        mutable.value = mutable.value.copy(completed = mutable.value.completed + 1, message = successMessage)
                        onSuccess(result.value)
                    }
                    is ApiResult.Failure -> mutable.value = mutable.value.copy(error = result.error)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = unexpectedError())
            } finally {
                mutable.value = mutable.value.copy(busy = false)
            }
        }
    }
}

private fun unexpectedError() = ApiError(ErrorCode.INTERNAL, "操作未完成，请刷新后重试")

private val numberedDomains = setOf(DataDomain.CALLERS, DataDomain.CALLS, DataDomain.REPORTS)

fun DataChange.affects(domains: Set<DataDomain>, number: String? = null, widgetId: Int? = null): Boolean =
    this.domains.intersect(domains).any { domain ->
        when {
            domain in numberedDomains && number != null -> numbers?.contains(number) != false
            domain == DataDomain.WIDGET_BINDINGS && widgetId != null -> widgetIds?.contains(widgetId) != false
            else -> true
        }
    }
