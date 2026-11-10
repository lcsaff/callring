package com.greatwall.callring.ui.app

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.feature.recording.*
import com.greatwall.callring.ui.feature.callers.*
import com.greatwall.callring.ui.feature.ai.ScopedRecordingSettingsViewModel

@Composable internal fun CallHistoryRoute(api: CallringUiApi, onBack: () -> Unit,
    onArchives: () -> Unit, onDetail: (Long) -> Unit) {
    val model: CallHistoryViewModel = uiViewModel { CallHistoryViewModel(api.callers, api.recordings, api.changes) }
    val state by model.state.collectAsStateWithLifecycle()
    val playing by model.playback.state.collectAsStateWithLifecycle()
    val playback by model.playback.mutation.collectAsStateWithLifecycle()
    val transcript by model.transcript.collectAsStateWithLifecycle()
    val selection by model.selectedTranscript.collectAsStateWithLifecycle()
    DisposableEffect(model) { onDispose { model.playback.stopForExit() } }
    CallHistoryScreen(state, playing, playback, onBack, model::refresh, model::more, onArchives, onDetail,
        model.playback::toggle, model::openTranscript)
    selection?.let { CallHistoryTranscriptDialog(it, transcript, model::retryTranscript, model::closeTranscript) }
}

@Composable internal fun RecordingArchivesRoute(api: CallringUiApi, signal: Long, onBack: () -> Unit,
    onArchive: (ArchiveListItem) -> Unit) {
    val model: RecordingArchivesViewModel = uiViewModel { RecordingArchivesViewModel(api.recordings, changes = api.changes) }
    val state by model.state.collectAsStateWithLifecycle()
    val paging by model.paging.collectAsStateWithLifecycle()
    val timeZone: RecordingTimeZoneViewModel = uiViewModel { RecordingTimeZoneViewModel(api.settings, api.changes) }
    val zone by timeZone.state.collectAsStateWithLifecycle()
    RecordingArchivesScreen(state, onBack, model::refresh, onArchive, zone, timeZone::refresh, paging, model::more)
}

@Composable internal fun RecordingSettingsRoute(api: CallringUiApi, onBack: () -> Unit) {
    val model: ScopedRecordingSettingsViewModel = uiViewModel { ScopedRecordingSettingsViewModel(api) }
    val state by model.state.collectAsStateWithLifecycle()
    val mutation by model.mutation.collectAsStateWithLifecycle()
    val importDaysDraft by model.importDaysDraft.collectAsStateWithLifecycle()
    var permissionError by remember { mutableStateOf<String?>(null) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.folder(DocumentRef(uri.toString()))
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.captureMode(CaptureMode.MICROPHONE_EXPERIMENTAL)
        else permissionError = "未获得麦克风权限；录音模式未改变"
    }
    RecordingSettingsScreen(state, mutation, onBack, model::refresh, model::enabled, model::captureMode,
        { try { folder.launch(null) } catch (_: ActivityNotFoundException) { permissionError = "系统没有可用的目录选择器" } },
        { microphone.launch(Manifest.permission.RECORD_AUDIO) }, importDaysDraft, model::systemImportDays)
    permissionError?.let { message -> androidx.compose.material3.AlertDialog(onDismissRequest = { permissionError = null },
        title = { androidx.compose.material3.Text("权限提示") }, text = { androidx.compose.material3.Text(message) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { permissionError = null }) { androidx.compose.material3.Text("知道了") } }) }
}

@Composable internal fun ArchiveRoute(api: CallringUiApi, callId: Long, number: String?, startedAt: Long?,
    onBack: () -> Unit, onEdit: (String) -> Unit, historyDetail: Boolean = false) {
    val model: ArchiveViewModel = uiViewModel {
        ArchiveViewModel(api.recordings, callId, number, startedAt, changes = api.changes,
            callers = if (historyDetail) api.callers else null)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val files by model.files.collectAsStateWithLifecycle()
    val mutation by model.mutation.collectAsStateWithLifecycle()
    val playback by model.playback.mutation.collectAsStateWithLifecycle()
    val playing by model.playback.state.collectAsStateWithLifecycle()
    val suggestions by model.suggestions.state.collectAsStateWithLifecycle()
    val timeZone: RecordingTimeZoneViewModel = uiViewModel { RecordingTimeZoneViewModel(api.settings, api.changes) }
    val zone by timeZone.state.collectAsStateWithLifecycle()
    val picker = rememberImportPicker(arrayOf("audio/*")) { model.import(it) }
    val offlinePicker = rememberImportPicker(arrayOf("audio/*")) { model.import(it, offlineOnly = true) }
    DisposableEffect(model) { onDispose { model.playback.stopForExit() } }
    val isPlaying = playing.data?.callId?.let {
        it == callId || it == state.data?.callId || it == history.data?.linkedScreeningCallId
    } == true
    ArchiveScreen(state, files, mutation, onBack, model::refresh, picker, model::listSystem,
        { model.import(DocumentRef(it.uri), RecordingSource.SYSTEM) }, model::retry, model::delete,
        model::togglePlayback, model::togglePlayback,
        { (history.data?.call?.number ?: number)?.let(onEdit) }, zone, timeZone::refresh,
        offlinePicker, model::transcribeOffline, model::cancelOffline,
        history = if (historyDetail) history else null, isPlaying = isPlaying,
        playback = playback, playbackError = playing.error,
        onAnalyze = model::analyze, suggestions = suggestions, onRetrySuggestions = model.suggestions::refresh,
        onConfirmSuggestions = model.suggestions::confirm)
}
