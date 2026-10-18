package com.greatwall.callring.ui.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.SwitchSettingRow
import com.greatwall.callring.ui.designsystem.components.rememberFormBack
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.feature.shared.LoadFeedback
import com.greatwall.callring.ui.feature.shared.MutationFeedback
import com.greatwall.callring.ui.format.UiLabels
import com.greatwall.callring.ui.state.*

@Composable
fun HomeQuietCard(
    state: LoadState<QuietSettings>, mutation: MutationState,
    onRetry: () -> Unit, onEnabled: (Boolean) -> Unit, onCustomize: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("home-quiet"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("免打扰", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onCustomize, enabled = state.data != null && !mutation.busy) { Text("自定义") }
                Switch(state.data?.mode?.let { it != QuietMode.OFF } ?: false, onEnabled,
                    enabled = state.data != null && !mutation.busy,
                    modifier = Modifier.testTag("home-quiet-switch").semantics { contentDescription = "免打扰开关" })
            }
            Text(state.data?.let { UiLabels.quiet(it.mode) } ?: "正在读取免打扰设置…",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LoadFeedback(state, onRetry)
            MutationFeedback(mutation, showSuccess = false)
        }
    }
}

@Composable
fun HomeQuietDialog(
    saved: QuietSettings, draft: QuietSettings, mutation: MutationState,
    onChange: (QuietSettings) -> Unit, onSave: () -> Unit, onClose: () -> Unit,
) {
    val valid = remember { mutableStateMapOf<Int, Boolean>() }
    val invalidWindows = draft.mode == QuietMode.CUSTOM && valid.values.any { !it }
    val canSave = validQuietDraft(draft) && !invalidWindows
    val requestClose = rememberFormBack(onClose, draft != saved || invalidWindows, mutation, onSave, canSave)
    AlertDialog(
        onDismissRequest = requestClose,
        title = { Text("自定义免打扰") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("quiet-dialog-scroll"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MutationFeedback(mutation, showSuccess = false)
                Text("免打扰模式", style = MaterialTheme.typography.titleMedium)
                listOf(QuietMode.NON_WORK to "全部非工作时间", QuietMode.CUSTOM to "自定义时段").forEach { (mode, label) ->
                    FilterChip(draft.mode == mode, { onChange(draft.copy(mode = mode)) },
                        enabled = !mutation.busy, label = { Text(label) })
                }
                FieldError(mutation, "mode")
                SwitchSettingRow("应用于所有号码", draft.allNumbers,
                    { onChange(draft.copy(allNumbers = it)) }, "关闭时按来电规则处理", enabled = !mutation.busy)
                FieldError(mutation, "allNumbers")
                if (draft.mode == QuietMode.CUSTOM) {
                    Text("每周自定义时段", style = MaterialTheme.typography.titleMedium)
                    Text("无时段表示当天不静音。结束时间早于开始时间时，时段跨到次日。")
                    weekLabels.forEachIndexed { index, label ->
                        val day = index + 1
                        WindowsField(label, draft.weeklyWindows[day].orEmpty(), !mutation.busy,
                            { onChange(draft.copy(weeklyWindows = draft.weeklyWindows + (day to it))) },
                            { valid[day] = it }, mutation, "weeklyWindows.$day", "weeklyWindows[$day]")
                    }
                    InputError(if (draft.weeklyWindows.values.none { it.isNotEmpty() }) "自定义免打扰至少需要一个时段" else null)
                    FieldError(mutation, "weeklyWindows", includeDescendants = false)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = canSave && !mutation.busy, modifier = Modifier.testTag("quiet-dialog-save")) {
                Text(if (mutation.busy) "保存中…" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = requestClose, enabled = !mutation.busy, modifier = Modifier.testTag("quiet-dialog-cancel")) { Text("取消") }
        },
    )
}
