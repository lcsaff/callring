package com.greatwall.callring.application

import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.recording.policy.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** Durable state transitions; Android supplies files, networking and scheduling. */
class RecordingWorkflow(
    private val store: RecordingStore,
    private val client: AiClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val running = ConcurrentHashMap.newKeySet<Long>()
    private val cancelledOperations = ConcurrentHashMap<Long, AtomicBoolean>()
    private val activeClients = ConcurrentHashMap<Long, AiClient>()
    private val versions = AtomicLong(System.currentTimeMillis() * 1000)
    fun activitySnapshot()=aiObject("running" to aiNumber(running.size.toLong()),"activeClients" to aiNumber(activeClients.size.toLong()),"cancelledOperations" to aiNumber(cancelledOperations.values.count{it.get()}.toLong()))
    private fun version(previous: Long = 0): Long = versions.updateAndGet { maxOf(it + 1, previous + 1) }

    fun attach(callId: Long, number: String, startedAt: Long, path: String, source: RecordingSource, duration: Long): CallArchive {
        require(callId != 0L && number.isNotBlank() && duration > 0) { "recording.invalid_attachment" }
        val old = store.get(callId)
        cancelledOperations[callId]?.set(true)
        activeClients[callId]?.cancel()
        val settings = store.settings()
        val value = CallArchive(callId, number, startedAt, version(old?.generation ?: 0),
            if (settings.automaticAnalysis) ArchiveStatus.QUEUED else ArchiveStatus.READY,
            source, path, if (source == RecordingSource.MICROPHONE) "recording.microphone_unverified" else "recording.unverified",
            duration)
        check(store.compareAndSet(callId, old?.generation, value)) { "recording.changed" }
        return value
    }

    fun retry(callId: Long) {
        check(callId !in running) { "recording.busy" }
        val value = requireNotNull(store.get(callId)) { "recording.not_found" }
        require(value.audioPath != null) { "recording.missing_audio" }
        val config = store.settings()
        config.analysis.validate(); config.transcription.validate()
        require(config.transcription.protocol == AiProtocol.OPENAI) { "ai.transcription_requires_openai" }
        check(store.compareAndSet(callId, value.generation, value.copy(generation = version(value.generation),
            status = ArchiveStatus.QUEUED, attempts = 0, nextAttemptAt = 0, error = null, manualAnalysis = true))) { "recording.changed" }
    }

    fun delete(callId: Long): CallArchive? {
        cancelledOperations[callId]?.set(true)
        activeClients[callId]?.cancel()
        return store.delete(callId)
    }
    fun cancelProcessing() {
        cancelledOperations.values.forEach { it.set(true) }
        activeClients.values.forEach { it.cancel() }
    }

    fun nextRunAt(): Long? = store.all().filter { it.status in pending && it.nextAttemptAt != Long.MAX_VALUE }
        .minOfOrNull { it.nextAttemptAt }

    /** Busy rows are recovered on a new process, with in-process exclusion on each call ID. */
    fun processPending(cancelled: () -> Boolean = { false }) {
        store.all().filter { it.status in pending && it.nextAttemptAt <= now() }.forEach { item ->
            if (cancelled()) return
            if (running.add(item.callId)) try { process(item, cancelled) } finally {
                cancelledOperations.remove(item.callId); activeClients.remove(item.callId); running.remove(item.callId)
            }
        }
    }

    private fun process(initial: CallArchive, cancelled: () -> Boolean) {
        var current = store.get(initial.callId) ?: return
        if (current.generation != initial.generation) return
        val config = store.settings()
        val cancellation = AtomicBoolean(false)
        cancelledOperations[current.callId] = cancellation
        val isCancelled = { cancelled() || cancellation.get() }
        val operation = client.operation(isCancelled)
        activeClients[current.callId] = operation
        fun update(value: CallArchive): Boolean {
            val next = value.copy(generation = version(current.generation))
            if (!store.compareAndSet(current.callId, current.generation, next)) return false
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
                val text = operation.transcribe(config.transcription, File(requireNotNull(current.audioPath)))
                if (text.isBlank()) throw AiFailure("ai.empty_transcript")
                if (!update(current.copy(transcript = text, transcriptionModel = config.transcription.model,
                        status = ArchiveStatus.ANALYZING))) return
            }
            if (isCancelled()) throw AiFailure("recording.cancelled")
            val result = operation.analyze(config.analysis, current.transcript)
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

    companion object {
        private val pending = setOf(ArchiveStatus.QUEUED, ArchiveStatus.TRANSCRIBING, ArchiveStatus.ANALYZING, ArchiveStatus.FAILED)
    }
}
