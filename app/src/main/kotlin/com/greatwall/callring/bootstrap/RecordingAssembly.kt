package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.ports.recording.AiClient
import com.greatwall.callring.domain.calling.RecordingCall

import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.recording.RecordingDirectory
import com.greatwall.callring.application.recording.RecordingWorkflow
import com.greatwall.callring.data.recording.AndroidRecordingStore
import com.greatwall.callring.platform.recording.AndroidAudioTranscriber
import com.greatwall.callring.platform.recording.AndroidRecordingAudio
import com.greatwall.callring.platform.recording.AndroidRecordingPlayback
import com.greatwall.callring.platform.recording.SystemRecordingSource

import android.content.Context
import com.greatwall.callring.application.*
import com.greatwall.callring.application.recording.*
import com.greatwall.callring.platform.ai.*
import com.greatwall.callring.contracts.common.DocumentRef
import com.greatwall.callring.contracts.runtime.AccessState
import java.io.File

/** Owns the shared lazy resources; business rules live in RecordingService. */
class RecordingAssembly(
    private val context: Context,
    namespace: String,
    findCall: (Long) -> RecordingCall?,
    activeCallId: () -> Long?,
    schedule: (Long) -> Unit,
    storeFactory: (() -> AndroidRecordingStore)? = null,
    metadataReader: ((File) -> Pair<Long, String>)? = null,
    private val coordinator: BusinessRevisionCoordinator = BusinessRevisionCoordinator(),
    clientFactory: (() -> AiClient)? = null,
    private val operationActive: () -> Boolean,
) : AutoCloseable {
    private val lifetime = Any()
    private var closed = false
    private fun <T> owned(block: () -> T): T = synchronized(lifetime) {
        check(!closed) { "recording.closed" }
        block()
    }
    private val storeDelegate = lazy { storeFactory?.invoke() ?: AndroidRecordingStore(context, namespace, recordingVersion = { coordinator.capture().values.getValue("recording") }) }
    val store get() = owned { storeDelegate.value }
    val directory = File(context.filesDir, namespace + "_recordings")
    private val sources by lazy { SystemRecordingSource(context.contentResolver) }
    private val clientAccess = com.greatwall.callring.platform.ownership.OwnedFileAccess()
    private val clientDelegate = lazy {
        val instance = clientFactory?.invoke() ?: CompatibleAiClient(testAudio = {
            clientAccess.checkOpen()
            val file = File(context.cacheDir, "ai-connection-test.wav")
            if (!file.isFile) context.assets.open("ai-test.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
            file
        })
        try { clientAccess.checkOpen(); instance } catch (failure: Throwable) { instance.cancel(); throw failure }
    }
    private val client get() = clientDelegate.value
    private val aiSettingsDelegate = lazy { AiSettingsService(store, coordinator,
        { context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
        { connection, transcription -> clientAccess.acquire().use { client.test(connection, transcription); clientAccess.checkOpen() } },
        afterSave = { if (!it.recordingEnabled) context.stopService(android.content.Intent(context, com.greatwall.callring.CallCaptureService::class.java)) }, folderView = sources::describe, operationActive = operationActive) }
    val aiSettings get() = owned { aiSettingsDelegate.value }
    suspend fun reobserveFolder() { if (aiSettingsDelegate.isInitialized()) aiSettingsDelegate.value.reobserveFolder() }
    private val archiveCommandsDelegate = lazy { RecordingArchiveCommands(store, store, coordinator) }
    private val registrationsDelegate = lazy { RecordingRegistrationService(store, coordinator) }
    private val workflowDelegate = lazy { RecordingWorkflow(store, archiveCommands, AndroidAudioTranscriber(client, File(context.cacheDir, namespace + "_ai_audio"))) }
    private val audioDelegate = lazy { AndroidRecordingAudio(context, directory, metadataReader) }
    private val audio get() = audioDelegate.value
    private val playbackDelegate: Lazy<AndroidRecordingPlayback> = lazy { AndroidRecordingPlayback(directory) { service.playbackFinished() } }
    private val playback: AndroidRecordingPlayback get() = playbackDelegate.value
    private val serviceDelegate: Lazy<RecordingService> = lazy { RecordingService(store, store, store::settings, { workflow }, archiveCommands, registrations, aiSettings,
        audio, playback, object : RecordingDirectory {
            override fun authorize(document: DocumentRef) = sources.authorize(document.uri)
            override fun list(folder: String) = sources.list(folder)
            override fun inspect(folder: String, document: DocumentRef) = sources.inspect(folder, document)
        }, findCall, activeCallId, schedule, coordinator, operationActive = operationActive, captureBusy = { com.greatwall.callring.entrypoint.recording.CaptureServiceActivity.busy() }) }
    val archiveCommands get() = owned { archiveCommandsDelegate.value }
    val registrations get() = owned { registrationsDelegate.value }
    val workflow get() = owned { workflowDelegate.value }
    val service get() = owned { serviceDelegate.value }
    fun folderAccess(): AccessState {
        val folder = store.settings().systemFolder ?: return AccessState.NOT_REQUIRED
        return if (context.contentResolver.persistedUriPermissions.any { it.uri.toString() == folder && it.isReadPermission }) AccessState.GRANTED else AccessState.DENIED
    }
    fun cancelPendingWork() {
        clientAccess.seal()
        val resources = synchronized(lifetime) {
            (if (workflowDelegate.isInitialized()) workflowDelegate.value else null) to
                (if (clientDelegate.isInitialized()) clientDelegate.value else null)
        }
        resources.first?.seal(); resources.first?.cancelProcessing(); resources.second?.cancel()
    }
    override fun close() {
        val resources = synchronized(lifetime) {
            if (closed) return
            closed = true
            listOf(
                if (workflowDelegate.isInitialized()) workflowDelegate.value else null,
                if (clientDelegate.isInitialized()) clientDelegate.value else null,
                if (playbackDelegate.isInitialized()) playbackDelegate.value else null,
                if (audioDelegate.isInitialized()) audioDelegate.value else null,
                if (storeDelegate.isInitialized()) storeDelegate.value else null,
            )
        }
        val workflow = resources[0] as? RecordingWorkflow
        val client = resources[1] as? AiClient
        val playback = resources[2] as? AndroidRecordingPlayback
        val audio = resources[3] as? AndroidRecordingAudio
        val store = resources[4] as? AndroidRecordingStore
        clientAccess.seal(); audio?.seal(); playback?.seal()
        client?.cancel()
        try { workflow?.close() }
        finally { try { clientAccess.close() }
            finally { try { playback?.close() }
                finally { try { audio?.close() } finally { store?.close() } } } }
    }
}
