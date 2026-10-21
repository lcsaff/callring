package com.greatwall.callring.bootstrap

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
    private val storeDelegate = lazy { storeFactory?.invoke() ?: AndroidRecordingStore(context, namespace, recordingVersion = { coordinator.capture().values.getValue("recording") }) }
    val store get() = storeDelegate.value
    val directory = File(context.filesDir, namespace + "_recordings")
    private val sources by lazy { SystemRecordingSource(context.contentResolver) }
    private val client by lazy {
        clientFactory?.invoke() ?: CompatibleAiClient(testAudio = {
            val file = File(context.cacheDir, "ai-connection-test.wav")
            if (!file.isFile) context.assets.open("ai-test.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
            file
        })
    }
    private val aiSettingsDelegate = lazy { AiSettingsService(store, coordinator,
        { context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
        { connection, transcription -> client.test(connection, transcription) },
        afterSave = { if (!it.recordingEnabled) context.stopService(android.content.Intent(context, com.greatwall.callring.CallCaptureService::class.java)) }, folderView = sources::describe, operationActive = operationActive) }
    val aiSettings get() = aiSettingsDelegate.value
    suspend fun reobserveFolder() { if (aiSettingsDelegate.isInitialized()) aiSettingsDelegate.value.reobserveFolder() }
    val archiveCommands by lazy { RecordingArchiveCommands(store, store, coordinator) }
    val registrations by lazy { RecordingRegistrationService(store, coordinator) }
    val workflow by lazy { RecordingWorkflow(store, archiveCommands, AndroidAudioTranscriber(client, File(context.cacheDir, namespace + "_ai_audio"))) }
    private val audio by lazy { AndroidRecordingAudio(context, directory, metadataReader) }
    private val playbackDelegate: Lazy<AndroidRecordingPlayback> = lazy { AndroidRecordingPlayback(directory) { service.playbackFinished() } }
    private val playback: AndroidRecordingPlayback get() = playbackDelegate.value
    val service: RecordingService by lazy { RecordingService(store, store, store::settings, { workflow }, archiveCommands, registrations, aiSettings,
        audio, playback, object : RecordingDirectory {
            override fun authorize(document: DocumentRef) = sources.authorize(document.uri)
            override fun list(folder: String) = sources.list(folder)
            override fun inspect(folder: String, document: DocumentRef) = sources.inspect(folder, document)
        }, findCall, activeCallId, schedule, coordinator, operationActive = operationActive) }
    fun folderAccess(): AccessState {
        val folder = store.settings().systemFolder ?: return AccessState.NOT_REQUIRED
        return if (context.contentResolver.persistedUriPermissions.any { it.uri.toString() == folder && it.isReadPermission }) AccessState.GRANTED else AccessState.DENIED
    }
    override fun close() {
        if (playbackDelegate.isInitialized()) playbackDelegate.value.close()
        if (storeDelegate.isInitialized()) storeDelegate.value.close()
    }
}
