package com.greatwall.callring.application.recording

import com.greatwall.callring.application.ports.recording.AiFailure
import com.greatwall.callring.application.ports.recording.AudioReference
import com.greatwall.callring.domain.calling.RecordingCall

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.recording.PreparedRecordingAudio
import com.greatwall.callring.application.ports.recording.RecordingArchiveState
import com.greatwall.callring.application.ports.recording.RecordingAudioFiles
import com.greatwall.callring.application.ports.recording.RecordingDirectory
import com.greatwall.callring.application.ports.recording.RecordingIdentityReads
import com.greatwall.callring.application.ports.recording.RecordingPlayback

import com.greatwall.callring.application.coordination.requireOperationActive

import com.greatwall.callring.application.*
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class RecordingService(
    private val reads: RecordingIdentityReads,
    private val state: RecordingArchiveState,
    private val settings: () -> com.greatwall.callring.application.recording.model.RecordingSettings,
    private val workflow: () -> RecordingWorkflow,
    private val archiveCommands: RecordingArchiveCommands,
    private val registrations: RecordingRegistrationService,
    private val aiSettings: AiSettingsService,
    private val audio: RecordingAudioFiles,
    private val playback: RecordingPlayback,
    private val directory: RecordingDirectory,
    private val findCall: (Long) -> RecordingCall?,
    private val activeCallId: () -> Long?,
    private val schedule: (Long) -> Unit,
    private val coordinator: BusinessRevisionCoordinator,
    private val operationActive: () -> Boolean,
    private val captureBusy: () -> Boolean,
    private val discovery: com.greatwall.callring.application.ports.recording.SystemRecordingDiscoveryRunner,
    private val networkAvailable: () -> Boolean,
) : RecordingApi, com.greatwall.callring.application.callers.NumberDeletionRecordingReads,
    com.greatwall.callring.application.ports.storage.StorageArchiveAccess {
    private val playbackGeneration = AtomicLong()
    // Read and updated under the coordinator fence, together with player activation.
    private var activePlaybackToken: String? = null
    private suspend fun <T> result(block: suspend () -> T): ApiResult<T> = withContext(Dispatchers.IO) {
        try { requireOperationActive(operationActive); ApiResult.Success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: com.greatwall.callring.application.ports.BackendFailure) { ApiResult.Failure(failure.error) }
        catch (failure: AiFailure) { ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, failure.reason, retryable = failure.retryable)) }
        catch (_: SecurityException) { ApiResult.Failure(ApiError(ErrorCode.PERMISSION_REQUIRED, "recording.permission_required")) }
        catch (_: IllegalArgumentException) { ApiResult.Failure(ApiError(ErrorCode.VALIDATION, "recording.invalid_configuration_or_audio")) }
        catch (_: IllegalStateException) { ApiResult.Failure(ApiError(ErrorCode.SYSTEM_RESTRICTED, "recording.busy_or_changed", retryable = true)) }
        catch (failure: java.io.IOException) {
            val reason = when(failure.message) {
                "storage.insufficient_space" -> "recording.insufficient_space"
                "storage.space_unknown" -> "recording.space_unknown"
                else -> "recording.storage_or_network"
            }
            ApiResult.Failure(ApiError(if(reason == "recording.storage_or_network") ErrorCode.IO_FAILURE else ErrorCode.STORAGE_FAILURE, reason, retryable=true))
        }
        catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "recording.storage_or_network", retryable = true)) }
    }
    fun observeIncoming(sessionId: String, number: String, at: Long, account: String?): RecordingCall? = coordinator.guard {
        if (!settings().recordingEnabled || number.isBlank()) null else registrations.registerIncoming(sessionId, number, at, account, null)
    }
    fun linkStatistics(sessionId: String, statisticsId: Long) {
        registrations.linkStatistics(sessionId, statisticsId, null)
    }
    private fun recordingCall(callId: Long): RecordingCall? {
        if (callId < 0) return reads.findRecording(callId)
        reads.linkedRecording(callId)?.let { return it }
        reads.metadata(callId) { false }?.let { return RecordingCall(it.callId, it.number, it.startedAtEpochMillis, it.durationMillis?.div(1000)) }
        val legacy = findCall(callId) ?: return null
        return reads.linkedRecording(legacy.id) ?: legacy
    }
    private fun archiveId(callId: Long): Long = recordingCall(callId)?.id ?: callId
    override suspend fun getIdentity(callId: Long) = result { val call = requireNotNull(recordingCall(callId)); RecordingIdentity(call.id, call.number, call.startedAt) }
    override suspend fun queryArchives(query: ArchiveListQuery) = result {
        val caller = currentCoroutineContext()
        require(query.limit in 1..100)
        reads.queryArchives(query) { !caller.isActive || !operationActive() }
    }
    override fun sourceStamp(): String = coordinator.guard { requireOperationActive(operationActive); reads.archiveStamp() }
    override suspend fun page(query: ArchiveListQuery) = queryArchives(query)
    override fun snapshot(callId: Long): com.greatwall.callring.application.ports.storage.StorageArchiveState? = coordinator.guard {
        requireOperationActive(operationActive)
        val metadata = reads.metadata(callId) { !operationActive() } ?: return@guard null
        com.greatwall.callring.application.ports.storage.StorageArchiveState(callId, metadata.generation, metadata.status,
            reads.ownedAudio(callId) { !operationActive() }?.let(audio::ownedBytes) ?: if (!metadata.hasAudio) 0L else null,
            captureBusy() || activeCallId()?.let { archiveId(it) == callId } == true || workflow().isRunning(callId) ||
                playback.observation().callId?.let(::archiveId) == callId || metadata.status in setOf(ArchiveStatus.QUEUED, ArchiveStatus.TRANSCRIBING, ArchiveStatus.ANALYZING, ArchiveStatus.ENRICHING))
    }
    override suspend fun remove(callId: Long, expectedGeneration: Long): ApiResult<Unit> =
        deleteWith(callId, null, expectedGeneration, requireInactive = true)
    suspend fun numberArchives(number: String) = result {
        val caller = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        reads.numberMetadata(number) { caller?.isActive == false }
    }
    override suspend fun archives(number: String) = numberArchives(number)
    override suspend fun getArchive(callId: Long) = result { coordinator.guard {
        val id = archiveId(callId)
        state.get(id)?.let { archive ->
            workflow().confirmedView(archive).copy(offlineTranscription = workflow().offlineState(id))
        }
    } }
    override suspend fun transcribeOffline(callId: Long) = result {
        coordinator.guard { workflow().startOffline(archiveId(callId)) }
    }
    override suspend fun cancelOfflineTranscription(callId: Long, runId: Long) = result {
        coordinator.guard { workflow().cancelOffline(archiveId(callId), runId) }
    }
    override suspend fun importRecording(callId: Long, number: String, startedAt: Long, document: DocumentRef, source: RecordingSource, offlineOnly: Boolean) = result {
        val caller = currentCoroutineContext()
        importFile(callId, number, startedAt, document, source, offlineOnly = offlineOnly, callerActive = { caller.isActive })
    }
    private fun importFile(callId: Long, number: String, startedAt: Long, document: DocumentRef,
        source: RecordingSource, automatic: Boolean = false, command: CommandContext? = null, callerActive: () -> Boolean = { true }, validatePrepared: () -> Unit = {}, offlineOnly: Boolean = false): CallArchive {
        val saved = reads.metadata(callId) { false }?.takeIf { it.number == number && it.startedAtEpochMillis == startedAt }
        val request = requireNotNull(if (callId < 0) reads.findRecording(callId) else saved?.let {
            RecordingCall(it.callId, it.number, it.startedAtEpochMillis, it.durationMillis?.div(1000))
        } ?: findCall(callId))
        require(request.number == number && request.startedAt == startedAt)
        val call = requireNotNull(recordingCall(callId))
        val initialGeneration = state.generation(call.id)
        coordinator.guard(command) {
            requireOperationActive(operationActive); check(!automatic || !state.isDeleted(call.id)) { "recording.deleted" } }
        fun checkCaller() { if (!callerActive()) throw CancellationException("recording.cancelled") }
        checkCaller()
        audio.prepareImport(document) { !callerActive() || (command != null && !command.active()) }.use { prepared ->
            validatePrepared()
            val archive = coordinator.guard(command) {
                requireOperationActive(operationActive)
                checkCaller()
                check(state.generation(call.id) == initialGeneration && (!automatic || !state.isDeleted(call.id))) { "recording.changed" }
                attach(call, prepared, source, command, offlineOnly)
            }
            scheduleArchive(archive)
            return archive
        }
    }
    /** The discovery runner supplies the same frozen source/settings checks before and after copy. */
    fun importDiscovered(call: RecordingCall, candidate: com.greatwall.callring.application.ports.recording.SystemRecordingCandidate, validate: () -> Unit, inspect: () -> RecordingFile?) {
        val file = candidate.entry.file
        fun current() {
            validate()
            val observed = inspect() ?: throw java.io.IOException("recording.file_missing")
            check(observed.size == file.size && observed.modifiedKnown && observed.modifiedAt == file.modifiedAt && observed.name == file.name) { "recording.file_changed" }
        }
        current()
        importFile(call.id,call.number,call.startedAt,DocumentRef(file.uri),RecordingSource.SYSTEM,automatic=true,
            callerActive={ try { validate(); true } catch(_: CancellationException) { false } }, validatePrepared=::current)
    }
    fun attachCapture(call: RecordingCall, reference: AudioReference): CallArchive {
        val initialGeneration = state.generation(call.id)
        audio.prepareCapture(reference).use { prepared ->
            val archive = coordinator.guard {
                check(!state.isDeleted(call.id) && state.generation(call.id) == initialGeneration) { "recording.changed" }
                attach(call, prepared, RecordingSource.MICROPHONE, null)
            }
            scheduleArchive(archive)
            return archive
        }
    }
    private fun scheduleArchive(archive: CallArchive) {
        if (archive.status == ArchiveStatus.QUEUED) runCatching { schedule(0) }
    }
    private fun attach(call: RecordingCall, prepared: PreparedRecordingAudio, source: RecordingSource, command: CommandContext?, offlineOnly: Boolean = false): CallArchive {
        val previous = state.get(call.id)
        val reference = prepared.commit()
        playbackGeneration.incrementAndGet()
        val stopped = playback.stop()
        val archive = try {
            workflow().attach(call.id, call.number, call.startedAt, reference.location, source, prepared.durationMillis, command, stopped, offlineOnly)
        } catch (failure: Exception) {
            if (stopped) playbackFinished()
            throw failure
        }
        prepared.retain()
        previous?.audioPath?.takeIf { it != reference.location }?.let { runCatching { audio.deleteOwned(AudioReference(it)) } }
        return archive
    }
    fun noteMissing(call: RecordingCall, reason: String? = null): Unit = coordinator.guard {
        if (!state.isDeleted(call.id) && state.generation(call.id) == null) archiveCommands.compareAndSet(call.id, null, CallArchive(call.id, call.number, call.startedAt,
            generation = System.currentTimeMillis() * 1000, error = reason), null, false)
        Unit
    }
    private fun captureCandidate(): RecordingCall? = activeCallId()?.let(::recordingCall)
    data class CapturePlan(val call: RecordingCall, val voiceCall: Boolean)
    fun capturePlan(privileged: Boolean, microphoneGranted: Boolean, foregroundRequest: Boolean): CapturePlan? {
        val config = settings()
        val call = captureCandidate()?.takeIf { config.recordingEnabled } ?: return null
        if (foregroundRequest) noteMissing(call)
        if (foregroundRequest && !privileged && config.captureMode != CaptureMode.MICROPHONE_EXPERIMENTAL) return null
        if ((!privileged && config.captureMode != CaptureMode.MICROPHONE_EXPERIMENTAL) ||
            ((!privileged || foregroundRequest) && !microphoneGranted)) {
            captureFailure(call, "recording.permission_required")
            return null
        }
        return CapturePlan(call, privileged)
    }
    fun playbackStatus() = playback.status()
    fun playbackObservation() = playback.observation()
    fun captureFailure(call: RecordingCall, reason: String): Unit = coordinator.guard {
        noteMissing(call, reason)
        val value = state.get(call.id)
        if (value != null && value.audioPath == null) archiveCommands.compareAndSet(call.id, value.generation,
            value.copy(generation = value.generation + 1, error = reason), null, false)
        Unit
    }
    override suspend fun analyzeTranscript(callId: Long) = result {
        val caller = currentCoroutineContext()
        coordinator.guard {
            caller.ensureActive()
            workflow().queueTranscriptAnalysis(archiveId(callId), caller::ensureActive)
        }
        runCatching { schedule(0) }
        Unit
    }

    override suspend fun getCallerSuggestions(callId: Long) = result {
        coordinator.guard { workflow().callerSuggestions(archiveId(callId)) }
    }

    override suspend fun confirmCallerSuggestions(command: ConfirmCallerSuggestions) = result {
        val caller = currentCoroutineContext()
        coordinator.guard {
            caller.ensureActive()
            workflow().confirmCallerSuggestions(
                command.copy(callId = archiveId(command.callId), fields = command.fields.toSet()), caller::ensureActive,
            )
        }
    }

    override suspend fun retry(callId: Long) = retryWith(callId, null)
    private suspend fun retryWith(callId: Long, command: CommandContext?) = result {
        coordinator.guard(command) {
            requireOperationActive(operationActive); workflow().retry(archiveId(callId), command) }
        runCatching { schedule(0) }; Unit
    }
    override suspend fun delete(callId: Long) = deleteWith(callId, null)
    private suspend fun deleteWith(callId: Long, command: CommandContext?, expectedGeneration: Long? = null, requireInactive: Boolean = false) = result { coordinator.guard(command) {
        requireOperationActive(operationActive)
        val id = archiveId(callId)
        if (expectedGeneration != null && state.generation(id) != expectedGeneration)
            throw BackendFailure(ApiError(ErrorCode.CONFLICT, "recording.changed", retryable = true))
        fun removeArchive() {
            if (requireInactive && snapshot(id)?.active != false)
                throw BackendFailure(ApiError(ErrorCode.CONFLICT, "recording.busy_or_changed", retryable = true))
            val playbackStopped = if(playback.observation().callId?.let(::archiveId) == id) {
                playbackGeneration.incrementAndGet()
                playback.stop()
            } else false
            try {
                // Retain the durable path until removal succeeds so cleanup can be retried.
                reads.ownedAudio(id) { !operationActive() }?.let(audio::deleteOwned)
                workflow().delete(id, command, playbackStopped)
            } catch (failure: Exception) {
                if (playbackStopped) coordinator.publishRuntime(setOf("runtime:recordingPlayback"))
                throw failure
            }
        }
        if(requireInactive) workflow().withIdle(id, ::removeArchive) else removeArchive()
        Unit
    } }
    override suspend fun getPlaybackSession(): ApiResult<RecordingPlaybackSession?> = result { coordinator.guard {
        val id = playbackObservation().callId
        val token = activePlaybackToken
        if (id != null && token != null) RecordingPlaybackSession(id, token) else null
    } }
    override suspend fun playOwned(callId: Long, token: String): ApiResult<Unit> = playWith(callId, null, token)
    override suspend fun stopPlaybackSession(token: String): ApiResult<Unit> = stopPlaybackSessionWith(token, null)
    private suspend fun stopPlaybackSessionWith(token: String, command: CommandContext?) = result { coordinator.guard(command) {
        requireOperationActive(operationActive)
        if (activePlaybackToken == token) {
            activePlaybackToken = null
            if (playback.stop()) {
                playbackFinished()
                coordinator.record(command)
            }
        }
    } }
    override suspend fun play(callId: Long): ApiResult<Unit> = playWith(callId, null)
    private suspend fun playWith(callId: Long, command: CommandContext?, token: String = UUID.randomUUID().toString()): ApiResult<Unit> {
        val ticket = playbackGeneration.incrementAndGet()
        return result {
            val caller = currentCoroutineContext()
            caller.ensureActive()
            val archive = requireNotNull(state.get(archiveId(callId)))
            val next = playback.prepare(AudioReference(requireNotNull(archive.audioPath)))
            var activated = false
            try {
                coordinator.guard(command) {
                    requireOperationActive(operationActive)
                    caller.ensureActive()
                    check(ticket == playbackGeneration.get() && state.generation(archive.callId) == archive.generation) { "recording.changed" }
                    val stopped = playback.stop()
                    activePlaybackToken = null
                    try {
                        playback.activate(next, callId)
                        activePlaybackToken = token
                        activated = true
                    }
                    catch (failure: Exception) { if (stopped) playbackFinished(); throw failure }
                    playbackFinished()
                    coordinator.record(command)
                }
            } finally { if (!activated) next.close() }
        }
    }
    fun playbackFinished() { coordinator.publishRuntime(setOf("runtime:recordingPlayback")) }
    override suspend fun stop(): ApiResult<Unit> = stopWith(null)
    private suspend fun stopWith(command: CommandContext?) = result { coordinator.guard(command) {
        requireOperationActive(operationActive)
        playbackGeneration.incrementAndGet()
        if (playback.stop()) playbackFinished()
        coordinator.record(command)
    } }
    override suspend fun setSystemFolder(document: DocumentRef) = folderWith(document, null)
    private suspend fun folderWith(document: DocumentRef, command: CommandContext?) = result {
        val previousFolder = settings().systemFolder
        directory.authorize(document)
        (if (command == null) aiSettings else aiSettings.bound(command)).setSystemFolder(document.uri)
        // Regranting the same tree can restore permission without changing a stored setting.
        runCatching { aiSettings.reobserveFolder() }
        if(previousFolder == document.uri && settings().recordingEnabled) runCatching { schedule(0) }
        Unit
    }
    fun bound(command: CommandContext): RecordingApi = object : RecordingApi by this {
        override suspend fun importRecording(callId: Long, number: String, startedAt: Long, document: DocumentRef, source: RecordingSource, offlineOnly: Boolean) = result { val caller = currentCoroutineContext(); importFile(callId, number, startedAt, document, source, offlineOnly = offlineOnly, command = command, callerActive = { caller.isActive }) }
        override suspend fun retry(callId: Long) = retryWith(callId, command)
        override suspend fun delete(callId: Long) = deleteWith(callId, command)
        override suspend fun play(callId: Long) = playWith(callId, command)
        override suspend fun playOwned(callId: Long, token: String) = playWith(callId, command, token)
        override suspend fun stopPlaybackSession(token: String) = stopPlaybackSessionWith(token, command)
        override suspend fun stop() = stopWith(command)
        override suspend fun setSystemFolder(document: DocumentRef) = folderWith(document, command)
    }
    override suspend fun systemRecordings() = result { settings().systemFolder?.let(directory::list).orEmpty() }
    override suspend fun inspectSystemRecording(document:DocumentRef)=result {val folder=settings().systemFolder?:throw BackendFailure(ApiError(ErrorCode.NOT_FOUND,"recording.folder_required"));directory.inspect(folder,document)?:throw BackendFailure(ApiError(ErrorCode.NOT_FOUND,"recording.file_missing"))}

    fun discoverSystemRecordings(cancelled: () -> Boolean = { false }): Long? = discovery.discover(cancelled)
    fun runScheduled(cancelled: () -> Boolean): Long? {
        val nextDiscovery = if (!cancelled()) try { discoverSystemRecordings(cancelled) }
            catch(_: CancellationException) { if(cancelled() || !operationActive()) return null else System.currentTimeMillis() + 1000 }
            catch(_: SecurityException) { null }
            catch(_: Exception) { System.currentTimeMillis() + 60_000 } else null
        val online = networkAvailable()
        if (!cancelled()) workflow().processPending(cancelled, allowNetwork = online)
        if(cancelled()) return null
        val pending = workflow().nextRunAt()?.let { if(online) it else maxOf(it,System.currentTimeMillis() + 60_000) }
        return listOfNotNull(nextDiscovery,pending).minOrNull()
    }
}
