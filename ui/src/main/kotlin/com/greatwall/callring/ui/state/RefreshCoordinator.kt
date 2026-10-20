package com.greatwall.callring.ui.state

import kotlinx.coroutines.*

/** One read in flight; only a result for the latest parameter and cumulative token may publish. */
class RefreshCoordinator<T>(private val scope: CoroutineScope, private val token: () -> Any?,
    private val load: suspend () -> T, private val apply: (T) -> Unit,
    private val loading: (Boolean) -> Unit = {}) {
    private var job: Job? = null
    private var pending = false
    private var parameter: Any? = Unit
    private var generation = 0L
    private var runId = 0L
    private var readingToken: Any? = null
    fun request(parameter: Any? = Unit, force: Boolean = false) {
        val changedParameter = this.parameter != parameter
        if (changedParameter || force) generation++
        this.parameter = parameter
        if (job?.isActive == true) {
            if (changedParameter || force || token() != readingToken) pending = true
            return
        }
        pending = true
        val currentRun = ++runId
        job = scope.launch {
            loading(true)
            try {
                while (pending) {
                    pending = false
                    val ticket = generation
                    readingToken = token()
                    val result = load()
                    currentCoroutineContext().ensureActive()
                    if (ticket != generation || readingToken != token()) pending = true
                    else if (!pending) apply(result)
                }
            } finally {
                if (currentRun == runId) {
                    loading(false)
                    if (pending && scope.isActive) { job = null; request(parameter) }
                }
            }
        }
    }
    fun cancel() { generation++; runId++; pending = false; job?.cancel(); job = null }
}
