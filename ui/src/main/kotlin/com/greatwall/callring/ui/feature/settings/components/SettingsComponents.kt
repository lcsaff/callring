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
import com.greatwall.callring.ui.feature.shared.LoadFeedback
import com.greatwall.callring.ui.feature.shared.MutationFeedback
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState

@Composable
internal fun <T> SettingsLayout(
    title: String, state: LoadState<T>, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    ScreenFrame(title, onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!state.loaded && state.data == null && !state.loading && state.error == null) LoadingContent()
            LoadFeedback(state, onRetry)
            MutationFeedback(mutation)
            state.data?.let { content(it) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun SettingsCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    BrandButton(onClick = onSave, enabled = enabled && !mutation.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings-save")) {
        Text(if (mutation.busy) "保存中…" else "保存")
    }
}

@Composable
internal fun SettingsSwitch(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    Switch(checked, onCheckedChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
}

internal fun windowsText(windows: List<TimeWindow>): String = windows.joinToString(",") {
    "%02d:%02d-%02d:%02d".format(it.startMinute / 60, it.startMinute % 60, it.endMinute / 60, it.endMinute % 60)
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
    var text by rememberSaveable(windowsText(windows), mutation.completed) { mutableStateOf(windowsText(windows)) }
    val parsed = parseWindows(text)
    LaunchedEffect(text) { onValidity(parsed != null) }
    OutlinedTextField(text, { raw ->
        text = raw
        val result = parseWindows(raw)
        onValidity(result != null)
        if (result != null) onChange(result)
    }, label = { Text(label) }, placeholder = { Text("09:00-12:00,13:00-18:00") }, enabled = enabled,
        isError = parsed == null, modifier = Modifier.fillMaxWidth(), minLines = 1)
    InputError(if (parsed == null) "请输入有效时段，例如 09:00-12:00；多个时段用逗号分隔" else null)
    FieldError(mutation, *errorKeys)
}

internal val weekLabels = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
