package com.greatwall.callring.application.storage

import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.application.ports.storage.NumberProfileResetPort
import com.greatwall.callring.contracts.common.*
import kotlinx.coroutines.*

class NumberProfileResetService(private val port: NumberProfileResetPort,
    private val dispatcher: CoroutineDispatcher, private val operationActive: () -> Boolean) {
    private suspend fun <T> run(block: (() -> Unit) -> T): ApiResult<T> = withContext(dispatcher) {
        val caller = currentCoroutineContext()
        val check = { caller.ensureActive(); if (!operationActive()) throw CancellationException("storage.closed") }
        try { check(); ApiResult.Success(block(check)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: BackendFailure) { ApiResult.Failure(failure.error) }
        catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "storage.reset_failed", retryable = true)) }
        // Successful replacement deliberately closes this owner; no post-commit admission check.
    }
    suspend fun preview() = run { check ->
        val preview=port.preview(check)
        try {check();preview} catch(failure:Throwable) {port.discard(preview.token);throw failure}
    }
    suspend fun confirm(token: String, keepRecordings: Boolean) = run { port.confirm(token, keepRecordings, it) }
    suspend fun discard(token: String) = run { check -> check(); port.discard(token) }
}
