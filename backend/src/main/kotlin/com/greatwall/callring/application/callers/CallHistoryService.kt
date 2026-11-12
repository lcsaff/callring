package com.greatwall.callring.application.callers

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.UseCaseRunner
import com.greatwall.callring.application.mapping.frozenList
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.application.ports.store.CallHistoryBoundary
import com.greatwall.callring.application.ports.store.CallHistoryCaller
import com.greatwall.callring.application.ports.store.CallHistoryReads
import com.greatwall.callring.application.ports.store.ReportCallFact
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.change.ChangeSubscription
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.change.InvalidationSnapshot
import com.greatwall.callring.contracts.common.ApiError
import com.greatwall.callring.contracts.common.ErrorCode
import com.greatwall.callring.contracts.common.SnapshotMeta
import com.greatwall.callring.domain.calling.stableRecordingCallId
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

data class CallHistoryRecordingTarget(val callId: Long, val stableRecordingId: Long)

fun interface CallHistoryRecordingReads {
    fun readCallHistory(
        targets: List<CallHistoryRecordingTarget>,
        cancelled: () -> Boolean,
    ): Map<Long, CallHistoryRecording>
}

internal class CallHistoryService(
    private val calls: CallHistoryReads,
    private val recordings: CallHistoryRecordingReads,
    private val coordinator: BusinessRevisionCoordinator,
    dispatcher: CoroutineDispatcher,
    private val clock: Clock,
    private val operationActive: () -> Boolean,
) {
    private val runner = UseCaseRunner(dispatcher)
    // PositionService publishes the affected caller numbers when a configured title changes.
    private val domains = setOf(DataDomain.CALLS, DataDomain.RECORDING_ARCHIVES, DataDomain.CALLERS)
    private val globalDependencies = ChangeSubscription(domains)

    private fun dependencies(number: String?) = if (number == null) globalDependencies else ChangeSubscription(
        domains,
        mapOf(DataDomain.CALLS to setOf(number), DataDomain.CALLERS to setOf(number)),
    )

    suspend fun page(query: CallHistoryQuery) = runner.io {
        callerRequire(query.pageSize in 1..100, "pageSize", "page.size.invalid")
        val cursor = query.cursor
        val dependencies = dependencies(query.number)
        consistent(dependencies) { snapshot, cancelled ->
            val token = snapshot.token(dependencies)
            if (cursor != null && (cursor.number != query.number || cursor.pageSize != query.pageSize || cursor.token != token)) {
                pageChanged()
            }
            val boundary = cursor?.let { CallHistoryBoundary(it.startedAtEpochMillis, it.callId) }
            val (rows, callers) = calls.readCallHistory { reader ->
                val page = reader.visibleCallHistory(query.number, boundary, query.pageSize + 1)
                page to reader.callHistoryCallers(page.take(query.pageSize).mapTo(linkedSetOf()) { it.current.number })
            }
            val entries = project(rows.take(query.pageSize), callers, cancelled)
            val next = if (rows.size > query.pageSize) entries.last().call.let {
                CallHistoryCursor(token, it.startedAtEpochMillis, it.id, query.pageSize, query.number)
            } else null
            CallHistoryPage(entries, next, SnapshotMeta(snapshot.revision, clock.millis()))
        }
    }

    suspend fun detail(callId: Long) = runner.io {
        consistent(globalDependencies) { _, cancelled ->
            val (row, callers) = calls.readCallHistory { reader ->
                val detail = reader.visibleCallHistoryDetail(callId)
                detail to if (detail == null) emptyMap() else reader.callHistoryCallers(setOf(detail.current.number))
            }
            row?.let { project(listOf(it), callers, cancelled).single() }
        } ?: callerFailure(ErrorCode.NOT_FOUND, "call.not_found")
    }

    private suspend fun <T> consistent(
        dependencies: ChangeSubscription,
        block: (InvalidationSnapshot, () -> Boolean) -> T,
    ): T {
        val context = currentCoroutineContext()
        val cancelled = { !context.isActive || !operationActive() }
        repeat(3) {
            if (cancelled()) throw CancellationException("call.history_cancelled")
            val before = snapshot()
            val value = block(before, cancelled)
            if (cancelled()) throw CancellationException("call.history_cancelled")
            if (before.token(dependencies) == snapshot().token(dependencies)) return value
        }
        pageChanged()
    }

    // Compare publications outside either database lease. Mutation holds the fence through commit and publication.
    private fun snapshot() = coordinator.guard { coordinator.invalidations.snapshots.value }

    private fun project(
        rows: List<ReportCallFact>,
        callers: Map<String, CallHistoryCaller>,
        cancelled: () -> Boolean,
    ): List<CallHistoryEntry> {
        if (rows.isEmpty()) return emptyList()
        if (cancelled()) throw CancellationException("call.history_cancelled")
        val targets = rows.map { (row, _) ->
            CallHistoryRecordingTarget(row.id, stableRecordingCallId(row.id, row.linkedScreenId))
        }
        val byCall = recordings.readCallHistory(targets, cancelled)
        return frozenList(rows.map { (row, screening) ->
            val caller = callers[row.number]
            CallHistoryEntry(
                callDetail(row, screening),
                byCall[row.id] ?: CallHistoryRecording(null, CallArchiveAvailability.NONE, false, false),
                row.linkedScreenId,
                callerName = caller?.name?.takeIf(String::isNotBlank),
                callerRegion = caller?.region?.takeIf(String::isNotBlank),
                callerSelfReportedPosition = caller?.selfReportedPosition?.takeIf(String::isNotBlank),
                callerConfiguredPosition = caller?.configuredPosition?.takeIf(String::isNotBlank),
            )
        })
    }

    private fun pageChanged(): Nothing =
        throw BackendFailure(ApiError(ErrorCode.CONFLICT, "call.history_changed", retryable = true))
}
