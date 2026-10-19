package com.greatwall.callring.ui.feature.callers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.ApiError
import com.greatwall.callring.application.api.NumberDeletionImpact
import com.greatwall.callring.ui.state.MutationState

/** 删除号码的两次确认：先看影响范围，再要求输入号码后 4 位并勾选不可恢复。 */
@Composable
internal fun NumberDeletionDialogs(
    impact: NumberDeletionImpact?,
    state: MutationState?,
    error: ApiError?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (impact == null) return
    var stage by remember(impact) { mutableIntStateOf(1) }
    var typed by remember(impact) { mutableStateOf("") }
    var acknowledged by remember(impact) { mutableStateOf(false) }
    val tail = impact.number.filter(Char::isDigit).takeLast(4).ifEmpty { impact.number }
    val busy = state?.busy == true
    if (stage == 1) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("删除该号码及全部数据？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("将永久删除：")
                    if (impact.profileExists) Text("· 号码档案 1 条")
                    Text("· 通话记录 ${impact.callCount} 条")
                    Text("· 录音归档 ${impact.archiveCount} 条" + if (impact.audioBytes > 0) "（音频 ${formatDeletionSize(impact.audioBytes)}）" else "")
                    if (impact.widgetBindingCount > 0) Text("· 小组件绑定 ${impact.widgetBindingCount} 个")
                    if (impact.busy) Text("该号码仍有进行中的录音/转写/分析，稍后再试。", color = MaterialTheme.colorScheme.error)
                    error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                    state?.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                    Text("此操作不可恢复。", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(modifier = Modifier.testTag("delete-continue"), onClick = { stage = 2 }) { Text("继续") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("确认永久删除") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请输入号码后 4 位：$tail")
                    OutlinedTextField(
                        value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.testTag("delete-tail"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("后 4 位") },
                    )
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it }, modifier = Modifier.testTag("delete-ack"))
                        Text("我已知晓删除后不可恢复")
                    }
                    error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                    state?.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onConfirm,
                    modifier = Modifier.testTag("delete-confirm"), enabled = !busy && typed == tail && acknowledged,
                ) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    }
}

internal fun formatDeletionSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
