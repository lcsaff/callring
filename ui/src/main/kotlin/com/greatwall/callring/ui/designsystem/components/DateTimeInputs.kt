package com.greatwall.callring.ui.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

// Material date picker values are UTC calendar dates, independent of the business time zone.
internal fun dateToPickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
internal fun pickerMillisToDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
internal fun clockTimeText(hour: Int, minute: Int): String = "%02d:%02d".format(Locale.ROOT, hour, minute)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSelectionField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton({ open = true }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().testTag("date-$label")) {
        Text("$label：${value.ifBlank { "选择日期" }}")
    }
    if (open) {
        val initial = runCatching { LocalDate.parse(value) }.getOrNull()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial?.let(::dateToPickerMillis))
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton({ picker.selectedDateMillis?.let { onChange(pickerMillisToDate(it).toString()) }; open = false },
                enabled = picker.selectedDateMillis != null) { Text("确定") }
        }, dismissButton = { TextButton({ open = false }) { Text("取消") } }) {
            Column {
                TextButton({ picker.displayMode = if (picker.displayMode == DisplayMode.Picker) DisplayMode.Input else DisplayMode.Picker }) {
                    Text(if (picker.displayMode == DisplayMode.Picker) "输入日期" else "选择日历")
                }
                DatePicker(picker, title = { Text(label, Modifier.padding(start = 24.dp, top = 8.dp)) },
                    showModeToggle = false, modifier = Modifier.testTag("date-picker-$label"))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSelectionField(
    label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true,
    modifier: Modifier = Modifier, selectionKey: String = label,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    OutlinedButton({ open = true }, enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("time-$selectionKey")) {
        Text("$label ${value.ifBlank { "选择时间" }}")
    }
    if (open) {
        val initial = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(9, 0))
        val picker = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
        var keyboard by rememberSaveable { mutableStateOf(false) }
        BasicAlertDialog(onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 280.dp, max = 360.dp).fillMaxWidth(),
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ keyboard = !keyboard }) { Text(if (keyboard) "切换为表盘选择" else "切换为键盘输入") }
                        if (keyboard) TimeInput(picker, modifier = Modifier.testTag("time-input")) else TimePicker(picker)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ open = false }) { Text("取消") }
                        TextButton({ onChange(clockTimeText(picker.hour, picker.minute)); open = false }) { Text("确定") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeSelectionDialog(
    title: String, start: LocalDate, end: LocalDate,
    onDismiss: () -> Unit, onConfirm: (LocalDate, LocalDate) -> Unit, confirmLabel: String = "确定",
) {
    val picker = rememberDateRangePickerState(
        initialSelectedStartDateMillis = dateToPickerMillis(start),
        initialSelectedEndDateMillis = dateToPickerMillis(end),
    )
    DatePickerDialog(onDismissRequest = onDismiss, confirmButton = {
        TextButton({
            onConfirm(pickerMillisToDate(picker.selectedStartDateMillis!!), pickerMillisToDate(picker.selectedEndDateMillis!!))
        }, enabled = picker.selectedStartDateMillis != null && picker.selectedEndDateMillis != null) { Text(confirmLabel) }
    }, dismissButton = { TextButton(onDismiss) { Text("取消") } }) {
        DateRangePicker(picker, title = { Text(title, Modifier.padding(start = 24.dp, top = 8.dp)) },
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp))
    }
}
