package com.greatwall.callring.ui.app

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greatwall.callring.contracts.backup.BackupApi
import com.greatwall.callring.contracts.backup.BackupPhase
import com.greatwall.callring.contracts.common.DocumentRef
import com.greatwall.callring.ui.designsystem.components.ConfirmationDialog
import com.greatwall.callring.ui.feature.backup.*

@Composable internal fun BackupRoute(api: BackupApi, onBack: () -> Unit) {
    val model: BackupViewModel = uiViewModel { BackupViewModel(api) }
    val state by model.state.collectAsStateWithLifecycle()
    val password by model.password.collectAsStateWithLifecycle()
    val audio by model.includeAudio.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    var pickerError by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri?.scheme == "content") model.export(DocumentRef(uri.toString()))
    }
    val import = rememberImportPicker(arrayOf("application/octet-stream", "application/zip", "*/*"), model::preview)
    val restoring = state.busy && progress.phase == BackupPhase.RESTORING
    val back = { if (!restoring) { model.exit(); onBack() } }
    BackHandler(state.busy) { back() }
    DisposableEffect(model) { onDispose { model.exit() } }
    BackupScreen(state, progress, password, audio, back, model::editPassword, model::includeAudio,
        { try { export.launch("callring-backup.crbackup") } catch (_: ActivityNotFoundException) { pickerError = true } },
        import, model::restore, model::cancel)
    if (pickerError) ConfirmationDialog("无法保存备份", "系统没有可用的文档保存器。", "知道了",
        { pickerError = false }, { pickerError = false })
}
