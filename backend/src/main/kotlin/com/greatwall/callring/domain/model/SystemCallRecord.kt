package com.greatwall.callring.domain.model

import com.greatwall.callring.domain.model.*


enum class CallDirection { INCOMING, OUTGOING, UNKNOWN }

data class SystemCallRecord(
    val logId: String,
    val number: String,
    val startedAtEpochMillis: Long,
    val durationSeconds: Long?,
    val result: CallResult,
    val account: String? = null,
    val direction: CallDirection = CallDirection.INCOMING,
)
