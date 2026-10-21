package com.greatwall.callring.bootstrap

import com.greatwall.callring.application.conversation.context.SecretRedactor
import com.greatwall.callring.application.conversation.foreground.ForegroundResourceRegistry
import com.greatwall.callring.application.conversation.query.*
import com.greatwall.callring.application.ports.query.ScopedReadPort
import com.greatwall.callring.application.coordination.BusinessRevisionCoordinator
import com.greatwall.callring.contracts.runtime.PlaybackStatus
import com.greatwall.callring.contracts.widget.WidgetInstallState
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.CallringUiApi
import com.greatwall.callring.data.query.LazyScopedReadPort
import com.greatwall.callring.data.query.ValueScopedReadSource
import com.greatwall.callring.data.preferences.AndroidPreferences
import com.greatwall.callring.platform.diagnostics.AndroidDiagnosticsStore
import com.greatwall.callring.platform.recording.SystemRecordingSource
import java.time.Clock

/** Register lazy source adapters. Constructing this registry performs no business source reads. */
fun scopedQueryApi(core:ScopedReadPort,recordings:()->RecordingAssembly,api:()->CallringUiApi,preferences:()->AndroidPreferences,diagnostics:()->AndroidDiagnosticsStore,directory:SystemRecordingSource,coordinator:BusinessRevisionCoordinator,resources:ForegroundResourceRegistry,playback:()->PlaybackStatus,installed:()->WidgetInstallState,jobs:()->ScheduledJobsObservation,clock:Clock):QueryService {
    val recordingSource = LazyScopedReadPort(setOf(AiDomain.D15, AiDomain.D16), coordinator::sourceVersion) { recordings().store.scopedReads() }
    val appearance = AppearanceQueryProjection { preferences().getAppearance() }
    val ringtone = RingtoneQueryProjection({ api().ringtone.getSettings() }, playback)
    val recordingSettings = RecordingSettingsQueryProjection { recordings().aiSettings.getRecordingView() }
    val aiSettings = AiSettingsQueryProjection { recordings().aiSettings.getAiView() }
    val widgets = WidgetQueryProjection({ preferences().scopedBindings() }, installed)
    val runtime = RuntimeQueryProjection({ api().runtime.getStatus() },
        { recordings().service.playbackObservation() }, { recordings().workflow.activityObservation() }, jobs)
    val views = listOf(
        ValueScopedReadSource(setOf(AiDomain.D11), coordinator::sourceVersion, ::queryConsistency, appearance::rows),
        ValueScopedReadSource(setOf(AiDomain.D12), coordinator::sourceVersion, ::queryConsistency, ringtone::rows),
        ValueScopedReadSource(setOf(AiDomain.D13), coordinator::sourceVersion, ::queryConsistency, recordingSettings::rows),
        ValueScopedReadSource(setOf(AiDomain.D14), coordinator::sourceVersion, ::queryConsistency, aiSettings::rows),
        ValueScopedReadSource(setOf(AiDomain.D18), coordinator::sourceVersion, ::queryConsistency, widgets::rows),
        ValueScopedReadSource(setOf(AiDomain.D19), coordinator::sourceVersion, ::queryConsistency, runtime::rows),
    )
    val diagnosticSource = LazyScopedReadPort(setOf(AiDomain.D20), coordinator::sourceVersion) { diagnostics().scopedReads() }
    val sources = listOf(core, recordingSource) + views + listOf(diagnosticSource,
        directory.scopedReads({ recordings().store.settings().systemFolder }, { coordinator.sourceVersion(AiDomain.D17) }, resources))
    require(sources.flatMap { it.domains }.toSet() == AiDomain.entries.toSet())
    return QueryService(sources,clock::millis,coordinator::marker){recordings().store.settings().let{SecretRedactor(listOf(it.analysis.apiKey,it.transcription.apiKey))}}
}
