package com.greatwall.callring.data.recording

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.coordination.ChangeImpact
import com.greatwall.callring.contracts.callers.CallArchiveAvailability
import com.greatwall.callring.contracts.callers.CallHistoryRecording
import com.greatwall.callring.contracts.change.DataDomain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal data class LegacyCallHistoryArchive(val id: Long, val generation: Long)

internal data class CallHistoryArchiveBatch(
    val recordings: Map<Long, CallHistoryRecording>,
    val missing: Map<Long, LegacyCallHistoryArchive>,
)

/** Only the background owner may consume historical payloads to populate the derived index. */
internal class CallHistoryProjectionRepair(
    private val advance: (LegacyCallHistoryArchive, () -> Boolean) -> Boolean?,
    private val coordinator: BusinessRevisionCoordinator,
    private val operationActive: () -> Boolean,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val pending = linkedSetOf<LegacyCallHistoryArchive>()
    private val finished = linkedMapOf<LegacyCallHistoryArchive, CallArchiveAvailability>()
    private var worker: Job? = null
    private var closed = false

    fun resolve(batch: CallHistoryArchiveBatch): Map<Long, CallHistoryRecording> = synchronized(lock) {
        if (closed || !operationActive()) throw CancellationException("recording.cancelled")
        if (batch.missing.isEmpty()) return@synchronized batch.recordings
        for (target in batch.missing.values) {
            if (pending.size >= 100) break
            if (target !in finished) pending += target
        }
        startWorker()
        batch.recordings.mapValues { (callId, row) ->
            if (finished[batch.missing[callId]] == CallArchiveAvailability.UNREADABLE) {
                row.copy(availability = CallArchiveAvailability.UNREADABLE)
            } else row
        }
    }

    // Called with lock held. A completed worker also covers submissions made as it exits.
    private fun startWorker() {
        if (closed || !operationActive() || pending.isEmpty() || worker != null) return
        val next = scope.launch(start = CoroutineStart.LAZY) { repair() }
        worker = next
        next.invokeOnCompletion { failure ->
            synchronized(lock) {
                worker = null
                if (failure == null) startWorker()
            }
        }
        next.start()
    }

    private suspend fun repair() {
        val context = currentCoroutineContext()
        val cancelled = { !context.isActive || !operationActive() }
        while (!cancelled()) {
            val target = synchronized(lock) { pending.firstOrNull() } ?: return
            var availability = CallArchiveAvailability.AVAILABLE
            val complete = try {
                advance(target, cancelled)
            } catch (failure: Exception) {
                if (cancelled()) throw CancellationException("recording.cancelled", failure)
                availability = CallArchiveAvailability.UNREADABLE
                true
            }
            synchronized(lock) {
                pending.remove(target)
                if (!closed) when (complete) {
                    false -> pending += target
                    true -> {
                        finished[target] = availability
                        if (finished.size > 256) finished.remove(finished.keys.first())
                    }
                    null -> Unit
                }
            }
            if (complete == true) coordinator.guard {
                if (!cancelled()) coordinator.publishResource(
                    "recording",
                    setOf("archiveProjection:${target.id}"),
                    ChangeImpact(mapOf(DataDomain.RECORDING_ARCHIVES to setOf(target.id.toString()))),
                )
            }
            yield()
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        pending.clear()
        scope.cancel()
    }
}
