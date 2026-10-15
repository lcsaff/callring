package com.greatwall.callring.bootstrap

import android.content.Context
import com.greatwall.callring.application.CallringBackend
import com.greatwall.callring.application.api.*
import com.greatwall.callring.data.SqliteBackendStore
import com.greatwall.callring.platform.*
import com.greatwall.callring.platform.calling.BackendIncomingCallEngine
import com.greatwall.callring.platform.calling.IncomingCallCoordinator
import java.io.Closeable

/**
 * Composition only. No database, media decoder or document is opened by constructing this graph.
 */
class BackendGraph(
    context: Context,
    val configuration: BackendConfiguration,
    val notifications: UiNotificationEntryPoints,
    val incomingCallUi: IncomingCallUiSink,
) : Closeable {
    private val application = context.applicationContext
    private val lifetime = Any()
    @Volatile private var closed = false
    private val store = LazyBackendStore {
        SqliteBackendStore(application, configuration.databaseName)
    }
    val playbackState = PlaybackStateTracker()
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
            synchronized(lifetime) {
                ensureOpen()
                audioDelegate.value
            }

    private val documents by lazy {
        ensureOpen()
        AndroidDocumentStore(application.contentResolver)
    }
    val syncScheduler by lazy {
        ensureOpen()
        AndroidSyncScheduler(application)
    }
    private val backendDelegate = lazy {
        ensureOpen()
        CallringBackend(
            store,
            AndroidPreferences(
                application.getSharedPreferences(
                    configuration.appearancePreferencesName,
                    Context.MODE_PRIVATE,
                ),
                application.getSharedPreferences(
                    configuration.widgetPreferencesName,
                    Context.MODE_PRIVATE,
                ),
                configuration.defaultAppearance,
            ),
            documents,
            AndroidRuntimeProbe(application, playbackState, diagnostics, configuration.clock),
            audio,
            syncScheduler,
            configuration.clock,
            configuration.dispatcher,
        )
    }
    val backend: CallringBackend
        get() =
            synchronized(lifetime) {
                ensureOpen()
                backendDelegate.value
            }

    val api: CallringUiApi
        get() = backend

    private val incomingDelegate = lazy {
        ensureOpen()
        IncomingCallCoordinator(BackendIncomingCallEngine(backend), incomingCallUi) {
            diagnostics.enqueue(DiagnosticEvent.CALL_RECORD_FAILED)
        }
    }
    val incomingCalls: IncomingCallCoordinator
        get() =
            synchronized(lifetime) {
                ensureOpen()
                incomingDelegate.value
            }

    private fun ensureOpen() {
        check(!closed) { "backend.closed" }
    }

    override fun close() {
        val resources =
            synchronized(lifetime) {
                if (closed) return
                closed = true
                Triple(
                    if (incomingDelegate.isInitialized()) incomingDelegate.value else null,
                    if (audioDelegate.isInitialized()) audioDelegate.value else null,
                    if (diagnosticsDelegate.isInitialized()) diagnosticsDelegate.value else null,
                )
            }
        // The coordinator drains persistence work. Its callbacks may need graph-owned diagnostics;
        // never wait for its executors while holding the graph's lifetime lock.
        try {
            resources.first?.close()
        } finally {
            try {
                resources.second?.close()
            } finally {
                try {
                    resources.third?.close()
                } finally {
                    playbackState.clear()
                    store.close()
                }
            }
        }
    }
}
