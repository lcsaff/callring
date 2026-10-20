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
import com.greatwall.callring.ui.feature.ai.ScopedRecordingSettingsViewModel

@Composable internal fun RecordingArchivesRoute(api: CallringUiApi, signal: Long, onBack: () -> Unit,
    onArchive: (CallArchive) -> Unit) {
    val model: RecordingArchivesViewModel = uiViewModel { RecordingArchivesViewModel(api.recordings, changes = api.changes) }
    val state by model.state.collectAsStateWithLifecycle()
    RecordingArchivesScreen(state, onBack, model::refresh, onArchive)
}

@Composable internal fun RecordingSettingsRoute(api: CallringUiApi, onBack: () -> Unit) {
    val model: ScopedRecordingSettingsViewModel = uiViewModel { ScopedRecordingSettingsViewModel(api) }
    val state by model.state.collectAsStateWithLifecycle()
    val mutation by model.mutation.collectAsStateWithLifecycle()
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
        { microphone.launch(Manifest.permission.RECORD_AUDIO) })
    permissionError?.let { message -> androidx.compose.material3.AlertDialog(onDismissRequest = { permissionError = null },
        title = { androidx.compose.material3.Text("权限提示") }, text = { androidx.compose.material3.Text(message) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { permissionError = null }) { androidx.compose.material3.Text("知道了") } }) }
}

@Composable internal fun ArchiveRoute(api: CallringUiApi, callId: Long, number: String, startedAt: Long, signal: Long,
    onBack: () -> Unit, onEdit: () -> Unit) {
    val model: ArchiveViewModel = uiViewModel { ArchiveViewModel(api.recordings, callId, number, startedAt, adoptCommand = api::adoptIdentity, changes = api.changes) }
    val state by model.state.collectAsStateWithLifecycle()
    val files by model.files.collectAsStateWithLifecycle()
    val mutation by model.mutation.collectAsStateWithLifecycle()
    val picker = rememberImportPicker(arrayOf("audio/*")) { model.import(it) }
    DisposableEffect(model) { onDispose { model.stopForExit() } }
    ArchiveScreen(state, files, mutation, onBack, model::refresh, picker, model::listSystem,
        { model.import(DocumentRef(it.uri), RecordingSource.SYSTEM) }, model::retry, model::delete, model::play, model::stop,
        model::adoptIdentity, onEdit)
}
