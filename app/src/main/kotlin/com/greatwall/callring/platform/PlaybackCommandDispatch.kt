package com.greatwall.callring.platform

import com.greatwall.callring.contracts.common.ApiError
import com.greatwall.callring.contracts.common.ErrorCode
import com.greatwall.callring.application.ports.BackendFailure
import java.util.concurrent.CancellationException

/** Only the platform delivery attempt belongs here; exception messages may contain private data. */
internal fun <T : Any> dispatchPlaybackCommand(command: () -> T?): T =
    try {
        command()
            ?: throw BackendFailure(
                ApiError(
                    ErrorCode.SYSTEM_UNAVAILABLE,
                    "audio.service_unavailable",
                    retryable = true,
                )
            )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: BackendFailure) {
        throw failure
    } catch (_: SecurityException) {
        throw BackendFailure(
            ApiError(ErrorCode.PERMISSION_REQUIRED, "audio.service_permission_required")
        )
    } catch (_: IllegalStateException) {
        throw BackendFailure(
            ApiError(
                ErrorCode.SYSTEM_RESTRICTED,
                "audio.service_start_restricted",
                retryable = true,
            )
        )
    } catch (_: RuntimeException) {
        throw BackendFailure(
            ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "audio.service_unavailable", retryable = true)
        )
    }
