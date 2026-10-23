package com.greatwall.callring.ui.app

import com.greatwall.callring.contracts.callers.NumberCheck
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.NumberRuleSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class NumberRuleStartupState(
    val pending: UiDestination = UiDestination.Home,
    val destination: UiDestination? = null,
    val loading: Boolean = true,
    val error: ApiError? = null,
)

/** A raw navigation request cannot invoke synchronous checks until its settings load succeeds. */
internal class NumberRuleStartupGate(
    private val scope: CoroutineScope,
    private val prepare: suspend () -> ApiResult<NumberRuleSettings>,
    private val check: (String) -> NumberCheck,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(NumberRuleStartupState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var closed = false
    private var job: Job? = null

    fun open(raw: UiDestination) {
        if (closed) return
        val ticket = ++generation
        job?.cancel()
        mutableState.value = NumberRuleStartupState(pending = raw)
        job = scope.launch {
            try {
                val result = prepare()
                currentCoroutineContext().ensureActive()
                if (closed || ticket != generation) return@launch
                mutableState.value = when (result) {
                    is ApiResult.Failure -> NumberRuleStartupState(raw, loading = false, error = result.error)
                    is ApiResult.Success -> NumberRuleStartupState(raw, normalize(raw), loading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!closed && ticket == generation)
                    mutableState.value = NumberRuleStartupState(raw, loading = false,
                        error = ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "number_rule.unavailable", retryable = true))
            }
        }
    }

    private fun normalize(raw: UiDestination): UiDestination = when (raw) {
        is UiDestination.Report -> check(raw.number).let { if (it.validProfileNumber) UiDestination.Report(it.normalized) else UiDestination.Lookup }
        is UiDestination.EditCaller -> raw.number?.let { number ->
            check(number).let { if (it.validProfileNumber) UiDestination.EditCaller(it.normalized) else UiDestination.EditCaller() }
        } ?: raw
        else -> raw
    }
    fun retry() = open(state.value.pending)
    override fun close() { closed = true; generation++; job?.cancel() }
}
