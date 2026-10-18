package com.greatwall.callring.platform.ai

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import com.greatwall.callring.application.*
import com.greatwall.callring.application.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class AndroidRecordingRuntime(
    private val context: Context,
    namespace: String,
    private val findCall: (Long) -> RecordingCall?,
    private val activeCallId: () -> Long?,
    private val schedule: (Long) -> Unit,
    private val storeFactory: () -> AndroidRecordingStore = { AndroidRecordingStore(context, namespace) },
    private val metadataReader: ((File) -> Pair<Long, String>)? = null,
) : RecordingApi, AutoCloseable {
    val store by lazy { storeFactory() }
    val directory = File(context.filesDir, namespace + "_recordings")
    private val sources = SystemRecordingSource(context.contentResolver)
    private val client by lazy {
        CompatibleAiClient(testAudio = {
            val file = File(context.cacheDir, "ai-connection-test.wav")
            if (!file.isFile) context.assets.open("ai-test.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
            file
        })
    }
    val workflow by lazy { RecordingWorkflow(store, AndroidAudioTranscriber(client, File(context.cacheDir, namespace + "_ai_audio"))) }
    private var player: MediaPlayer? = null
    private val playbackGeneration = AtomicLong()
    private val filesLock = Any()

    private suspend fun <T> result(block: () -> T): ApiResult<T> = withContext(Dispatchers.IO) {
        try { ApiResult.Success(block()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: AiFailure) { ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, failure.reason, retryable = failure.retryable)) }
        catch (_: SecurityException) { ApiResult.Failure(ApiError(ErrorCode.PERMISSION_REQUIRED, "recording.permission_required")) }
        catch (_: IllegalArgumentException) { ApiResult.Failure(ApiError(ErrorCode.VALIDATION, "recording.invalid_configuration_or_audio")) }
        catch (_: IllegalStateException) { ApiResult.Failure(ApiError(ErrorCode.SYSTEM_RESTRICTED, "recording.busy_or_changed", retryable = true)) }
        catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "recording.storage_or_network", retryable = true)) }
    }
    override suspend fun getSettings() = result { store.settings() }
    override suspend fun saveSettings(settings: RecordingSettings) = result {
        settings.validate(); store.saveSettings(settings)
        if (!settings.recordingEnabled) context.stopService(android.content.Intent(context, com.greatwall.callring.CallCaptureService::class.java))
        settings
    }
    override suspend fun testConnection(connection: AiConnection, transcription: Boolean) = result { client.test(connection, transcription) }
    fun observeIncoming(sessionId: String, number: String, at: Long, account: String?): RecordingCall? = synchronized(filesLock) {
        if (!store.settings().recordingEnabled || number.isBlank()) return@synchronized null
        store.registerIncoming(sessionId, number, at, account)
    }
    fun linkStatistics(sessionId: String, statisticsId: Long) {
        if (store.hasSession(sessionId)) store.linkStatistics(statisticsId, sessionId)
    }
    private fun recordingCall(callId: Long): RecordingCall? {
        if (callId < 0) return store.findRecording(callId)
        store.linkedRecording(callId)?.let { return it }
        store.get(callId)?.let { return RecordingCall(it.callId, it.number, it.startedAtEpochMillis, it.durationMillis?.div(1000)) }
        val legacy = findCall(callId) ?: return null
        return store.linkedRecording(legacy.id) ?: legacy
    }
    private fun archiveId(callId: Long): Long = recordingCall(callId)?.id ?: callId
    override suspend fun listArchives() = result { store.all().sortedByDescending { it.startedAtEpochMillis } }
    override suspend fun getArchive(callId: Long) = result { store.get(archiveId(callId)) }
    override suspend fun importRecording(callId: Long, number: String, startedAt: Long, document: DocumentRef, source: RecordingSource) = result {
        importFile(callId, number, startedAt, document, source)
    }
    private fun importFile(callId: Long, number: String, startedAt: Long, document: DocumentRef,
        source: RecordingSource, automatic: Boolean = false): CallArchive {
        val saved = store.get(callId)?.takeIf { it.number == number && it.startedAtEpochMillis == startedAt }
        val request = requireNotNull(if (callId < 0) store.findRecording(callId) else saved?.let {
            RecordingCall(it.callId, it.number, it.startedAtEpochMillis, it.durationMillis?.div(1000))
        } ?: findCall(callId))
        require(request.number == number && request.startedAt == startedAt)
        val call = requireNotNull(recordingCall(callId))
        val uri = Uri.parse(document.uri)
        require(uri.scheme == "content")
        return synchronized(filesLock) {
            check(!automatic || !store.isDeleted(call.id)) { "recording.deleted" }
            check(directory.isDirectory || directory.mkdirs())
            val scratch = File(directory, UUID.randomUUID().toString() + ".tmp")
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input)
                    scratch.outputStream().use { out ->
                        val buffer = ByteArray(16384); var total = 0L
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            total += n; require(total <= 256L * 1024 * 1024)
                            out.write(buffer, 0, n)
                        }
                        out.fd.sync()
                    }
                }
                val metadata = metadata(scratch)
                val audio = File(directory, UUID.randomUUID().toString() + "." + metadata.second)
                check(scratch.renameTo(audio))
                attach(call, audio, source, metadata.first)
            } finally { scratch.delete() }
        }
    }
    private fun metadata(file: File): Pair<Long, String> {
        metadataReader?.let { return it(file) }
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.absolutePath)
            val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(duration > 0)
            val extension = when (reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)) {
                "audio/mpeg" -> "mp3"
                "audio/ogg", "application/ogg" -> "ogg"
                "audio/flac" -> "flac"
                "audio/amr", "audio/3gpp" -> "amr"
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/aac" -> "aac"
                "audio/webm", "video/webm" -> "webm"
                else -> "m4a"
            }
            return duration to extension
        } finally { reader.release() }
    }
    fun attachCapture(call: RecordingCall, file: File): CallArchive = synchronized(filesLock) {
        require(file.canonicalFile.parentFile == directory.canonicalFile)
        check(!store.isDeleted(call.id)) { "recording.deleted" }
        attach(call, file, RecordingSource.MICROPHONE, metadata(file).first)
    }
    private fun attach(call: RecordingCall, file: File, source: RecordingSource, duration: Long): CallArchive {
        playbackGeneration.incrementAndGet()
        stopPlayer()
        val old = store.get(call.id)
        val archive = try { workflow.attach(call.id, call.number, call.startedAt, file.absolutePath, source, duration) }
        catch (failure: Exception) { file.delete(); throw failure }
        old?.audioPath?.let { ownedFile(it)?.delete() }
        if (archive.status == ArchiveStatus.QUEUED) schedule(0)
        return archive
    }
    fun noteMissing(call: RecordingCall, reason: String? = null): Unit = synchronized(filesLock) {
        if (store.isDeleted(call.id)) return
        if (store.get(call.id) != null) return
        store.compareAndSet(call.id, null, CallArchive(call.id, call.number, call.startedAt,
            generation = System.currentTimeMillis() * 1000, error = reason))
    }
    fun captureCandidate(): RecordingCall? = activeCallId()?.let(::recordingCall)
    fun captureFailure(call: RecordingCall, reason: String): Unit = synchronized(filesLock) {
        noteMissing(call, reason)
        val value = store.get(call.id) ?: return
        if (value.audioPath == null) store.compareAndSet(call.id, value.generation,
            value.copy(generation = value.generation + 1, error = reason))
    }
    override suspend fun retry(callId: Long) = result { workflow.retry(archiveId(callId)); schedule(0) }
    override suspend fun delete(callId: Long) = result {
        playbackGeneration.incrementAndGet()
        synchronized(filesLock) { stopPlayer(); workflow.delete(archiveId(callId))?.audioPath?.let { ownedFile(it)?.delete() }; Unit }
    }
    private fun ownedFile(path: String): File? = File(path).canonicalFile.takeIf { it.parentFile == directory.canonicalFile }
    override suspend fun play(callId: Long): ApiResult<Unit> {
        val ticket = playbackGeneration.incrementAndGet()
        return result { synchronized(filesLock) {
            if (ticket != playbackGeneration.get()) return@synchronized
            stopPlayer()
            val file = ownedFile(requireNotNull(store.get(archiveId(callId))?.audioPath)) ?: throw IOException()
            val next = MediaPlayer()
            try {
                next.setDataSource(file.absolutePath); next.prepare()
                if (ticket != playbackGeneration.get()) { next.release(); return@synchronized }
                next.setOnCompletionListener { synchronized(filesLock) { if (player === it) stopPlayer() } }
                next.setOnErrorListener { mp, _, _ -> synchronized(filesLock) { if (player === mp) stopPlayer() }; true }
                next.start(); player = next
            } catch (failure: Exception) { next.release(); throw failure }
        } }
    }
    private fun stopPlayer() { player?.release(); player = null }
    override suspend fun stop(): ApiResult<Unit> {
        // Invalidate a pending prepare before waiting for its IO/monitor to finish.
        playbackGeneration.incrementAndGet()
        return result { synchronized(filesLock) { stopPlayer() } }
    }
    override suspend fun setSystemFolder(document: DocumentRef) = result {
        sources.authorize(document.uri)
        store.settings().copy(systemFolder = document.uri).also { store.saveSettings(it) }
    }
    override suspend fun systemRecordings() = result { store.settings().systemFolder?.let(sources::list).orEmpty() }

    /** Called after phone state settles. Unknown filename formats stay available for manual selection. */
    fun discoverSystemRecordings() {
        val config = store.settings()
        val folder = config.systemFolder
        if (!config.recordingEnabled || folder == null) return
        val since = System.currentTimeMillis() - 12 * 60 * 60 * 1000L
        val calls = store.all().filter { it.startedAtEpochMillis >= since }.map {
            RecordingCall(it.callId, it.number, it.startedAtEpochMillis, it.durationMillis?.div(1000))
        }
        val files = sources.list(folder)
        calls.forEach { call ->
            val existing = store.get(call.id)
            if ((existing?.audioPath != null && existing.source != RecordingSource.MICROPHONE) || store.isDeleted(call.id)) return@forEach
            noteMissing(call)
            // A file's modified timestamp cannot establish the call's start or number. Require
            // an explicit filename number and timestamp; reject ambiguous candidates both ways.
            val candidates = files.filter { matches(it, call) }
            val candidate = candidates.singleOrNull() ?: return@forEach
            if (calls.count { matches(candidate, it) } != 1) return@forEach
            try { importFile(call.id, call.number, call.startedAt, DocumentRef(candidate.uri), RecordingSource.SYSTEM, automatic = true) }
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
    fun process(cancelled: () -> Boolean) {
        workflow.processPending(cancelled)
    }
    override fun close() { synchronized(filesLock) { stopPlayer(); store.close() } }
}
