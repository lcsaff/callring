package com.greatwall.callring.ui.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusDirection

@Composable
fun ConfirmationDialog(
    title: String, message: String, confirmLabel: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit, busy: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { if (!busy) onConfirm() }, enabled = !busy) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

@Composable
fun ConfirmAction(
    label: String, title: String, message: String, onConfirm: () -> Unit,
    enabled: Boolean = true, modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = modifier) { Text(label) }
    if (open) ConfirmationDialog(title, message, "确认", onConfirm = {
        open = false
        onConfirm()
    }, onDismiss = { open = false }, busy = !enabled)
}

@Composable
internal fun UnsavedChangesDialog(
    busy: Boolean, canSave: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, onContinue: () -> Unit,
) {
    AlertDialog(onDismissRequest = { if (!busy) onContinue() },
        title = { Text("放弃未保存的修改？") }, text = { Text("可以先保存修改，或放弃本次编辑。") },
        confirmButton = { Row {
            TextButton(onClick = onSave, enabled = !busy && canSave) { Text("保存") }
            TextButton(onClick = onDiscard, enabled = !busy) { Text("放弃修改") }
        } },
        dismissButton = { TextButton(onClick = onContinue, enabled = !busy) { Text("取消") } })
}

@Composable
fun formKeyboardActions(onDone: (() -> Unit)? = null): KeyboardActions {
    val focus = LocalFocusManager.current
    return KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }, onDone = {
        focus.clearFocus()
        onDone?.invoke()
    }, onSearch = { focus.clearFocus(); onDone?.invoke() })
}

@Composable
fun rememberSubmit(onSubmit: () -> Unit): () -> Unit {
    val focus = LocalFocusManager.current
    return { focus.clearFocus(); onSubmit() }
}

/** Material Dialog with scrollable caller content and an always visible action row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormDialog(
    title: String, busy: Boolean, canSave: Boolean, onDismiss: () -> Unit, onSave: () -> Unit,
    dirty: Boolean = false,
    content: @Composable () -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val dismiss: () -> Unit = { if (!busy) { if (dirty) confirmDiscard = true else onDismiss() } }
    BasicAlertDialog(onDismissRequest = dismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.widthIn(max = 560.dp).heightIn(max = 640.dp).imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Box(Modifier.weight(1f, fill = false)) { content() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss, enabled = !busy) { Text("取消") }
                    TextButton(onClick = rememberSubmit(onSave), enabled = canSave && !busy) {
                        Text(if (busy) "保存中…" else "保存")
                    }
                }
            }
        }
    }
    if (confirmDiscard) UnsavedChangesDialog(busy, canSave,
        onSave = { confirmDiscard = false; onSave() },
        onDiscard = { confirmDiscard = false; onDismiss() }, onContinue = { confirmDiscard = false })
}
