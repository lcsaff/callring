package com.greatwall.callring.application.coordination

import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.contracts.common.*
import java.io.IOException
import kotlinx.coroutines.*

/** Executes public use cases without owning business data or external capabilities. */
internal class UseCaseRunner(private val dispatcher: CoroutineDispatcher) {
    suspend fun <T> io(block: () -> T): ApiResult<T> = try {
        withContext(dispatcher) { ApiResult.Success(block()) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: BackendFailure) {
        ApiResult.Failure(failure.error)
    } catch (_: IOException) {
        ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "io.failure", retryable = true))
    } catch (_: Exception) {
        ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "storage.failure", retryable = true))
    }

    /** Once commit begins, publication survives cancellation. */
    suspend fun <T> write(block: () -> T): ApiResult<T> {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) { io(block) }
    }
}
