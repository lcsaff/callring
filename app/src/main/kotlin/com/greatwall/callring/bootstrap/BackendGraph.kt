package com.greatwall.callring.bootstrap

import android.content.Context
import com.greatwall.callring.application.CallringBackend
import com.greatwall.callring.application.IncomingRecordingSession
import com.greatwall.callring.application.findRecordingCall
import com.greatwall.callring.application.conversation.QueryService
import com.greatwall.callring.contracts.CallringUiApi
import com.greatwall.callring.contracts.callers.IncomingCallUiSink
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.data.SqliteBackendStore
import com.greatwall.callring.platform.*
import com.greatwall.callring.platform.calling.AndroidCallWorkLease
import com.greatwall.callring.platform.calling.BackendIncomingCallEngine
import com.greatwall.callring.platform.calling.IncomingCallCoordinator
import com.greatwall.callring.RecordingJobService
import java.io.Closeable

/** Total assembly and lifetime only; construction does not open persistence or media. */
class BackendGraph(
    context: Context,
    val configuration: BackendConfiguration,
    notifications: UiNotificationEntryPoints,
    incomingCallUi: IncomingCallUiSink,
) : Closeable {
    val coordinator = com.greatwall.callring.application.BusinessRevisionCoordinator()
    private val application = context.applicationContext
    private val lifetime = Any()
    private val recoveryLifetime = Any()
    @Volatile private var closed = false
    @Volatile private var persistenceClosed = false
    private val ui = UiBridgeAssembly(application, notifications, incomingCallUi)
    val notifications get() = ui.notifications
    val incomingCallUi get() = ui.incomingCallUi
    private val store = StorageResources {
        SqliteBackendStore(application, configuration.databaseName, configuration.sqlAccessProbe)
    }
    private val core: CoreAssembly = CoreAssembly(application, configuration, coordinator, store, ui,
        ::ensureOpen, { !closed }, { !persistenceClosed }, { recordings }, { conversation }, { update })
    private val conversation: ConversationAssembly = ConversationAssembly(application, configuration, core,
        { recordings }, ui, { api }, ::ensureOpen)
    internal val fullSnapshotReader: com.greatwall.callring.application.ports.store.FullSnapshotReader get() = store
    val playbackState get() = core.playbackState
    val playbackCommands get() = core.playbackCommands
    val callLogSource get() = core.callLogSource
    val diagnostics get() = core.diagnostics
    val audio get() = synchronized(lifetime) { ensureOpen(); core.audio }
    val syncScheduler get() = core.syncScheduler
    val backend: CallringBackend get() = synchronized(lifetime) { ensureOpen(); core.backend }
    val recordingSession = IncomingRecordingSession()
    private val recordingDelegate = lazy {
        ensureOpen()
        RecordingAssembly(application, configuration.databaseName,
            findCall = { id -> findRecordingCall(store, id) },
            activeCallId = { recordingSession.activeCandidate(configuration.clock.millis()) },
            schedule = { delay -> RecordingJobService.schedule(application, delay) }, coordinator = coordinator, operationActive = { !closed })
    }
    val recordings get() = recordingDelegate.value
    val queries: QueryService get() = conversation.queries
    fun businessReadCounters(): Map<String, Long> = mapOf(
        "coreOpens" to store.coreOpens.get(), "coreStateLoads" to store.stateLoadCount(),
        "completeCaptures" to store.completeCaptures.get(), "sourceQueries" to conversation.sourceQueries(),
        "classifications" to com.greatwall.callring.domain.WorkClassifier.evaluations.get(),
        "runtimeProbes" to AndroidRuntimeProbe.observations.get(), "quietReads" to 0L, "quietWrites" to 0L,
    ) + store.narrowAccessCounts()
    private val updateDelegate = lazy { ensureOpen(); com.greatwall.callring.platform.update.AndroidAppUpdate(application) }
    val update: AppUpdateApi get() = synchronized(lifetime) { ensureOpen(); updateDelegate.value }
    val api: CallringUiApi get() = backend
    private val callWorkLease = AndroidCallWorkLease(application)
    private val recovery = com.greatwall.callring.platform.calling.AndroidIncomingRecovery(
        application, configuration.clock, { syncScheduler }, { !closed }, recoveryLifetime)
    private val incomingDelegate = lazy {
        ensureOpen()
        val recording = recordings
        val engine = BackendIncomingCallEngine(backend::decisionSnapshot, backend::refreshDecisionSnapshot,
            backend::recordIncoming, backend::updateDecision)
        com.greatwall.callring.platform.calling.IncomingCallController(engine, ui.incomingCallUi,
            { callWorkLease.acquire() },
            { id, number, at, account -> recording.service.observeIncoming(id, number, at, account) },
            { id, call -> recording.service.linkStatistics(id, call) }, recordingSession, configuration.clock,
            { com.greatwall.callring.CallCaptureService.phoneState(application, true) },
            recovery::recover, { diagnostics.enqueue(DiagnosticEvent.CALL_RECORD_FAILED) }).coordinator
    }
    val incomingCalls: IncomingCallCoordinator get() = synchronized(lifetime) { ensureOpen(); incomingDelegate.value }
    private fun ensureOpen() { check(!closed) { "backend.closed" } }
    override fun close() {
        val incoming = synchronized(recoveryLifetime) {
            synchronized(lifetime) {
                if (closed) return
                closed = true
                if (incomingDelegate.isInitialized()) incomingDelegate.value else null
            }
        }
        try {
            try { conversation.close() }
            finally { if (updateDelegate.isInitialized()) updateDelegate.value.close() }
        } finally {
            try { incoming?.close() } finally {
                persistenceClosed = true
                try { core.close() } finally {
                    try { if (recordingDelegate.isInitialized()) recordingDelegate.value.close() }
                    finally { store.close() }
                }
            }
        }
    }
}
