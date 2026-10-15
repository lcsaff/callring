package com.greatwall.callring.ui.app

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.greatwall.callring.application.api.DocumentRef

@Composable internal fun rememberImportPicker(mimeTypes: Array<String>, onPicked: (DocumentRef) -> Unit): () -> Unit {
    val context = LocalContext.current
    val current by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (uri.scheme == "content") current(DocumentRef(uri.toString()))
            else Toast.makeText(context, "请选择系统文档提供器中的文件", Toast.LENGTH_LONG).show()
        }
    }
    return { try { launcher.launch(mimeTypes) } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "系统没有可用的文件选择器", Toast.LENGTH_LONG).show()
    } }
}

@Composable internal fun rememberExportPicker(onPicked: (DocumentRef) -> Unit): () -> Unit {
    val context = LocalContext.current
    val current by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && uri.scheme == "content") current(DocumentRef(uri.toString()))
    }
    return { try { launcher.launch("callring-report.csv") } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "系统没有可用的文档保存器", Toast.LENGTH_LONG).show()
    } }
}
