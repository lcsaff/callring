package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.conversation.execution.ConversationService
import com.greatwall.callring.application.conversation.foreground.ForegroundResourceRegistry
import com.greatwall.callring.application.conversation.query.QueryService

import android.content.Context
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.contracts.CallringUiApi
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.platform.ai.CompatibleConversationClient

/** Owns query sources, snapshot composition, foreground leases and conversation lifetime. */
internal class ConversationAssembly(
    private val application: Context,
    private val configuration: BackendConfiguration,
    private val core: CoreAssembly,
    private val recordings: () -> RecordingAssembly,
    private val ui: UiBridgeAssembly,
    private val api: () -> CallringUiApi,
    private val ensureOpen: () -> Unit,
) : AutoCloseable {
    private val coordinator get() = core.coordinator
    private val resources = ForegroundResourceRegistry(configuration.clock)
    private val queriesDelegate = lazy {
        ensureOpen()
        scopedQueryApi(core.store.scopedReads(coordinator), recordings, api,
            { core.preferenceStore }, { core.diagnostics },
            com.greatwall.callring.platform.recording.SystemRecordingSource(application.contentResolver),
            coordinator, resources, core.playbackState::snapshot, ui::installedWidgets,
            com.greatwall.callring.platform.scheduler.AndroidJobObservation(application)::observe, configuration.clock)
    }
    val queries: QueryService get() = queriesDelegate.value
    fun sourceQueries() = if (queriesDelegate.isInitialized()) queriesDelegate.value.sourceQueries.get() else 0L
    private val snapshotsDelegate = lazy {
        ensureOpen()
        completeSnapshotApi(core.backend, core.store, recordings(), core.diagnostics,
            coordinator, resources, configuration.clock)
    }
    val snapshots: AiSnapshotApi get() = snapshotsDelegate.value
    private val serviceDelegate = lazy {
        ensureOpen()
        ConversationService(api, queries, { recordings().store.settings() }, coordinator,
            { CompatibleConversationClient() }, resources = resources,
            documentWriteVerified = { core.documents.verifyWrite(it) })
    }
    val service: ConversationService get() = serviceDelegate.value
    override fun close() {
        if (serviceDelegate.isInitialized()) serviceDelegate.value.close()
        else {
            resources.reset()
            if (queriesDelegate.isInitialized()) queriesDelegate.value.reset()
        }
    }
}
