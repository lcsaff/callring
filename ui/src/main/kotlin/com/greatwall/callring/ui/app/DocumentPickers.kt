package com.greatwall.callring.ui.app

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.greatwall.callring.ui.designsystem.components.LocalFeedbackHost
import kotlinx.coroutines.launch
import com.greatwall.callring.contracts.common.DocumentRef

@Composable internal fun rememberImportPicker(mimeTypes: Array<String>, onPicked: (DocumentRef) -> Unit): () -> Unit {
    val feedback = rememberDocumentFeedback()
    val current by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (uri.scheme == "content") current(DocumentRef(uri.toString()))
            else feedback("请选择系统文档提供器中的文件")
        }
    }
    return { try { launcher.launch(mimeTypes) } catch (_: ActivityNotFoundException) {
        feedback("系统没有可用的文件选择器")
    } }
}

@Composable internal fun rememberExportPicker(onPicked: (DocumentRef) -> Unit): () -> Unit {
    val feedback = rememberDocumentFeedback()
    val current by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && uri.scheme == "content") current(DocumentRef(uri.toString()))
    }
    return { try { launcher.launch("callring-report.csv") } catch (_: ActivityNotFoundException) {
        feedback("系统没有可用的文档保存器")
    } }
}

@Composable private fun rememberDocumentFeedback(): (String) -> Unit {
    val host = LocalFeedbackHost.current
    val scope = rememberCoroutineScope()
    return { message -> scope.launch { host?.showSnackbar(message, withDismissAction = true) }; Unit }
}
