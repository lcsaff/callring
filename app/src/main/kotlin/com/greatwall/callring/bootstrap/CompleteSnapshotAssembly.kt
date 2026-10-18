package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.*
import com.greatwall.callring.application.api.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.platform.AndroidDiagnosticsStore
import com.greatwall.callring.platform.ai.AndroidRecordingRuntime
import java.time.Clock
import kotlinx.coroutines.*

fun completeSnapshotApi(backend: CallringBackend, recordings: AndroidRecordingRuntime, diagnostics: AndroidDiagnosticsStore, coordinator: BusinessRevisionCoordinator, resources: ForegroundResourceRegistry, clock: Clock): AiSnapshotApi {
    suspend fun <T> source(ids: String, block: suspend () -> T): T = try { block() }
        catch(e: CancellationException) { throw e }
        catch(e: BackendFailure) { throw BackendFailure(e.error.copy(fieldErrors = e.error.fieldErrors + ("domains" to ids))) }
        catch(_: Exception) { throw BackendFailure(ApiError(ErrorCode.IO_FAILURE, "ai.snapshot_domain_failed", fieldErrors = mapOf("domains" to ids), retryable = true)) }
    return AiSnapshotService(coordinator, listOf(
        { at -> source("D01-D12,D21") { backend.captureCore(at).requiredValue().mapValues { (id, data) -> SnapshotPart(data, count = sourceCount(id, data)) } } },
        { _ -> source("D15,D16") { withContext(Dispatchers.IO) { recordings.store.captureRecordingText().mapValues { (id, data) -> SnapshotPart(data, count = sourceCount(id, data), revisionDomain = "recording") } } } },
        { _ -> source("D13,D14") {
            mapOf("D13" to SnapshotPart(safeValue(recordings.aiSettings.getRecordingView().requiredValue()), revisionDomain = "recordingConfig"),
                "D14" to SnapshotPart(safeValue(recordings.aiSettings.getAiView().requiredValue()), revisionDomain = "aiConfig"))
        } },
        { _ -> source("D17") {
            val configured = withContext(Dispatchers.IO) { recordings.store.settings().systemFolder != null }
            if(configured && recordings.folderAccess() != AccessState.GRANTED) throw BackendFailure(ApiError(ErrorCode.PERMISSION_REQUIRED, "recording.permission_required"))
            val files = recordings.systemRecordings().requiredValue()
            mapOf("D17" to SnapshotPart(resources.project(files), if(configured) SnapshotDomainStatus.COMPLETE else SnapshotDomainStatus.NOT_CONFIGURED, "authorized_tree_stable_observation", files.size.toLong(), "recordingConfig"))
        } },
        { _ -> source("D18-D20") {
            mapOf("D18" to SnapshotPart(safeValue(backend.widgets.listBindings().requiredValue()), revisionDomain = "preferences"),
                "D19" to SnapshotPart(safeValue(mapOf("runtime" to backend.runtime.getStatus().requiredValue(), "recordingPlayback" to recordings.playbackStatus())), scope = "live_probe"),
                "D20" to SnapshotPart(withContext(Dispatchers.IO) { safeValue(diagnostics.captureStoredText()) }, revisionDomain = "diagnostics"))
        } },
    ), { recordings.store.settings().let { listOf(it.analysis.apiKey, it.transcription.apiKey) } }, clock)
}

private fun sourceCount(id: String, data: AiJson): Long {
    fun rows(key: String): Long = when (val child = (data as? AiJson.Obj)?.fields?.get(key)) {
        is AiJson.Arr -> child.values.size.toLong()
        is AiJson.Obj -> child.fields.size.toLong()
        else -> 0L
    }
    return when (id) {
        "D02" -> rows("options")
        "D05" -> rows("intervals") + rows("numbers")
        "D06" -> rows("history")
        "D07" -> rows("years") + rows("holidays") + rows("makeupDates")
        "D09" -> rows("entries")
        "D15" -> rows("events") + rows("links") + rows("deleted")
        else -> (data as? AiJson.Arr)?.values?.size?.toLong() ?: 1L
    }
}
