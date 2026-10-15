package com.greatwall.callring.ui.feature.settings

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
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.feature.shared.MutationFeedback
import com.greatwall.callring.ui.state.*
import java.time.LocalDate

@Composable
fun CalendarScreen(
    state: LoadState<CalendarInfo>, mutation: MutationState, onBack: () -> Unit, onRetry: () -> Unit,
    onImport: () -> Unit, onSave: (DateOverride) -> Unit, onDelete: (LocalDate) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var editingDate by rememberSaveable { mutableStateOf<String?>(null) }
    var date by rememberSaveable { mutableStateOf("") }
    var work by rememberSaveable { mutableStateOf(false) }
    var windows by rememberSaveable { mutableStateOf("") }
    var openedAt by rememberSaveable { mutableStateOf(mutation.completed) }
    fun edit(entry: DateOverride?) {
        editingDate = entry?.date?.toString(); date = entry?.date?.toString().orEmpty()
        work = entry?.kind == DateOverrideKind.WORK; windows = windowsText(entry?.windows.orEmpty())
        openedAt = mutation.completed; open = true
    }
    LaunchedEffect(mutation.completed) {
        if (mutation.completed < openedAt) openedAt = mutation.completed
        else if (mutation.completed > openedAt) open = false
    }
    SettingsLayout("节假日与日期覆盖", state, mutation, onBack, onRetry) { data ->
        SettingsCard("日历数据") {
            Text("导入年度日历后，将更新节假日与调休依据。")
            Button(onImport, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("导入日历文件") }
            FieldError(mutation, "source", "uri")
            if (data.years.isEmpty()) EmptyContent("暂无已导入日历")
            data.years.forEach { year ->
                Text("${year.year} 年", style = MaterialTheme.typography.titleMedium)
                Text("来源：${year.source}\n发布日期：${year.published}\n版本：${year.version}")
            }
        }
        Button({ edit(null) }, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("添加日期覆盖") }
        if (data.overrides.isEmpty()) EmptyContent("暂无日期覆盖")
        data.overrides.forEach { entry ->
            SettingsCard(entry.date.toString()) {
                Text(if (entry.kind == DateOverrideKind.REST) "休息日" else "工作日")
                if (entry.windows.isNotEmpty()) Text(windowsText(entry.windows))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ edit(entry) }, enabled = !mutation.busy) { Text("编辑") }
                    TextButton({ onDelete(entry.date) }, enabled = !mutation.busy) { Text("删除") }
                }
            }
        }
    }
    if (open) {
        val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
        val parsedWindows = if (work) parseWindows(windows) else emptyList()
        val windowsValid = parsedWindows != null && (!work || parsedWindows.isNotEmpty())
        AlertDialog(onDismissRequest = { if (!mutation.busy) open = false },
            title = { Text(if (editingDate == null) "添加日期覆盖" else "编辑日期覆盖") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MutationFeedback(mutation)
                    OutlinedTextField(date, { date = it }, label = { Text("日期（yyyy-MM-dd）") }, singleLine = true,
                        enabled = !mutation.busy && editingDate == null, isError = parsedDate == null, modifier = Modifier.fillMaxWidth())
                    InputError(if (parsedDate == null) "请输入有效日期，例如 2026-10-01" else null)
                    FieldError(mutation, "date")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!work, { work = false }, enabled = !mutation.busy, label = { Text("休息日") })
                        FilterChip(work, { work = true }, enabled = !mutation.busy, label = { Text("工作日") })
                    }
                    FieldError(mutation, "kind")
                    if (work) {
                        Text("工作日必须填写时段；跨午夜归属开始日。")
                        OutlinedTextField(windows, { windows = it }, label = { Text("工作时段") },
                            placeholder = { Text("09:00-12:00,13:00-18:00") }, enabled = !mutation.busy,
                            isError = !windowsValid, modifier = Modifier.fillMaxWidth())
                        InputError(if (!windowsValid) "请至少填写一个有效工作时段" else null)
                    } else Text("休息日不包含工作时段。")
                    FieldError(mutation, "windows")
                }
            }, confirmButton = {
                TextButton({ onSave(DateOverride(parsedDate!!, if (work) DateOverrideKind.WORK else DateOverrideKind.REST, parsedWindows!!)) },
                    enabled = !mutation.busy && parsedDate != null && windowsValid) { Text("保存") }
            }, dismissButton = { TextButton({ open = false }, enabled = !mutation.busy) { Text("取消") } })
    }
}
