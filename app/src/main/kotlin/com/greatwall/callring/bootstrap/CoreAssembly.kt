package com.greatwall.callring.bootstrap

import com.greatwall.callring.data.preferences.AndroidPreferences
import com.greatwall.callring.platform.audio.AndroidAudioController
import com.greatwall.callring.platform.audio.AndroidPlaybackCommands
import com.greatwall.callring.platform.audio.PlaybackStateTracker
import com.greatwall.callring.platform.calendar.HolidayCalendarSource
import com.greatwall.callring.platform.calling.AndroidCallLogSource
import com.greatwall.callring.platform.diagnostics.AndroidDiagnosticsStore
import com.greatwall.callring.platform.document.AndroidDocumentStore
import com.greatwall.callring.platform.runtime.AndroidRuntimeProbe
import com.greatwall.callring.platform.scheduler.AndroidSyncScheduler

import android.content.Context
import com.greatwall.callring.application.CallringBackend
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.platform.*

/** Core adapters and service construction, without opening persistence during graph construction. */
internal class CoreAssembly(
    private val application: Context,
    private val configuration: BackendConfiguration,
    val coordinator: com.greatwall.callring.application.coordination.BusinessRevisionCoordinator,
    val store: StorageResources,
    private val ui: UiBridgeAssembly,
    private val ensureOpen: () -> Unit,
    private val operationActive: () -> Boolean,
    private val persistenceActive: () -> Boolean,
    private val recordings: () -> RecordingAssembly,
    private val conversation: () -> ConversationAssembly,
    private val update: () -> AppUpdateApi,
) : AutoCloseable {
    val playbackState = PlaybackStateTracker { coordinator.publishRuntime(setOf("runtime:ringtonePlayback")) }
    val playbackCommands by lazy {
        ensureOpen()
        AndroidPlaybackCommands(application)
    }
    val callLogSource by lazy {
        ensureOpen()
        AndroidCallLogSource(application.contentResolver)
    }
    private val diagnosticsDelegate = lazy {
        ensureOpen()
        AndroidDiagnosticsStore(
            application.getSharedPreferences(
                configuration.ringPreferencesName,
                Context.MODE_PRIVATE,
            ),
            configuration.clock,
            coordinator,
        )
    }
    val diagnostics
        get() = diagnosticsDelegate.value

    private val audioDelegate = lazy {
        ensureOpen()
        AndroidAudioController(
            application,
            application.getSharedPreferences(
                configuration.ringPreferencesName,
                Context.MODE_PRIVATE,
            ),
            { configuration.audioDirectory ?: application.filesDir },
            documents,
        )
    }
    val audio
        get() =
            run {
                ensureOpen()
                audioDelegate.value
            }

    val documents by lazy {
        ensureOpen()
        com.greatwall.callring.application.runtime.ReadbackDocumentStore(AndroidDocumentStore(application.contentResolver))
    }
    val syncScheduler by lazy {
        ensureOpen()
        AndroidSyncScheduler(application)
    }
    val preferenceStore by lazy {
        AndroidPreferences(application.getSharedPreferences(configuration.appearancePreferencesName,Context.MODE_PRIVATE),application.getSharedPreferences(configuration.widgetPreferencesName,Context.MODE_PRIVATE),configuration.defaultAppearance)
    }
    private val backendDelegate = lazy {
        ensureOpen()
        CallringBackend(
            preferenceStore,
            documents,
            AndroidRuntimeProbe(application, playbackState, diagnostics, configuration.clock) {
                ui.installedWidgets()
            },
            audio,
            syncScheduler,
            configuration.clock,
            configuration.dispatcher,
            HolidayCalendarSource(configuration.clock),
            coordinator,
            store,
            object : com.greatwall.callring.application.callers.NumberDeletionRecordings {
                override suspend fun archives(number: String) = recordings().service.numberArchives(number)
                override suspend fun delete(callId: Long, expectedGeneration: Long, command: CommandContext) = recordings().service.delete(callId, expectedGeneration, command)
            },
            { recordings().service.getArchive(it) },
            operationActive,
            conversationProvider = { conversation().service },
            aiSettingsProvider = { command -> if (command == null) recordings().aiSettings else recordings().aiSettings.bound(command) },
            updateProvider = { update() },
            snapshotsProvider = { conversation().snapshots },
            recordingsProvider = { command -> if (command == null) recordings().service else recordings().service.bound(command) }, persistenceActive = persistenceActive)
    }
    val backend: CallringBackend
        get() =
            run {
                ensureOpen()
                backendDelegate.value
            }


    override fun close() {
        try {
            if (audioDelegate.isInitialized()) audioDelegate.value.close()
        } finally {
            try { if (diagnosticsDelegate.isInitialized()) diagnosticsDelegate.value.close() }
            finally { playbackState.clear() }
        }
    }
}
