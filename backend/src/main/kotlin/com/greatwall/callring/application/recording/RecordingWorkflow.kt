package com.greatwall.callring.application.recording

import com.greatwall.callring.application.ports.recording.AiClient
import com.greatwall.callring.application.ports.recording.AiFailure
import com.greatwall.callring.application.ports.recording.AudioReference
import com.greatwall.callring.application.ports.recording.OfflineTranscriber
import com.greatwall.callring.application.ports.recording.RecordingCandidate
import com.greatwall.callring.application.ports.recording.RecordingWorkflowReads

import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.AiProtocol
import com.greatwall.callring.application.recording.model.RecordingActivityObservation
import com.greatwall.callring.application.recording.policy.*
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.contracts.common.ApiError
import com.greatwall.callring.contracts.common.ErrorCode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Durable state transitions; Android supplies files, networking and scheduling. */
class RecordingWorkflow(
    private val store: RecordingWorkflowReads,
    private val commands: RecordingArchiveCommands,
    private val client: AiClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val enrichment: com.greatwall.callring.application.ports.recording.RecordingIdentityEnrichment,
    private val directionResolver: (CallArchive) -> com.greatwall.callring.contracts.callers.CallDirection,
    private val offlineTranscriber: OfflineTranscriber? = null,
) {
    private val running = ConcurrentHashMap.newKeySet<Long>()
    private val activityFence = Object()
    @Volatile private var closed = false
    private val cancelledOperations = ConcurrentHashMap<Long, AtomicBoolean>()
    private val activeClients = ConcurrentHashMap<Long, AiClient>()
    private val versions = AtomicLong(System.currentTimeMillis() * 1000)
    private val offlineScope = CoroutineScope(Dispatchers.IO)
    private val offlineStates = ConcurrentHashMap<Long, OfflineTranscriptionRun>()
    private var offlineWork: Pair<Long, AtomicBoolean>? = null
    fun offlineState(callId: Long): OfflineTranscriptionRun? = offlineStates[callId]

    fun startOffline(callId: Long): Long = commands.guardOfflineState {
        val transcriber = offlineTranscriber ?: throw AiFailure("recording.offline_model_unavailable")
        val archive = store.get(callId) ?: throw AiFailure("recording.not_found")
        val audio = archive.audioPath ?: throw AiFailure("recording.missing_audio")
        if (archive.status in setOf(ArchiveStatus.QUEUED, ArchiveStatus.TRANSCRIBING,
                ArchiveStatus.ANALYZING, ArchiveStatus.ENRICHING) ||
            (archive.status == ArchiveStatus.FAILED && archive.nextAttemptAt != Long.MAX_VALUE)) {
            throw AiFailure("recording.offline_busy", retryable = true)
        }
        check(archive.transcript.isBlank()) { "recording.transcript_exists" }
        val run = OfflineTranscriptionRun(version(), OfflineTranscriptionPhase.PREPARING)
        val cancelled = AtomicBoolean(false)
        synchronized(activityFence) {
            check(!closed && offlineWork == null && running.add(callId)) { "recording.busy" }
            offlineWork = callId to cancelled
            offlineStates[callId] = run
            commands.offlineStateChanged(callId)
        }
        offlineScope.launch {
            var error: String? = null
            try {
                val transcript = transcriber.transcribe(AudioReference(audio), { closed || cancelled.get() }) {
                    commands.withCurrent(callId, archive.generation) {
                        synchronized(activityFence) {
                            if (!closed && !cancelled.get()) {
                                offlineStates[callId] = run.copy(phase = OfflineTranscriptionPhase.TRANSCRIBING)
                                commands.offlineStateChanged(callId)
                            }
                        }
                    }
                }
                if (transcript.text.isBlank()) throw AiFailure("recording.silent_audio")
                commands.withCurrent(callId, archive.generation) {
                    synchronized(activityFence) {
                        if (!closed && !cancelled.get()) {
                            commands.compareAndSet(callId, archive.generation, archive.copy(
                                generation = version(archive.generation), status = ArchiveStatus.READY,
                                transcript = transcript.text, transcriptionModel = transcript.model,
                                error = null, nextAttemptAt = 0, resetPaused = false,
                            ), null, false)
                            offlineStates.remove(callId)
                        }
                    }
                }
            } catch (failure: AiFailure) {
                error = failure.reason
            } catch (_: Exception) {
                error = "recording.offline_failed"
            } finally {
                commands.guardOfflineState {
                    synchronized(activityFence) {
                        try {
                            offlineStates.remove(callId)
                            if (!closed) {
                                if (!cancelled.get() && error != null) commands.withCurrent(callId, archive.generation) {
                                    offlineStates[callId] = run.copy(phase = OfflineTranscriptionPhase.FAILED, error = error)
                                }
                                commands.offlineStateChanged(callId)
                            }
                        } finally {
                            offlineWork = null
                            running.remove(callId)
                            activityFence.notifyAll()
                        }
                    }
                }
            }
        }
        run.id
    }

    fun cancelOffline(callId: Long, runId: Long) = commands.guardOfflineState {
        synchronized(activityFence) {
            val run = offlineStates[callId] ?: return@guardOfflineState
            if (closed || run.id != runId || run.phase !in setOf(OfflineTranscriptionPhase.PREPARING, OfflineTranscriptionPhase.TRANSCRIBING)
                || offlineWork?.second?.compareAndSet(false, true) != true) return@guardOfflineState
            offlineStates[callId] = run.copy(phase = OfflineTranscriptionPhase.CANCELLING)
            commands.offlineStateChanged(callId)
        }
    }
    fun isRunning(callId: Long): Boolean = callId in running
    /** Cleanup holds this only for local deletion. Candidate admission never retains it for IO. */
    fun <T> withIdle(callId: Long, block: () -> T): T = synchronized(activityFence) {
        check(!closed) { "recording.closed" }
        check(callId !in running) { "recording.busy" }
        block()
    }
    fun activityObservation() = RecordingActivityObservation(running.size.toLong(), activeClients.size.toLong(), cancelledOperations.values.count{it.get()}.toLong())
    private fun version(previous: Long = 0): Long = versions.updateAndGet { maxOf(it + 1, previous + 1) }

    private fun invalidateOffline(callId: Long) = synchronized(activityFence) {
        offlineWork?.takeIf { it.first == callId }?.second?.set(true)
        offlineStates.remove(callId)
    }

    fun attach(callId: Long, number: String, startedAt: Long, path: String, source: RecordingSource, duration: Long, command: CommandContext?, playbackStopped: Boolean, offlineOnly: Boolean = false, direction: com.greatwall.callring.contracts.callers.CallDirection = com.greatwall.callring.contracts.callers.CallDirection.INCOMING): CallArchive {
        check(!closed) { "recording.closed" }
        require(callId != 0L && number.isNotBlank() && duration > 0) { "recording.invalid_attachment" }
        val previousGeneration = store.generation(callId)
        cancelledOperations[callId]?.set(true)
        invalidateOffline(callId)
        activeClients[callId]?.cancel()
        val settings = store.settings()
        val value = CallArchive(callId, number, startedAt, version(previousGeneration ?: 0),
            if (!offlineOnly && settings.automaticAnalysis && direction == com.greatwall.callring.contracts.callers.CallDirection.INCOMING) ArchiveStatus.QUEUED else ArchiveStatus.READY,
            source, path, if (source == RecordingSource.MICROPHONE) "recording.microphone_unverified" else "recording.unverified",
            duration, direction = direction)
        check(commands.compareAndSet(callId, previousGeneration, value, command, playbackStopped)) { "recording.changed" }
        return value
    }

    private fun validateConnections(archive: CallArchive, config: com.greatwall.callring.application.recording.model.RecordingSettings) {
        config.analysis.validate()
        if (archive.transcript.isBlank()) {
            config.transcription.validate()
            require(config.transcription.protocol == AiProtocol.OPENAI) { "ai.transcription_requires_openai" }
        }
    }

    fun queueTranscriptAnalysis(callId: Long, checkActive: () -> Unit) {
        check(!closed && callId !in running) { "recording.busy" }
        val archive = store.get(callId) ?: throw AiFailure("recording.not_found")
        if (archive.transcript.isBlank()) throw AiFailure("recording.transcript_required")
        queueManual(archive, ArchiveAnalysisIntent.MANUAL_TEXT, null, checkActive)
    }

    fun confirmedView(archive: CallArchive): CallArchive {
        if (archive.manualAnalysisState == ManualAnalysisState.PENDING_CONFIRMATION && enrichment.hasConfirmation(archive))
            return archive.copy(manualAnalysisState = ManualAnalysisState.CONFIRMED)
        return archive
    }

    fun callerSuggestions(callId: Long): CallerSuggestions? {
        val archive = store.get(callId) ?: return null
        if (archive.manualAnalysisState != ManualAnalysisState.PENDING_CONFIRMATION || !archive.canReviewManualResult()) return null
        return enrichment.suggestions(archive)
    }

    fun confirmCallerSuggestions(command: ConfirmCallerSuggestions, checkActive: () -> Unit): CallerSuggestionConfirmation {
        val archive = store.get(command.callId) ?: return CallerSuggestionConfirmation.Conflict(null)
        if (archive.manualAnalysisResultVersion == null || !archive.canReviewManualResult())
            return CallerSuggestionConfirmation.Conflict(null)
        val alreadyCommitted = enrichment.hasConfirmation(archive)
        val result = enrichment.confirm(archive, command, checkActive)
        if (result == CallerSuggestionConfirmation.Applied && archive.manualAnalysisState == ManualAnalysisState.PENDING_CONFIRMATION) {
            // Core already committed. The receipt remains authoritative if archive writeback fails.
            val saved = runCatching { commands.compareAndSet(archive.callId, archive.generation, archive.copy(
                generation = version(archive.generation), manualAnalysisState = ManualAnalysisState.CONFIRMED,
            ), null, false) }.getOrDefault(false)
            if (!saved && !alreadyCommitted) commands.confirmationStateChanged(archive.callId)
        }
        return result
    }

    private fun CallArchive.canReviewManualResult() = status == ArchiveStatus.COMPLETE || status == ArchiveStatus.FAILED

    fun retry(callId: Long, command: CommandContext?) {
        check(!closed) { "recording.closed" }
        check(callId !in running) { "recording.busy" }
        val value = requireNotNull(store.get(callId)) { "recording.not_found" }
        if (value.status in setOf(ArchiveStatus.ENRICHING, ArchiveStatus.ENRICHMENT_FAILED)) {
            check(commands.compareAndSet(callId, value.generation, value.copy(generation = version(value.generation), status = ArchiveStatus.ENRICHING, nextAttemptAt = 0, error = null, resetPaused = false), command, false)) { "recording.changed" }
            return
        }
        if (value.status == ArchiveStatus.COMPLETE && value.manualAnalysisState == ManualAnalysisState.PENDING_CONFIRMATION &&
            !enrichment.hasConfirmation(value))
            throw BackendFailure(ApiError(ErrorCode.CONFLICT, "recording.confirmation_pending"))
        require(value.transcript.isNotBlank() || value.audioPath != null) { "recording.missing_audio" }
        val intent = if (value.transcript.isNotBlank()) ArchiveAnalysisIntent.MANUAL_TEXT else ArchiveAnalysisIntent.MANUAL_LEGACY
        queueManual(value, intent, command)
    }

    private fun queueManual(
        archive: CallArchive, intent: ArchiveAnalysisIntent, command: CommandContext?, checkActive: () -> Unit = {},
    ) {
        validateConnections(archive, store.settings())
        checkActive()
        check(commands.compareAndSet(archive.callId, archive.generation, archive.copy(
            generation = version(archive.generation), status = ArchiveStatus.QUEUED, attempts = 0,
            nextAttemptAt = 0, error = null, manualAnalysis = true, analysisIntent = intent,
            enrichmentBaseline = null, enrichmentSourceGeneration = null,
            resetPaused = false,
        ), command, false)) { "recording.changed" }
    }

    fun delete(callId: Long, command: CommandContext?, playbackStopped: Boolean): Boolean {
        check(!closed) { "recording.closed" }
        cancelledOperations[callId]?.set(true)
        invalidateOffline(callId)
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
        offlineScope.cancel()
        if (interrupted) Thread.currentThread().interrupt()
    }

    fun nextRunAt(): Long? { check(!closed) { "recording.closed" }; return store.earliestPendingAt { closed } }

    /** Candidate pages are consumed before network work; no storage cursor spans an AI request. */
    fun processPending(cancelled: () -> Boolean = { false }, allowNetwork: Boolean = true) {
        val cutoff = now()
        var after: Long? = null
        while (!cancelled() && !closed) {
            val candidates = store.dueCandidates(cutoff, after, 64) { cancelled() || closed }
            if (candidates.isEmpty()) return
            for (item in candidates) {
                if (cancelled() || closed) return
                after = item.callId
                if (synchronized(activityFence) { !closed && running.add(item.callId) }) try { process(item, cancelled, allowNetwork) } finally {
                    cancelledOperations.remove(item.callId); activeClients.remove(item.callId)
                    synchronized(activityFence) { running.remove(item.callId); activityFence.notifyAll() }
                }
            }
        }
    }

    private fun process(initial: RecordingCandidate, cancelled: () -> Boolean, allowNetwork: Boolean) {
        var current = store.get(initial.callId) ?: return
        if(current.resetPaused) return
        if (current.generation != initial.generation) return
        val resolvedDirection = directionResolver(current)
        if (resolvedDirection != current.direction) {
            val automatic = !current.manualAnalysis && current.analysisIntent in setOf(ArchiveAnalysisIntent.AUTOMATIC, ArchiveAnalysisIntent.LEGACY_UNCLASSIFIED)
            val changed = current.copy(generation = version(current.generation), direction = resolvedDirection,
                status = if (automatic && resolvedDirection != com.greatwall.callring.contracts.callers.CallDirection.INCOMING) ArchiveStatus.READY else current.status,
                nextAttemptAt = 0)
            if (!commands.compareAndSet(current.callId,current.generation,changed,null,false)) return
            current = changed
        }
        val config = store.settings()
        val cancellation = AtomicBoolean(false)
        cancelledOperations[current.callId] = cancellation
        val isCancelled = { closed || cancelled() || cancellation.get() }
        val localOnly = current.status in setOf(ArchiveStatus.ENRICHING, ArchiveStatus.ENRICHMENT_FAILED)
        val manual = current.analysisIntent != ArchiveAnalysisIntent.AUTOMATIC || current.manualAnalysis
        val directionRestricted = !current.manualAnalysis && current.analysisIntent !in setOf(ArchiveAnalysisIntent.MANUAL_TEXT, ArchiveAnalysisIntent.MANUAL_LEGACY)
        val operation by lazy { client.operation(isCancelled).also { activeClients[current.callId] = it } }
        fun update(value: CallArchive): Boolean {
            if (closed || (directionRestricted && directionResolver(current) != com.greatwall.callring.contracts.callers.CallDirection.INCOMING)) return false
            val generation = version(current.generation)
            val next = value.copy(generation = generation,
                enrichmentSourceGeneration = value.enrichmentSourceGeneration ?: generation.takeIf { value.status == ArchiveStatus.ENRICHING },
                manualAnalysisResultVersion = value.manualAnalysisResultVersion
                    ?: generation.takeIf { value.manualAnalysisState == ManualAnalysisState.PENDING_CONFIRMATION })
            if (!commands.compareAndSet(current.callId, current.generation, next, null, false)) return false
            current = next; return true
        }
        if (current.direction != com.greatwall.callring.contracts.callers.CallDirection.INCOMING && directionRestricted) {
            commands.compareAndSet(current.callId,current.generation,current.copy(
                generation = version(current.generation),status = ArchiveStatus.READY,
                nextAttemptAt = current.nextAttemptAt.takeIf { it == Long.MAX_VALUE } ?: 0,error = null),null,false)
            return
        }
        if (current.status == ArchiveStatus.COMPLETE) {
            // A compatibility-decoded local result may still have the old durable queue status.
            if (current.manualAnalysisState == ManualAnalysisState.PENDING_CONFIRMATION && !isCancelled()) update(current)
            return
        }
        if (!localOnly && !allowNetwork) return

        fun enrich() {
            if (isCancelled() || (directionRestricted && directionResolver(current) != com.greatwall.callring.contracts.callers.CallDirection.INCOMING)) return
            checkNotNull(current.analysis) { "recording.analysis_missing" }
            if (manual) {
                update(current.copy(status = ArchiveStatus.COMPLETE, error = null, nextAttemptAt = 0,
                    manualAnalysisState = ManualAnalysisState.PENDING_CONFIRMATION))
                return
            }
            val baseline = current.enrichmentBaseline ?: error("recording.baseline_missing")
            if (!baseline.eligible) {
                if (!isCancelled()) update(current.copy(status = ArchiveStatus.COMPLETE, error = null, nextAttemptAt = 0))
                return
            }
            val candidates = current.validatedCallerCandidates()
            if (!commands.withCurrent(current.callId, current.generation) {
                    if (isCancelled() || directionResolver(current) != com.greatwall.callring.contracts.callers.CallDirection.INCOMING) throw AiFailure("recording.cancelled")
                    enrichment.apply(current.number, baseline, current.callId, current.enrichmentSourceGeneration ?: current.generation, candidates)
                }) return
            if (!isCancelled()) update(current.copy(status = ArchiveStatus.COMPLETE, error = null, nextAttemptAt = 0))
        }
        if (localOnly) {
            try { enrich() } catch (_: Exception) {
                if (!isCancelled()) update(current.copy(status = ArchiveStatus.ENRICHMENT_FAILED,
                    error = "recording.enrichment_failed", nextAttemptAt = Long.MAX_VALUE))
            }
            return
        }
        try {
            if (!config.automaticAnalysis && current.analysisIntent in setOf(
                    ArchiveAnalysisIntent.AUTOMATIC, ArchiveAnalysisIntent.LEGACY_UNCLASSIFIED) && !current.manualAnalysis) {
                update(current.copy(status = ArchiveStatus.READY, nextAttemptAt = 0)); return
            }
            if (current.analysisIntent == ArchiveAnalysisIntent.MANUAL_TEXT && current.transcript.isBlank())
                throw AiFailure("recording.transcript_required")
            validateConnections(current, config)
            if (!manual && current.enrichmentBaseline == null) {
                var baselineSaved = false
                val live = commands.withCurrent(current.callId, current.generation) {
                    if (isCancelled()) throw AiFailure("recording.cancelled")
                    val baseline = enrichment.capture(current.number,current.startedAtEpochMillis)
                    if (!isCancelled()) baselineSaved = update(current.copy(enrichmentBaseline = baseline))
                }
                if (!live || !baselineSaved) return
            }
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
            val savedAnalysis = update(current.copy(analysis = result,
                status = if (manual) ArchiveStatus.COMPLETE else ArchiveStatus.ENRICHING, error = null,
                nextAttemptAt = 0, analyzedAt = now(), analysisModel = config.analysis.model,
                analysisProtocol = config.analysis.protocol,
                manualAnalysisState = ManualAnalysisState.PENDING_CONFIRMATION.takeIf { manual },
                manualAnalysisResultVersion = null))
            if (savedAnalysis && !manual) {
                try { enrich() } catch (_: Exception) {
                    if (!isCancelled()) update(current.copy(status = ArchiveStatus.ENRICHMENT_FAILED,
                        error = "recording.enrichment_failed", nextAttemptAt = Long.MAX_VALUE))
                }
            }
        } catch (failure: Exception) {
            // Once analysis is durable, failure-state persistence must never return to network work.
            if (current.status in setOf(ArchiveStatus.ENRICHING, ArchiveStatus.ENRICHMENT_FAILED)) {
                if (!isCancelled()) runCatching { update(current.copy(status = ArchiveStatus.ENRICHMENT_FAILED,
                    error = "recording.enrichment_failed", nextAttemptAt = Long.MAX_VALUE)) }
                return
            }
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
