package com.greatwall.callring.domain

import com.greatwall.callring.contracts.callers.CallResult

data class SystemCallRecord(
    val logId: String,
    val number: String,
    val startedAtEpochMillis: Long,
    val durationSeconds: Long?,
    val result: CallResult,
    val account: String? = null,
)
