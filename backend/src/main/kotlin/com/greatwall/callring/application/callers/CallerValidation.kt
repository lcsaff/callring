package com.greatwall.callring.application.callers

import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.domain.model.NumberIdentity

internal fun callerFailure(code: ErrorCode, message: String, field: String? = null): Nothing =
    throw BackendFailure(
        ApiError(code, message, if (field == null) emptyMap() else mapOf(field to message)),
    )

internal fun callerRequire(ok: Boolean, field: String, message: String) {
    if (!ok) callerFailure(ErrorCode.VALIDATION, message, field)
}

internal fun callerNumber(raw: String): String =
    NumberIdentity.parse(raw).let {
        callerRequire(it.validProfileNumber, "number", "number.invalid")
        it.normalized
    }
