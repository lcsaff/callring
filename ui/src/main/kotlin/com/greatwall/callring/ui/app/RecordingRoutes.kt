package com.greatwall.callring.ui.app

import android.Manifest
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.feature.recording.*

@Composable internal fun RecordingArchivesRoute(api: RecordingApi, signal: Long, onBack: () -> Unit,
    onArchive: (CallArchive) -> Unit) {
    val model: RecordingArchivesViewModel = uiViewModel { RecordingArchivesViewModel(api) }
    val state by model.state.collectAsStateWithLifecycle()
    ActiveRefresh(signal, intervalMillis = 3_000, refresh = model::refresh)
    RecordingArchivesScreen(state, onBack, model::refresh, onArchive)
}

@Composable internal fun RecordingSettingsRoute(api: RecordingApi, onBack: () -> Unit) {
    val model: RecordingSettingsViewModel = uiViewModel { RecordingSettingsViewModel(api) }
    val state by model.state.collectAsStateWithLifecycle()
    val draft by model.draft.collectAsStateWithLifecycle()
    val dirty by model.dirty.collectAsStateWithLifecycle()
    val mutation by model.mutation.collectAsStateWithLifecycle()
    var permissionError by remember { mutableStateOf<String?>(null) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.folder(DocumentRef(uri.toString()))
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.draft.value?.let { model.edit(it.copy(captureMode = CaptureMode.MICROPHONE_EXPERIMENTAL)) }
        else permissionError = "未获得麦克风权限；录音模式未改变"
    }
    LaunchedEffect(Unit) { model.refresh() }
    RecordingSettingsScreen(state, draft, dirty, mutation, onBack, model::refresh, model::edit, model::save,
        { try { folder.launch(null) } catch (_: ActivityNotFoundException) { permissionError = "系统没有可用的目录选择器" } },
        { microphone.launch(Manifest.permission.RECORD_AUDIO) }, model::test, model::copyAnalysisToTranscription, model::copyTranscriptionToAnalysis)
    permissionError?.let { message -> androidx.compose.material3.AlertDialog(onDismissRequest = { permissionError = null },
        title = { androidx.compose.material3.Text("权限提示") }, text = { androidx.compose.material3.Text(message) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { permissionError = null }) { androidx.compose.material3.Text("知道了") } }) }
}

@Composable internal fun ArchiveRoute(api: CallringUiApi, callId: Long, number: String, startedAt: Long, signal: Long,
    onBack: () -> Unit, onEdit: () -> Unit) {
    val model: ArchiveViewModel = uiViewModel { ArchiveViewModel(api.recordings, api.callers, callId, number, startedAt) }
    val state by model.state.collectAsStateWithLifecycle()
    val files by model.files.collectAsStateWithLifecycle()
    val mutation by model.mutation.collectAsStateWithLifecycle()
    val picker = rememberImportPicker(arrayOf("audio/*")) { model.import(it) }
    ActiveRefresh(signal, intervalMillis = 3_000, refresh = model::refresh)
    DisposableEffect(model) { onDispose { model.stopForExit() } }
    ArchiveScreen(state, files, mutation, onBack, model::refresh, picker, model::listSystem,
        { model.import(DocumentRef(it.uri), RecordingSource.SYSTEM) }, model::retry, model::delete, model::play, model::stop,
        model::adoptIdentity, onEdit)
}
