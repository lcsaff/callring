package com.greatwall.callring.application.ports

import com.greatwall.callring.contracts.common.ApiError

/** Adapters may throw this to preserve a structured platform failure. */
class BackendFailure(val error: ApiError) : RuntimeException(error.message)
