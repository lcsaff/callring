package com.greatwall.callring.application.callers

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.UseCaseRunner
import com.greatwall.callring.application.mapping.frozenList
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.application.ports.store.CallHistoryBoundary
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
    private val dependencies = ChangeSubscription(setOf(DataDomain.CALLS, DataDomain.RECORDING_ARCHIVES))

    suspend fun page(query: CallHistoryQuery) = runner.io {
        callerRequire(query.pageSize in 1..100, "pageSize", "page.size.invalid")
        val cursor = query.cursor
        consistent { snapshot, cancelled ->
            val token = snapshot.token(dependencies)
            if (cursor != null && (cursor.token != token || cursor.pageSize != query.pageSize)) {
                pageChanged()
            }
            val boundary = cursor?.let { CallHistoryBoundary(it.startedAtEpochMillis, it.callId) }
            val rows = calls.readCallHistory { it.visibleCallHistory(boundary, query.pageSize + 1) }
            val entries = project(rows.take(query.pageSize), cancelled)
            val next = if (rows.size > query.pageSize) entries.last().call.let {
                CallHistoryCursor(token, it.startedAtEpochMillis, it.id, query.pageSize)
            } else null
            CallHistoryPage(entries, next, SnapshotMeta(snapshot.revision, clock.millis()))
        }
    }

    suspend fun detail(callId: Long) = runner.io {
        consistent { _, cancelled ->
            val row = calls.readCallHistory { it.visibleCallHistoryDetail(callId) }
            row?.let { project(listOf(it), cancelled).single() }
        } ?: callerFailure(ErrorCode.NOT_FOUND, "call.not_found")
    }

    private suspend fun <T> consistent(block: (InvalidationSnapshot, () -> Boolean) -> T): T {
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

    private fun project(rows: List<ReportCallFact>, cancelled: () -> Boolean): List<CallHistoryEntry> {
        if (rows.isEmpty()) return emptyList()
        if (cancelled()) throw CancellationException("call.history_cancelled")
        val targets = rows.map { (row, _) ->
            CallHistoryRecordingTarget(row.id, stableRecordingCallId(row.id, row.linkedScreenId))
        }
        val byCall = recordings.readCallHistory(targets, cancelled)
        return frozenList(rows.map { (row, screening) ->
            CallHistoryEntry(
                callDetail(row, screening),
                byCall[row.id] ?: CallHistoryRecording(null, CallArchiveAvailability.NONE, false, false),
                row.linkedScreenId,
            )
        })
    }

    private fun pageChanged(): Nothing =
        throw BackendFailure(ApiError(ErrorCode.CONFLICT, "call.history_changed", retryable = true))
}
