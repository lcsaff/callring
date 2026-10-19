package com.greatwall.callring.ui.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.greatwall.callring.application.api.WorkScheduleSettings
import com.greatwall.callring.application.api.TimeWindow
import com.greatwall.callring.ui.feature.settings.components.*
import com.greatwall.callring.ui.format.UiFormat
import com.greatwall.callring.ui.state.*
import com.greatwall.callring.ui.designsystem.components.ConfirmAction
import com.greatwall.callring.ui.designsystem.components.TimeSelectionField
import com.greatwall.callring.ui.designsystem.components.clockTimeText
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkScheduleScreen(
    state: LoadState<WorkScheduleSettings>, draft: WorkScheduleSettings?, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onChange: (WorkScheduleSettings) -> Unit,
    // The route still forwards a save callback from the previous page model; immediate editing never renders one.
    @Suppress("UNUSED_PARAMETER") onSave: () -> Unit, onReset: () -> Unit,
) {
    SettingsLayout("工作时间", state, mutation, onBack, onRetry) { saved ->
        val value = draft ?: saved
        val windows = sharedWorkWindows(value)
        fun text(minute: Int) = clockTimeText(minute / 60, minute % 60)
        // Include the completion counter so resetting to identical defaults also clears invalid local edits.
        var start by rememberSaveable(windows, mutation.completed) { mutableStateOf(text(windows[0].startMinute)) }
        var lunchStart by rememberSaveable(windows, mutation.completed) { mutableStateOf(text(windows[0].endMinute)) }
        var lunchEnd by rememberSaveable(windows, mutation.completed) { mutableStateOf(text(windows[1].startMinute)) }
        var end by rememberSaveable(windows, mutation.completed) { mutableStateOf(text(windows[1].endMinute)) }
        val minutes = listOf(start, lunchStart, lunchEnd, end).map {
            runCatching { LocalTime.parse(it).let { time -> time.hour * 60 + time.minute } }.getOrNull()
        }
        val validTimes = minutes.all { it != null } && minutes.zipWithNext().all { (a, b) -> a!! < b!! }
        val parsedZone = runCatching { ZoneId.of(value.zoneId) }.getOrNull()
        var expanded by remember { mutableStateOf(false) }
        val zones = remember(value.zoneId) {
            (listOf("Asia/Shanghai", "UTC", "Asia/Hong_Kong", "Asia/Tokyo", "Asia/Singapore",
                "Europe/London", "Europe/Paris", "America/New_York", "America/Los_Angeles")
                + listOfNotNull(value.zoneId.takeIf { parsedZone != null })
                + ZoneId.getAvailableZoneIds().sorted()).distinct()
        }
        SettingsCard("时区与版本") {
            ExposedDropdownMenuBox(expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(value.zoneId, {}, readOnly = true, label = { Text("时区") }, singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    isError = parsedZone == null,
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth().testTag("work-zone"))
                ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    zones.forEach { zone ->
                        DropdownMenuItem(text = { Text(zone) }, onClick = {
                            expanded = false
                            onChange(normalizeWorkSchedule(value.copy(zoneId = zone)))
                        })
                    }
                }
            }
            InputError(if (parsedZone == null) "请选择有效时区" else null)
            FieldError(mutation, "zoneId")
            Text("配置版本：${saved.configVersion}")
            Text("生效时间：${UiFormat.time(saved.effectiveFromEpochMillis, runCatching { ZoneId.of(saved.zoneId) }.getOrDefault(ZoneId.of("UTC")))}")
        }
        fun updateTimes(a: String, b: String, c: String, d: String) {
            start = a; lunchStart = b; lunchEnd = c; end = d
            val edited = listOf(a, b, c, d).map {
                runCatching { LocalTime.parse(it).let { time -> time.hour * 60 + time.minute } }.getOrNull()
            }
            if (edited.all { it != null } && edited.zipWithNext().all { (x, y) -> x!! < y!! }) {
                val common = listOf(TimeWindow(edited[0]!!, edited[1]!!), TimeWindow(edited[2]!!, edited[3]!!))
                onChange(value.copy(weekly = (1..7).associateWith { if (it <= 5) common else emptyList() }, makeup = common))
            }
        }
        SettingsCard("统一工作时间") {
            Text("所有工作日共用以下时间，午休期间不工作。")
            TimeSelectionField("上班时间", start, { updateTimes(it, lunchStart, lunchEnd, end) },
                true, selectionKey = "work-start")
            TimeSelectionField("午休开始", lunchStart, { updateTimes(start, it, lunchEnd, end) },
                true, selectionKey = "work-lunch-start")
            TimeSelectionField("午休结束", lunchEnd, { updateTimes(start, lunchStart, it, end) },
                true, selectionKey = "work-lunch-end")
            TimeSelectionField("下班时间", end, { updateTimes(start, lunchStart, lunchEnd, it) },
                true, selectionKey = "work-end")
            InputError(if (validTimes) null else "请按上班、午休开始、午休结束、下班的顺序设置同一天的时间")
            FieldError(mutation, "weekly", "makeup")
        }
        ConfirmAction("恢复默认工作时间", "恢复默认工作时间？", "工作时间和时区将被默认值替换。", onReset,
            enabled = !mutation.busy, modifier = Modifier.fillMaxWidth())
    }
}
