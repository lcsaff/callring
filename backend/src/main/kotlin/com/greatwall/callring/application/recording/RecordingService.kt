package com.greatwall.callring.application.recording

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
) : RecordingApi, com.greatwall.callring.application.callers.NumberDeletionRecordings {
    private val playbackGeneration = AtomicLong()
    private suspend fun <T> result(block: suspend () -> T): ApiResult<T> = withContext(Dispatchers.IO) {
        try { requireOperationActive(operationActive); ApiResult.Success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: com.greatwall.callring.application.ports.BackendFailure) { ApiResult.Failure(failure.error) }
        catch (failure: AiFailure) { ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, failure.reason, retryable = failure.retryable)) }
        catch (_: SecurityException) { ApiResult.Failure(ApiError(ErrorCode.PERMISSION_REQUIRED, "recording.permission_required")) }
        catch (_: IllegalArgumentException) { ApiResult.Failure(ApiError(ErrorCode.VALIDATION, "recording.invalid_configuration_or_audio")) }
        catch (_: IllegalStateException) { ApiResult.Failure(ApiError(ErrorCode.SYSTEM_RESTRICTED, "recording.busy_or_changed", retryable = true)) }
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
    override suspend fun listArchives() = result { reads.listArchives().sortedByDescending { it.startedAtEpochMillis } }
    suspend fun numberArchives(number: String) = result {
        val caller = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        reads.numberMetadata(number) { caller?.isActive == false }
    }
    override suspend fun archives(number: String) = numberArchives(number)
    override suspend fun delete(callId: Long, expectedGeneration: Long, command: CommandContext) = deleteWith(callId, command, expectedGeneration)
    override suspend fun getArchive(callId: Long) = result { state.get(archiveId(callId)) }
    override suspend fun importRecording(callId: Long, number: String, startedAt: Long, document: DocumentRef, source: RecordingSource) = result {
        val caller = currentCoroutineContext()
        importFile(callId, number, startedAt, document, source, callerActive = { caller.isActive })
    }
    private fun importFile(callId: Long, number: String, startedAt: Long, document: DocumentRef,
        source: RecordingSource, automatic: Boolean = false, command: CommandContext? = null, callerActive: () -> Boolean = { true }): CallArchive {
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
            val archive = coordinator.guard(command) {
                requireOperationActive(operationActive)
                checkCaller()
                check(state.generation(call.id) == initialGeneration && (!automatic || !state.isDeleted(call.id))) { "recording.changed" }
                attach(call, prepared, source, command)
            }
            scheduleArchive(archive)
            return archive
        }
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
    private fun attach(call: RecordingCall, prepared: PreparedRecordingAudio, source: RecordingSource, command: CommandContext?): CallArchive {
        val previous = state.get(call.id)
        val reference = prepared.commit()
        playbackGeneration.incrementAndGet()
        val stopped = playback.stop()
        val archive = try {
            workflow().attach(call.id, call.number, call.startedAt, reference.location, source, prepared.durationMillis, command, stopped)
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
    fun playbackSnapshot() = playback.snapshot()
    fun captureFailure(call: RecordingCall, reason: String): Unit = coordinator.guard {
        noteMissing(call, reason)
        val value = state.get(call.id)
        if (value != null && value.audioPath == null) archiveCommands.compareAndSet(call.id, value.generation,
            value.copy(generation = value.generation + 1, error = reason), null, false)
        Unit
    }
    override suspend fun retry(callId: Long) = retryWith(callId, null)
    private suspend fun retryWith(callId: Long, command: CommandContext?) = result {
        coordinator.guard(command) {
            requireOperationActive(operationActive); workflow().retry(archiveId(callId), command) }
        runCatching { schedule(0) }; Unit
    }
    override suspend fun delete(callId: Long) = deleteWith(callId, null)
    private suspend fun deleteWith(callId: Long, command: CommandContext?, expectedGeneration: Long? = null) = result { coordinator.guard(command) {
        requireOperationActive(operationActive)
        if (expectedGeneration != null && state.generation(callId) != expectedGeneration)
            throw BackendFailure(ApiError(ErrorCode.CONFLICT, "recording.changed", retryable = true))
        playbackGeneration.incrementAndGet()
        val playbackStopped = playback.stop()
        try {
            val id = archiveId(callId)
            // Retain the durable path until removal succeeds so cleanup can be retried.
            state.get(id)?.audioPath?.let { path -> audio.deleteOwned(AudioReference(path)) }
            workflow().delete(id, command, playbackStopped)
        } catch (failure: Exception) {
            if (playbackStopped) coordinator.publishRuntime(setOf("runtime:recordingPlayback"))
            throw failure
        }
        Unit
    } }
    override suspend fun play(callId: Long): ApiResult<Unit> = playWith(callId, null)
    private suspend fun playWith(callId: Long, command: CommandContext?): ApiResult<Unit> {
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
                    try { playback.activate(next, callId); activated = true }
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
        directory.authorize(document)
        (if (command == null) aiSettings else aiSettings.bound(command)).setSystemFolder(document.uri)
        Unit
    }
    fun bound(command: CommandContext): RecordingApi = object : RecordingApi by this {
        override suspend fun importRecording(callId: Long, number: String, startedAt: Long, document: DocumentRef, source: RecordingSource) = result { val caller = currentCoroutineContext(); importFile(callId, number, startedAt, document, source, command = command, callerActive = { caller.isActive }) }
        override suspend fun retry(callId: Long) = retryWith(callId, command)
        override suspend fun delete(callId: Long) = deleteWith(callId, command)
        override suspend fun play(callId: Long) = playWith(callId, command)
        override suspend fun stop() = stopWith(command)
        override suspend fun setSystemFolder(document: DocumentRef) = folderWith(document, command)
    }
    override suspend fun systemRecordings() = result { settings().systemFolder?.let(directory::list).orEmpty() }
    override suspend fun inspectSystemRecording(document:DocumentRef)=result {val folder=settings().systemFolder?:throw BackendFailure(ApiError(ErrorCode.NOT_FOUND,"recording.folder_required"));directory.inspect(folder,document)?:throw BackendFailure(ApiError(ErrorCode.NOT_FOUND,"recording.file_missing"))}

    /** Called after phone state settles. Unknown filename formats stay available for manual selection. */
    fun discoverSystemRecordings(cancelled: () -> Boolean = { false }) {
        val config = settings()
        val folder = config.systemFolder
        if (!config.recordingEnabled || folder == null) return
        val since = System.currentTimeMillis() - 12 * 60 * 60 * 1000L
        val metadata = reads.recentMetadata(since, cancelled)
        val calls = metadata.map {
            RecordingCall(it.callId, it.number, it.startedAtEpochMillis, it.durationMillis?.div(1000))
        }
        if (cancelled()) return
        val files = directory.list(folder)
        calls.forEach { call ->
            if (cancelled()) return
            val existing = reads.metadata(call.id, cancelled)
            if ((existing?.hasAudio == true && existing.source != RecordingSource.MICROPHONE) || state.isDeleted(call.id)) return@forEach
            noteMissing(call)
            // A file's modified timestamp cannot establish the call's start or number. Require
            // an explicit filename number and timestamp; reject ambiguous candidates both ways.
            val candidates = files.filter { matches(it, call) }
            val candidate = candidates.singleOrNull() ?: return@forEach
            if (calls.count { matches(candidate, it) } != 1) return@forEach
            try { importFile(call.id, call.number, call.startedAt, DocumentRef(candidate.uri), RecordingSource.SYSTEM, automatic = true, callerActive = { !cancelled() }) }
            catch (_: Exception) { /* Keep unmatched or inaccessible audio available for explicit association. */ }
        }
    }
    private fun matches(file: RecordingFile, call: RecordingCall): Boolean {
        val number = Regex("(?<![0-9])" + Regex.escape(call.number) + "(?![0-9])")
        if (!number.containsMatchIn(file.name)) return false
        val timestamp = Regex("(?<![0-9])(20[0-9]{6})[_-]([0-9]{6})(?![0-9])").find(file.name) ?: return false
        val instant = runCatching { java.time.LocalDateTime.parse(timestamp.groupValues[1] + timestamp.groupValues[2],
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() ?: return false
        return kotlin.math.abs(instant - call.startedAt) <= 3000
    }
    fun runScheduled(cancelled: () -> Boolean): Long? {
        // Directory access is independent of already imported durable work.
        if (!cancelled()) runCatching { discoverSystemRecordings(cancelled) }
        if (!cancelled()) workflow().processPending(cancelled)
        return workflow().nextRunAt()
    }
}
