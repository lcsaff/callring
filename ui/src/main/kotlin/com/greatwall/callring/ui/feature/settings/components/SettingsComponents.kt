package com.greatwall.callring.ui.feature.settings.components

import com.greatwall.callring.ui.designsystem.components.BrandButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.TimeWindow
import com.greatwall.callring.ui.designsystem.components.LoadingContent
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import com.greatwall.callring.ui.designsystem.components.rememberSubmit
import com.greatwall.callring.ui.designsystem.components.clockTimeText
import com.greatwall.callring.ui.feature.shared.LoadFeedback
import com.greatwall.callring.ui.feature.shared.MutationFeedback
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState

@Composable
internal fun <T> SettingsLayout(
    title: String, state: LoadState<T>, mutation: MutationState,
    onBack: (() -> Unit)?, onRetry: () -> Unit,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    ScreenFrame(title, onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.loaded && state.data == null && !state.loading && state.error == null) LoadingContent()
            LoadFeedback(state, onRetry)
            MutationFeedback(mutation)
            state.data?.let { content(it) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun SettingsCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun FieldError(mutation: MutationState, vararg keys: String, includeDescendants: Boolean = true) {
    mutation.error?.fieldErrors.orEmpty().filterKeys { path ->
        keys.any { key ->
            path == key || (includeDescendants && (path.startsWith("$key.") || path.startsWith("$key[")))
        }
    }.values.distinct().forEach {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun InputError(message: String?) {
    if (message != null) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun SaveAction(mutation: MutationState, enabled: Boolean = true, onSave: () -> Unit) {
    BrandButton(onClick = rememberSubmit(onSave), enabled = enabled && !mutation.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-save")) {
        Text(if (mutation.busy) "保存中…" else "保存")
    }
}

@Composable
internal fun SettingsSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    Switch(checked, onCheckedChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
}

internal fun windowsText(windows: List<TimeWindow>): String = windows.joinToString(",") {
    "${clockTimeText(it.startMinute / 60, it.startMinute % 60)}-${clockTimeText(it.endMinute / 60, it.endMinute % 60)}"
}

/** Input syntax only: the application API owns work and quiet classifications. */
internal fun parseWindows(text: String): List<TimeWindow>? {
    if (text.isBlank()) return emptyList()
    return text.replace('，', ',').split(',').map { token ->
        val match = Regex("\\s*(\\d{2}):(\\d{2})\\s*-\\s*(\\d{2}):(\\d{2})\\s*").matchEntire(token) ?: return null
        val (sh, sm, eh, em) = match.destructured
        val startHour = sh.toInt(); val startMinute = sm.toInt()
        val endHour = eh.toInt(); val endMinute = em.toInt()
        if (startHour !in 0..23 || startMinute !in 0..59 || endHour !in 0..23 || endMinute !in 0..59) return null
        val start = startHour * 60 + startMinute; val end = endHour * 60 + endMinute
        if (start == end) return null
        TimeWindow(start, end)
    }
}

@Composable
internal fun WindowsField(
    label: String, windows: List<TimeWindow>, enabled: Boolean,
    onChange: (List<TimeWindow>) -> Unit, onValidity: (Boolean) -> Unit,
    mutation: MutationState, vararg errorKeys: String,
) {
    // Keep a local, saveable draft so equal endpoints can be corrected without changing the valid API draft.
    var text by rememberSaveable(windowsText(windows), mutation.completed) { mutableStateOf(windowsText(windows)) }
    val rows = if (text.isBlank()) emptyList() else text.split(',').map { token ->
        val parts = token.split('-').map { time -> time.split(':').let { it[0].toInt() * 60 + it[1].toInt() } }
        TimeWindow(parts[0], parts[1])
    }
    val valid = rows.all { it.startMinute != it.endMinute }
    LaunchedEffect(text) { onValidity(valid) }
    fun update(next: List<TimeWindow>) {
        text = windowsText(next)
        val nextValid = next.all { it.startMinute != it.endMinute }
        onValidity(nextValid)
        if (nextValid) onChange(next)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        rows.forEachIndexed { index, window ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.greatwall.callring.ui.designsystem.components.TimeSelectionField("开始",
                        clockTimeText(window.startMinute / 60, window.startMinute % 60), { raw ->
                            val parts = raw.split(':').map(String::toInt)
                            update(rows.toMutableList().also { it[index] = window.copy(startMinute = parts[0] * 60 + parts[1]) })
                        }, enabled, modifier = Modifier.weight(1f), selectionKey = "$label-${index + 1}-开始")
                    com.greatwall.callring.ui.designsystem.components.TimeSelectionField("结束",
                        clockTimeText(window.endMinute / 60, window.endMinute % 60), { raw ->
                            val parts = raw.split(':').map(String::toInt)
                            update(rows.toMutableList().also { it[index] = window.copy(endMinute = parts[0] * 60 + parts[1]) })
                        }, enabled, modifier = Modifier.weight(1f), selectionKey = "$label-${index + 1}-结束")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (window.endMinute < window.startMinute) "跨至次日" else "当天结束",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    TextButton({ update(rows.toMutableList().also { it.removeAt(index) }) }, enabled = enabled) { Text("移除时段 ${index + 1}") }
                }
                InputError(if (window.startMinute == window.endMinute) "开始和结束时间不能相同" else null)
            }
        }
        TextButton({ update(rows + TimeWindow(540, 720)) }, enabled = enabled) { Text("添加时段") }
        if (rows.isEmpty()) Text("当天无时段", style = MaterialTheme.typography.bodySmall)
    }
    FieldError(mutation, *errorKeys)
}

internal val weekLabels = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
