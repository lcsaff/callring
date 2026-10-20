package com.greatwall.callring.application.coordination

import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.contracts.common.*

/** Public operations recheck their owner inside the final commit fence; drain channels are separate. */
internal fun requireOperationActive(active: () -> Boolean) {
    if (!active()) throw BackendFailure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "backend.closed"))
}
