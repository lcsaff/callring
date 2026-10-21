package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.conversation.foreground.ForegroundResourceRegistry
import com.greatwall.callring.application.conversation.query.AiSnapshotService
import com.greatwall.callring.application.conversation.query.SnapshotPart
import com.greatwall.callring.application.conversation.query.safeValue
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.mapping.requiredValue

import com.greatwall.callring.application.*
import com.greatwall.callring.application.conversation.*
import com.greatwall.callring.application.conversation.query.projectCoreSnapshot
import com.greatwall.callring.application.ports.BackendFailure
import com.greatwall.callring.application.ports.store.FullSnapshotReader
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.platform.diagnostics.AndroidDiagnosticsStore
import java.time.Clock
import kotlinx.coroutines.*

fun completeSnapshotApi(
    backend: CallringBackend,
    fullReader: FullSnapshotReader,
    recordings: RecordingAssembly,
    diagnostics: AndroidDiagnosticsStore,
    coordinator: BusinessRevisionCoordinator,
    resources: ForegroundResourceRegistry,
    clock: Clock,
): AiSnapshotApi {
    suspend fun <T> source(ids: String, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendFailure) {
            throw BackendFailure(
                e.error.copy(fieldErrors = e.error.fieldErrors + ("domains" to ids)),
            )
        } catch (_: Exception) {
            throw BackendFailure(
                ApiError(
                    ErrorCode.IO_FAILURE,
                    "ai.snapshot_domain_failed",
                    fieldErrors = mapOf("domains" to ids),
                    retryable = true,
                ),
            )
        }
    return AiSnapshotService(
        coordinator,
        listOf(
            { at ->
                source("D01-D12,D21") {
                    withContext(Dispatchers.IO) {
                            projectCoreSnapshot(
                                fullReader.capture(),
                                at,
                                backend.runtime.getStatus().requiredValue(),
                                backend.settings.getAppearance().requiredValue(),
                                backend.ringtone.getSettings().requiredValue(),
                            )
                        }
                        .mapValues { (id, data) ->
                            SnapshotPart(
                                data,
                                count = sourceCount(id, data),
                                scope =
                                    if (id in setOf("D05", "D06", "D09", "D12")) "live_probe"
                                    else "all_stored",
                            )
                        }
                }
            },
            { _ ->
                source("D15,D16") {
                    withContext(Dispatchers.IO) {
                        recordings.store.captureRecordingText().mapValues { (id, data) ->
                            SnapshotPart(data, count = sourceCount(id, data))
                        }
                    }
                }
            },
            { _ ->
                source("D13,D14") {
                    mapOf(
                        "D13" to
                            SnapshotPart(
                                safeValue(recordings.aiSettings.getRecordingView().requiredValue()),
                                scope = "live_probe",
                            ),
                        "D14" to
                            SnapshotPart(
                                safeValue(recordings.aiSettings.getAiView().requiredValue()),
                            ),
                    )
                }
            },
            { _ ->
                source("D17") {
                    val configured =
                        withContext(Dispatchers.IO) {
                            recordings.store.settings().systemFolder != null
                        }
                    if (configured && recordings.folderAccess() != AccessState.GRANTED)
                        throw BackendFailure(
                            ApiError(
                                ErrorCode.PERMISSION_REQUIRED,
                                "recording.permission_required",
                            ),
                        )
                    val files = recordings.service.systemRecordings().requiredValue()
                    mapOf(
                        "D17" to
                            resources.snapshotPart(
                                files,
                                if (configured) SnapshotDomainStatus.COMPLETE
                                else SnapshotDomainStatus.NOT_CONFIGURED,
                            ),
                    )
                }
            },
            { _ ->
                source("D18-D20") {
                    mapOf(
                        "D18" to
                            SnapshotPart(
                                safeValue(backend.widgets.listBindings().requiredValue()),
                                scope = "live_probe",
                            ),
                        "D19" to
                            SnapshotPart(
                                safeValue(
                                    mapOf(
                                        "runtime" to backend.runtime.getStatus().requiredValue(),
                                        "recordingPlayback" to recordings.service.playbackStatus(),
                                    ),
                                ),
                                scope = "live_probe",
                            ),
                        "D20" to
                            SnapshotPart(
                                withContext(Dispatchers.IO) {
                                    safeValue(diagnostics.captureStoredText())
                                },
                            ),
                    )
                }
            },
        ),
        { recordings.store.settings().let { listOf(it.analysis.apiKey, it.transcription.apiKey) } },
        clock,
    )
}

private fun sourceCount(id: String, data: AiJson): Long {
    fun rows(key: String): Long =
        when (val child = (data as? AiJson.Obj)?.fields?.get(key)) {
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
