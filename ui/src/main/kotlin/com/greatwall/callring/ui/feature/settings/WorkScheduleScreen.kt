package com.greatwall.callring.ui.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.greatwall.callring.application.api.WorkScheduleSettings
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.format.UiFormat
import com.greatwall.callring.ui.state.*
import java.time.ZoneId

@Composable
fun WorkScheduleScreen(
    state: LoadState<WorkScheduleSettings>, draft: WorkScheduleSettings?, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onChange: (WorkScheduleSettings) -> Unit,
    onSave: () -> Unit, onReset: () -> Unit,
) {
    SettingsLayout("工作时间", state, mutation, onBack, onRetry) { saved ->
        val value = draft ?: saved
        val valid = remember { mutableStateMapOf<Int, Boolean>() }
        var zone by rememberSaveable(value.zoneId) { mutableStateOf(value.zoneId) }
        val parsedZone = runCatching { ZoneId.of(zone) }.getOrNull()
        SettingsCard("时区与版本") {
            OutlinedTextField(zone, { raw ->
                zone = raw
                if (runCatching { ZoneId.of(raw) }.isSuccess) onChange(value.copy(zoneId = raw))
            }, label = { Text("时区") }, placeholder = { Text("Asia/Shanghai") }, singleLine = true,
                isError = parsedZone == null, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth())
            InputError(if (parsedZone == null) "请输入有效时区，例如 Asia/Shanghai" else null)
            FieldError(mutation, "zoneId")
            Text("配置版本：${saved.configVersion}")
            Text("生效时间：${UiFormat.time(saved.effectiveFromEpochMillis, runCatching { ZoneId.of(saved.zoneId) }.getOrDefault(ZoneId.of("UTC")))}")
        }
        SettingsCard("每周工作时段") {
            Text("留空表示当天无时段。跨午夜时段归属开始日，例如 22:00-07:00。")
            weekLabels.forEachIndexed { index, label ->
                val day = index + 1
                WindowsField(label, value.weekly[day].orEmpty(), !mutation.busy,
                    { onChange(value.copy(weekly = value.weekly + (day to it))) },
                    { valid[day] = it }, mutation, "weekly.$day", "weekly[$day]")
            }
            FieldError(mutation, "weekly", includeDescendants = false)
        }
        SettingsCard("调休上班时段") {
            WindowsField("调休工作日使用的时段", value.makeup, !mutation.busy,
                { onChange(value.copy(makeup = it)) }, { valid[0] = it }, mutation, "makeup")
        }
        SaveAction(mutation, parsedZone != null && valid.values.all { it }, onSave)
        OutlinedButton(onReset, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("恢复默认工作时间") }
    }
}
