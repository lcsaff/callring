package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.ui.designsystem.components.BrandButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.EmptyContent
import com.greatwall.callring.ui.designsystem.components.DateSelectionField
import com.greatwall.callring.ui.designsystem.components.ConfirmationDialog
import com.greatwall.callring.ui.designsystem.components.FormDialog
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.feature.shared.MutationFeedback
import com.greatwall.callring.ui.state.*
import java.time.LocalDate

@Composable
fun CalendarScreen(
    state: LoadState<CalendarInfo>, mutation: MutationState, onBack: () -> Unit, onRetry: () -> Unit,
    onImport: () -> Unit, onSave: (DateOverride) -> Unit, onDelete: (LocalDate) -> Unit,
) {
    var deleteDate by rememberSaveable { mutableStateOf<String?>(null) }
    var windowsValidDraft by remember { mutableStateOf(true) }
    var open by rememberSaveable { mutableStateOf(false) }
    var editingDate by rememberSaveable { mutableStateOf<String?>(null) }
    var date by rememberSaveable { mutableStateOf("") }
    var work by rememberSaveable { mutableStateOf(false) }
    var windows by rememberSaveable { mutableStateOf("") }
    var initialFields by rememberSaveable { mutableStateOf("") }
    var openedAt by rememberSaveable { mutableStateOf(mutation.completed) }
    fun edit(entry: DateOverride?) {
        editingDate = entry?.date?.toString(); date = entry?.date?.toString() ?: LocalDate.now().toString()
        work = entry?.kind == DateOverrideKind.WORK; windows = windowsText(entry?.windows.orEmpty())
        initialFields = listOf(date, work.toString(), windows).joinToString("\u0000")
        windowsValidDraft = true; openedAt = mutation.completed; open = true
    }
    LaunchedEffect(mutation.completed) {
        if (mutation.completed < openedAt) openedAt = mutation.completed
        else if (mutation.completed > openedAt) open = false
    }
    SettingsLayout("节假日与日期覆盖", state, mutation, onBack, onRetry) { data ->
        SettingsCard("日历数据") {
            Text("导入年度日历后，将更新节假日与调休依据。")
            BrandButton(onImport, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("导入日历文件") }
            FieldError(mutation, "source", "uri")
            if (data.years.isEmpty()) EmptyContent("暂无已导入日历")
            data.years.forEach { year ->
                Text("${year.year} 年", style = MaterialTheme.typography.titleMedium)
                Text("来源：${year.source}\n发布日期：${year.published}\n版本：${year.version}")
            }
        }
        BrandButton({ edit(null) }, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("添加日期覆盖") }
        if (data.overrides.isEmpty()) EmptyContent("暂无日期覆盖")
        data.overrides.forEach { entry ->
            SettingsCard(entry.date.toString()) {
                Text(if (entry.kind == DateOverrideKind.REST) "休息日" else "工作日")
                if (entry.windows.isNotEmpty()) Text(windowsText(entry.windows))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ edit(entry) }, enabled = !mutation.busy) { Text("编辑") }
                    TextButton({ deleteDate = entry.date.toString() }, enabled = !mutation.busy) { Text("删除") }
                }
            }
        }
    }
    if (open) {
        val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
        val parsedWindows = if (work) parseWindows(windows) else emptyList()
        val windowsValid = !work || (windowsValidDraft && !parsedWindows.isNullOrEmpty())
        FormDialog(title = if (editingDate == null) "添加日期覆盖" else "编辑日期覆盖",
            busy = mutation.busy, canSave = parsedDate != null && windowsValid,
            dirty = !windowsValidDraft || initialFields != listOf(date, work.toString(), windows).joinToString("\u0000"),
            onDismiss = { open = false }, onSave = {
                onSave(DateOverride(parsedDate!!, if (work) DateOverrideKind.WORK else DateOverrideKind.REST, parsedWindows!!))
            }) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MutationFeedback(mutation, showSuccess = false)
                    DateSelectionField("日期", date, { date = it }, enabled = !mutation.busy && editingDate == null)
                    InputError(if (parsedDate == null) "请选择日期" else null)
                    FieldError(mutation, "date")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!work, { work = false }, enabled = !mutation.busy, label = { Text("休息日") })
                        FilterChip(work, { work = true }, enabled = !mutation.busy, label = { Text("工作日") })
                    }
                    FieldError(mutation, "kind")
                    if (work) {
                        Text("工作日必须填写时段；跨午夜归属开始日。")
                        WindowsField("工作时段", parseWindows(windows).orEmpty(), !mutation.busy,
                            { windows = windowsText(it) }, { windowsValidDraft = it }, mutation, "windows")
                        InputError(if (!windowsValid) "请至少添加一个有效工作时段" else null)
                    } else Text("休息日不包含工作时段。")
                    FieldError(mutation, "windows")
                }
            }
    }
    deleteDate?.let { target ->
        ConfirmationDialog("删除日期覆盖", "删除 $target 的日期覆盖？", "删除",
            onConfirm = { deleteDate = null; onDelete(LocalDate.parse(target)) },
            onDismiss = { deleteDate = null }, busy = mutation.busy)
    }
}
