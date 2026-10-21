package com.greatwall.callring.application.mapping

import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.application.ports.BackendFailure

fun <T> ApiResult<T>.requiredValue(): T = when (this) {
    is ApiResult.Success -> value
    is ApiResult.Failure -> throw BackendFailure(error)
}
