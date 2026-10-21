package com.greatwall.callring.application.conversation.query

import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.contracts.common.ApiError
import com.greatwall.callring.contracts.common.ErrorCode
import kotlinx.coroutines.*

suspend fun <T> withSnapshotSource(ids: String, block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackendFailure) {
        throw BackendFailure(
            e.error.copy(fieldErrors = e.error.fieldErrors + ("domains" to ids)),
        )
    } catch (_: Exception) {
        throw BackendFailure(
            ApiError(
                ErrorCode.IO_FAILURE,
                "ai.snapshot_domain_failed",
                fieldErrors = mapOf("domains" to ids),
                retryable = true,
            ),
        )
    }
