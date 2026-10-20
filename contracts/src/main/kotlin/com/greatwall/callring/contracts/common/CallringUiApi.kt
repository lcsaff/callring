package com.greatwall.callring.contracts.common

import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.ai.*
import java.time.LocalDate

sealed class ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>()
    data class Failure(val error: ApiError) : ApiResult<Nothing>()
}

data class ApiError(
    val code: ErrorCode,
    val message: String,
    val fieldErrors: Map<String, String> = emptyMap(),
    val requiredCapabilities: Set<Capability> = emptySet(),
    val retryable: Boolean = false,
    val aiFailure: ConversationFailure? = null,
)

enum class ErrorCode {
    VALIDATION, NOT_FOUND, IN_USE, PERMISSION_REQUIRED, ROLE_REQUIRED, CONFLICT,
    SYSTEM_RESTRICTED, SYSTEM_UNAVAILABLE, IO_FAILURE, STORAGE_FAILURE, INTERNAL
}

data class PageRequest(val pageIndex: Int = 0, val pageSize: Int = 50)

data class Page<T>(
    val items: List<T>,
    val pageIndex: Int,
    val pageSize: Int,
    val totalItems: Long,
    val meta: SnapshotMeta
)

data class SnapshotMeta(
    val dataRevision: Long,
    val loadedAtEpochMillis: Long
)

data class DateRange(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
    val zoneId: String? = null
)

data class TimeRange(
    val startInclusiveEpochMillis: Long,
    val endExclusiveEpochMillis: Long,
    val zoneId: String
)

data class DocumentRef(val uri: String)
