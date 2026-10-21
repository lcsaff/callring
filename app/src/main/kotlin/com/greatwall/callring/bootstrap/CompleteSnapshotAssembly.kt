package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.CallringBackend
import com.greatwall.callring.application.conversation.foreground.ForegroundResourceRegistry
import com.greatwall.callring.application.conversation.query.*
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.application.ports.store.FullSnapshotReader
import com.greatwall.callring.contracts.ai.AiSnapshotApi
import com.greatwall.callring.platform.diagnostics.AndroidDiagnosticsStore
import java.time.Clock

fun completeSnapshotApi(
    backend: CallringBackend,
    fullReader: FullSnapshotReader,
    recordings: RecordingAssembly,
    diagnostics: AndroidDiagnosticsStore,
    coordinator: BusinessRevisionCoordinator,
    resources: ForegroundResourceRegistry,
    clock: Clock,
): AiSnapshotApi = AiSnapshotService(
    coordinator,
    listOf(
        CoreCompleteSnapshotSource(fullReader, { backend.runtime.getStatus() },
            { backend.settings.getAppearance() }, { backend.ringtone.getSettings() })::capture,
        RecordingTextCompleteSnapshotSource({ recordings.store.captureRecordingText() })::capture,
        RecordingSettingsCompleteSnapshotSource({ recordings.aiSettings.getRecordingView() },
            { recordings.aiSettings.getAiView() })::capture,
        DirectoryCompleteSnapshotSource({ recordings.store.settings().systemFolder != null },
            recordings::folderAccess, { recordings.service.systemRecordings() }, resources)::capture,
        ObservedCompleteSnapshotSource({ backend.widgets.listBindings() }, { backend.runtime.getStatus() },
            { recordings.service.playbackStatus() }, diagnostics::captureStoredText)::capture,
    ),
    { recordings.store.settings().let { listOf(it.analysis.apiKey, it.transcription.apiKey) } },
    clock,
)
