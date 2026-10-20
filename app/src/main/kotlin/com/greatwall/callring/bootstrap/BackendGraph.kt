package com.greatwall.callring.bootstrap

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.greatwall.callring.application.CallringBackend
import com.greatwall.callring.application.api.*
import com.greatwall.callring.data.SqliteBackendStore
import com.greatwall.callring.platform.*
import com.greatwall.callring.platform.calling.AndroidCallWorkLease
import com.greatwall.callring.platform.calling.BackendIncomingCallEngine
import com.greatwall.callring.platform.calling.IncomingCallCoordinator
import java.io.Closeable
import com.greatwall.callring.platform.ai.AndroidRecordingRuntime
import com.greatwall.callring.application.RecordingCall
import com.greatwall.callring.application.RecordingCallLink
import com.greatwall.callring.application.IncomingRecordingSession
import com.greatwall.callring.application.resolveRecordingCall
import com.greatwall.callring.RecordingJobService
import com.greatwall.callring.application.SharedBusinessCommands
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.platform.ai.CompatibleConversationClient

/**
 * Composition only. No database, media decoder or document is opened by constructing this graph.
 */
class BackendGraph(
    context: Context,
    val configuration: BackendConfiguration,
    val notifications: UiNotificationEntryPoints,
    val incomingCallUi: IncomingCallUiSink,
) : Closeable {
    val coordinator = com.greatwall.callring.application.BusinessRevisionCoordinator()
    private val application = context.applicationContext
    private val lifetime = Any()
    // Scheduler binder calls never hold the lock used by Telecom's graph getters.
    private val recoveryLifetime = Any()
    private val callWorkLease = AndroidCallWorkLease(application)
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
            coordinator,
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
        com.greatwall.callring.application.ReadbackDocumentStore(AndroidDocumentStore(application.contentResolver))
    }
    val syncScheduler by lazy {
        ensureOpen()
        AndroidSyncScheduler(application)
    }
    private val preferenceStore by lazy {
        AndroidPreferences(application.getSharedPreferences(configuration.appearancePreferencesName,Context.MODE_PRIVATE),application.getSharedPreferences(configuration.widgetPreferencesName,Context.MODE_PRIVATE),configuration.defaultAppearance,coordinator)
    }
    private fun installedWidgets()=WidgetInstallState(android.appwidget.AppWidgetManager.getInstance(application).getAppWidgetIds(android.content.ComponentName(application,com.greatwall.callring.ui.widget.CallringWidgetReceiver::class.java)).toList(),AccessState.GRANTED)
    private val backendDelegate = lazy {
        ensureOpen()
        CallringBackend(
            store,
            preferenceStore,
            documents,
            AndroidRuntimeProbe(application, playbackState, diagnostics, configuration.clock) {
                installedWidgets()
            },
            audio,
            syncScheduler,
            configuration.clock,
            configuration.dispatcher,
            HolidayCalendarSource(configuration.clock),
            coordinator,
        )
    }
    val backend: CallringBackend
        get() =
            synchronized(lifetime) {
                ensureOpen()
                backendDelegate.value
            }

    val recordingSession = IncomingRecordingSession()
    private val recordingDelegate = lazy {
        ensureOpen()
        AndroidRecordingRuntime(application, configuration.databaseName,
            findCall = { id -> store.read { state -> resolveRecordingCall(state.calls.values.map {
                RecordingCallLink(it.id, it.number, it.time, it.duration, it.hidden, it.linkedScreenId)
            }, id) } },
            activeCallId = { recordingSession.activeCandidate(configuration.clock.millis()) },
            schedule = { delay -> RecordingJobService.schedule(application, delay) }, coordinator = coordinator)
    }
    val recordings get() = recordingDelegate.value
    private val resources = ForegroundResourceRegistry(configuration.clock)
    private val queriesDelegate=lazy {scopedQueryApi(store.scopedReads(coordinator),{recordings},{api},{preferenceStore},{diagnostics},com.greatwall.callring.platform.ai.SystemRecordingSource(application.contentResolver),coordinator,resources,playbackState::snapshot,::installedWidgets,{
        val scheduler=application.getSystemService(android.app.job.JobScheduler::class.java)
        if(scheduler==null) aiObject("available" to aiBool(false)) else aiObject("available" to aiBool(true),"pending" to AiJson.Arr(scheduler.allPendingJobs.filter{it.service.packageName==application.packageName}.map{aiObject("service" to aiString(it.service.className),"jobId" to aiNumber(it.id.toLong()))}))
    },configuration.clock)}
    val queries:QueryService get()=queriesDelegate.value
    /** Safe aggregate counters; reading them neither opens a store nor probes platform state. */
    fun businessReadCounters():Map<String,Long> = mapOf("coreStateLoads" to store.stateLoadCount(),"completeCaptures" to store.completeCaptures.get(),"sourceQueries" to if(queriesDelegate.isInitialized())queries.sourceQueries.get()else 0L,"classifications" to com.greatwall.callring.domain.WorkClassifier.evaluations.get(),"runtimeProbes" to AndroidRuntimeProbe.observations.get())
    private val snapshotsDelegate = lazy { ensureOpen(); completeSnapshotApi(backend, recordings, diagnostics, coordinator, resources, configuration.clock) }
    private val conversationDelegate = lazy { ensureOpen(); ConversationService({ api }, queries, { recordings.store.settings() }, coordinator, { CompatibleConversationClient() }, resources = resources,documentWriteVerified={documents.verifyWrite(it)}) }
    private val updateDelegate = lazy { ensureOpen(); com.greatwall.callring.platform.update.AndroidAppUpdate(application) }
    /** 更新平台实现；构造不触网，首次访问才读取 PackageManager。 */
    val update: AppUpdateApi
        get() = synchronized(lifetime) {
            ensureOpen()
            updateDelegate.value
        }
    private val apiDelegate by lazy {
        object : CallringUiApi by backend {
            override val recordings: RecordingApi get() = this@BackendGraph.recordings
            override val aiSettings: AiSettingsApi get() = this@BackendGraph.recordings.aiSettings
            override val numberDeletion: NumberDeletionApi get() =
                com.greatwall.callring.platform.callers.NumberDeletionService(backend.callers, this@BackendGraph.recordings, backend.widgets)
            override val snapshots: AiSnapshotApi get() = snapshotsDelegate.value
            override val conversation: ConversationApi get() = conversationDelegate.value
            override val update: AppUpdateApi get() = this@BackendGraph.update
            override fun commandApi(context: CommandContext): CallringUiApi = boundApi(context)
            override suspend fun adoptIdentity(callId: Long, expectedGeneration: Long): ApiResult<CallerProfile> = boundApi(CommandContext(java.util.UUID.randomUUID().toString(), coordinator.capture(), 0) { !closed }).adoptIdentity(callId, expectedGeneration)
        }
    }
    private fun boundApi(command: CommandContext): CallringUiApi = object : CallringUiApi by backend.commandApi(command) {
        override val recordings: RecordingApi get() = this@BackendGraph.recordings.bound(command)
        override val aiSettings: AiSettingsApi get() = this@BackendGraph.recordings.aiSettings.bound(command)
        override val numberDeletion: NumberDeletionApi
            get() {
                val deletionCommand = command.copy(continueOwnCommits = true)
                val boundBackend = backend.commandApi(deletionCommand)
                return com.greatwall.callring.platform.callers.NumberDeletionService(
                    boundBackend.callers,
                    this@BackendGraph.recordings.bound(deletionCommand),
                    boundBackend.widgets,
                )
            }
        override val snapshots: AiSnapshotApi get() = snapshotsDelegate.value
        override val conversation: ConversationApi get() = conversationDelegate.value
        override val update: AppUpdateApi get() = this@BackendGraph.update
        override suspend fun adoptIdentity(callId: Long, expectedGeneration: Long) = SharedBusinessCommands(this).adoptIdentity(callId, expectedGeneration)
    }
    val api: CallringUiApi get() = apiDelegate

    private val incomingDelegate = lazy {
        ensureOpen()
        IncomingCallCoordinator(
            BackendIncomingCallEngine(backend),
            incomingCallUi,
            workLease = { callWorkLease.acquire() },
            onIncoming = { sessionId, number, at, account ->
                recordings.observeIncoming(sessionId, number, at, account)?.let { call ->
                    recordingSession.register(call.id, call.startedAt)
                    // A fast answer can precede background persistence. Recheck this same live
                    // session once its independent identity is durable; IDLE clears it first.
                    if (recordingSession.activeCandidate(configuration.clock.millis()) == call.id)
                        com.greatwall.callring.CallCaptureService.phoneState(application, true)
                }
            },
            onStatisticsRecorded = { sessionId, id -> recordings.linkStatistics(sessionId, id) },
            afterCompletion = {
                synchronized(recoveryLifetime) {
                    if (
                        !closed &&
                            application.checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
                                PackageManager.PERMISSION_GRANTED
                    ) {
                        syncScheduler.requestSync(configuration.clock.millis())
                    }
                }
            },
        ) {
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

    /** Drains asynchronous writes and closes persistence; call from a background thread. */
    override fun close() {
        val resources =
            synchronized(recoveryLifetime) {
                synchronized(lifetime) {
                    if (closed) return
                    closed = true
                    Triple(
                        if (incomingDelegate.isInitialized()) incomingDelegate.value else null,
                        if (audioDelegate.isInitialized()) audioDelegate.value else null,
                        if (diagnosticsDelegate.isInitialized()) diagnosticsDelegate.value else null,
                    )
                }
            }
        // The coordinator drains persistence work. Its callbacks may need graph-owned diagnostics;
        // never wait for its executors while holding the graph's lifetime lock.
        if(conversationDelegate.isInitialized()) conversationDelegate.value.close()
        if(updateDelegate.isInitialized()) updateDelegate.value.close()
        try {
            resources.first?.close()
        } finally {
            try {
                resources.second?.close()
            } finally {
                try {
                    resources.third?.close()
                } finally {
                    if (recordingDelegate.isInitialized()) recordingDelegate.value.close()
                    playbackState.clear()
                    store.close()
                }
            }
        }
    }
}
