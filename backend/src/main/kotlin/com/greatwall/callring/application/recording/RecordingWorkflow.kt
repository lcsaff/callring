package com.greatwall.callring.application.recording

import com.greatwall.callring.application.ports.recording.AiClient
import com.greatwall.callring.application.ports.recording.AiFailure
import com.greatwall.callring.application.ports.recording.AudioReference
import com.greatwall.callring.application.ports.recording.RecordingCandidate
import com.greatwall.callring.application.ports.recording.RecordingWorkflowReads

import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.AiProtocol
import com.greatwall.callring.application.recording.model.RecordingActivityObservation
import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.contracts.command.CommandContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** Durable state transitions; Android supplies files, networking and scheduling. */
class RecordingWorkflow(
    private val store: RecordingWorkflowReads,
    private val commands: RecordingArchiveCommands,
    private val client: AiClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val running = ConcurrentHashMap.newKeySet<Long>()
    private val activityFence = Object()
    @Volatile private var closed = false
    private val cancelledOperations = ConcurrentHashMap<Long, AtomicBoolean>()
    private val activeClients = ConcurrentHashMap<Long, AiClient>()
    private val versions = AtomicLong(System.currentTimeMillis() * 1000)
    fun isRunning(callId: Long): Boolean = callId in running
    /** Cleanup holds this only for local deletion. Candidate admission never retains it for IO. */
    fun <T> withIdle(callId: Long, block: () -> T): T = synchronized(activityFence) {
        check(!closed) { "recording.closed" }
        check(callId !in running) { "recording.busy" }
        block()
    }
    fun activityObservation() = RecordingActivityObservation(running.size.toLong(), activeClients.size.toLong(), cancelledOperations.values.count{it.get()}.toLong())
    private fun version(previous: Long = 0): Long = versions.updateAndGet { maxOf(it + 1, previous + 1) }

    fun attach(callId: Long, number: String, startedAt: Long, path: String, source: RecordingSource, duration: Long, command: CommandContext?, playbackStopped: Boolean): CallArchive {
        check(!closed) { "recording.closed" }
        require(callId != 0L && number.isNotBlank() && duration > 0) { "recording.invalid_attachment" }
        val previousGeneration = store.generation(callId)
        cancelledOperations[callId]?.set(true)
        activeClients[callId]?.cancel()
        val settings = store.settings()
        val value = CallArchive(callId, number, startedAt, version(previousGeneration ?: 0),
            if (settings.automaticAnalysis) ArchiveStatus.QUEUED else ArchiveStatus.READY,
            source, path, if (source == RecordingSource.MICROPHONE) "recording.microphone_unverified" else "recording.unverified",
            duration)
        check(commands.compareAndSet(callId, previousGeneration, value, command, playbackStopped)) { "recording.changed" }
        return value
    }

    fun retry(callId: Long, command: CommandContext?) {
        check(!closed) { "recording.closed" }
        check(callId !in running) { "recording.busy" }
        val value = requireNotNull(store.get(callId)) { "recording.not_found" }
        require(value.audioPath != null) { "recording.missing_audio" }
        val config = store.settings()
        config.analysis.validate(); config.transcription.validate()
        require(config.transcription.protocol == AiProtocol.OPENAI) { "ai.transcription_requires_openai" }
        check(commands.compareAndSet(callId, value.generation, value.copy(generation = version(value.generation),
            status = ArchiveStatus.QUEUED, attempts = 0, nextAttemptAt = 0, error = null, manualAnalysis = true), command, false)) { "recording.changed" }
    }

    fun delete(callId: Long, command: CommandContext?, playbackStopped: Boolean): Boolean {
        check(!closed) { "recording.closed" }
        cancelledOperations[callId]?.set(true)
        activeClients[callId]?.cancel()
        return commands.delete(callId, command, playbackStopped)
    }
    fun cancelProcessing() {
        cancelledOperations.values.forEach { it.set(true) }
        activeClients.values.forEach { it.cancel() }
    }

    /** Atomic admission seal followed by cancellation and drain; no resource/business lock is held. */
    fun seal() { synchronized(activityFence) { closed = true } }
    fun close() {
        seal()
        cancelProcessing()
        var interrupted = false
        synchronized(activityFence) {
            while (running.isNotEmpty()) try { activityFence.wait() } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    fun nextRunAt(): Long? { check(!closed) { "recording.closed" }; return store.earliestPendingAt { closed } }

    /** Candidate pages are consumed before network work; no storage cursor spans an AI request. */
    fun processPending(cancelled: () -> Boolean = { false }) {
        val cutoff = now()
        var after: Long? = null
        while (!cancelled() && !closed) {
            val candidates = store.dueCandidates(cutoff, after, 64) { cancelled() || closed }
            if (candidates.isEmpty()) return
            for (item in candidates) {
                if (cancelled() || closed) return
                after = item.callId
                if (synchronized(activityFence) { !closed && running.add(item.callId) }) try { process(item, cancelled) } finally {
                    cancelledOperations.remove(item.callId); activeClients.remove(item.callId)
                    synchronized(activityFence) { running.remove(item.callId); activityFence.notifyAll() }
                }
            }
        }
    }

    private fun process(initial: RecordingCandidate, cancelled: () -> Boolean) {
        var current = store.get(initial.callId) ?: return
        if (current.generation != initial.generation) return
        val config = store.settings()
        val cancellation = AtomicBoolean(false)
        cancelledOperations[current.callId] = cancellation
        val isCancelled = { closed || cancelled() || cancellation.get() }
        val operation = client.operation(isCancelled)
        activeClients[current.callId] = operation
        fun update(value: CallArchive): Boolean {
            if (closed) return false
            val next = value.copy(generation = version(current.generation))
            if (!commands.compareAndSet(current.callId, current.generation, next, null, false)) return false
            current = next; return true
        }
        try {
            if (!config.automaticAnalysis && !current.manualAnalysis) {
                update(current.copy(status = ArchiveStatus.READY, nextAttemptAt = 0)); return
            }
            config.analysis.validate(); config.transcription.validate()
            require(config.transcription.protocol == AiProtocol.OPENAI) { "ai.transcription_requires_openai" }
            if (!update(current.copy(attempts = current.attempts + 1, status =
                    if (current.transcript.isBlank()) ArchiveStatus.TRANSCRIBING else ArchiveStatus.ANALYZING))) return
            if (isCancelled()) throw AiFailure("recording.cancelled")
            if (current.transcript.isBlank()) {
                val text = operation.transcribe(config.transcription, AudioReference(requireNotNull(current.audioPath)))
                if (text.isBlank()) throw AiFailure("ai.empty_transcript")
                if (!update(current.copy(transcript = text, transcriptionModel = config.transcription.model,
                        status = ArchiveStatus.ANALYZING))) return
            }
            if (isCancelled()) throw AiFailure("recording.cancelled")
            val result = operation.analyze(config.analysis, current.transcript)
            if (isCancelled()) throw AiFailure("recording.cancelled")
            update(current.copy(analysis = result, status = ArchiveStatus.COMPLETE, error = null,
                nextAttemptAt = 0, analyzedAt = now(), analysisModel = config.analysis.model,
                analysisProtocol = config.analysis.protocol))
        } catch (failure: Exception) {
            if (isCancelled()) {
                update(current.copy(status = ArchiveStatus.QUEUED, error = null,
                    nextAttemptAt = 0, attempts = (current.attempts - 1).coerceAtLeast(0)))
                return
            }
            val retryable = (failure as? AiFailure)?.retryable == true
            update(current.copy(status = ArchiveStatus.FAILED,
                error = (failure as? AiFailure)?.reason ?: "ai.configuration_or_storage",
                nextAttemptAt = if (retryable && current.attempts < 4) now() + (60_000L shl current.attempts.coerceAtMost(4)) else Long.MAX_VALUE))
        }
    }

}
