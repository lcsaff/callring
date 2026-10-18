package com.greatwall.callring.ui.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
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

@Composable
private fun PickerField(
    label: String, value: String, placeholder: String, enabled: Boolean, modifier: Modifier,
    icon: ImageVector, onClick: () -> Unit,
) {
    OutlinedButton(onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value.ifBlank { placeholder }, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSelectionField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    var open by rememberSaveable { mutableStateOf(false) }
    PickerField(label, value, "选择日期", enabled, Modifier.testTag("date-$label"), Icons.Default.DateRange) { open = true }
    if (open) {
        val initial = runCatching { LocalDate.parse(value) }.getOrNull()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial?.let(::dateToPickerMillis))
        DatePickerDialog(onDismissRequest = { open = false }, modifier = Modifier.imePadding(), confirmButton = {
            TextButton({ picker.selectedDateMillis?.let { onChange(pickerMillisToDate(it).toString()) }; open = false },
                enabled = picker.selectedDateMillis != null) { Text("确定") }
        }, dismissButton = { TextButton({ open = false }) { Text("取消") } }) {
            DatePicker(picker, title = {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton({ picker.displayMode = if (picker.displayMode == DisplayMode.Picker) DisplayMode.Input else DisplayMode.Picker }) {
                        Text(if (picker.displayMode == DisplayMode.Picker) "输入日期" else "选择日历")
                    }
                }
            }, showModeToggle = false,
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("date-picker-$label"))
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
    PickerField(label, value, "选择时间", enabled, modifier.testTag("time-$selectionKey"), Icons.Default.ArrowDropDown) { open = true }
    if (open) {
        val initial = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(9, 0))
        val picker = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
        var keyboard by rememberSaveable { mutableStateOf(false) }
        val configuration = LocalConfiguration.current
        val horizontal = configuration.screenHeightDp < 480 && configuration.screenWidthDp >= 560
        BasicAlertDialog(onDismissRequest = { open = false },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (horizontal) 8.dp else 24.dp)
                .widthIn(max = if (horizontal) 560.dp else 360.dp).fillMaxWidth().imePadding(),
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = if (horizontal) 8.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(if (horizontal) 8.dp else 16.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        // Center the fixed-size clock and allow scrolling on short windows.
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            if (keyboard) TimeInput(picker, modifier = Modifier.testTag("time-input"))
                            else TimePicker(picker, layoutType = if (horizontal) TimePickerLayoutType.Horizontal else TimePickerLayoutType.Vertical)
                        }
                    }
                    if (!horizontal) TextButton({ keyboard = !keyboard }) { Text(if (keyboard) "切换为表盘选择" else "切换为键盘输入") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (horizontal) {
                            TextButton({ keyboard = !keyboard }) { Text(if (keyboard) "切换为表盘选择" else "切换为键盘输入") }
                            Spacer(Modifier.weight(1f))
                        }
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
    DatePickerDialog(onDismissRequest = onDismiss, modifier = Modifier.imePadding(), confirmButton = {
        TextButton({
            onConfirm(pickerMillisToDate(picker.selectedStartDateMillis!!), pickerMillisToDate(picker.selectedEndDateMillis!!))
        }, enabled = picker.selectedStartDateMillis != null && picker.selectedEndDateMillis != null) { Text(confirmLabel) }
    }, dismissButton = { TextButton(onDismiss) { Text("取消") } }) {
        DateRangePicker(picker, title = { Text(title, Modifier.padding(start = 24.dp, top = 16.dp)) },
            headline = {
                val first = picker.selectedStartDateMillis?.let(::pickerMillisToDate)?.toString() ?: "开始日期"
                val last = picker.selectedEndDateMillis?.let(::pickerMillisToDate)?.toString() ?: "结束日期"
                Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                    Text(first, style = MaterialTheme.typography.titleLarge)
                    Text("至 $last", style = MaterialTheme.typography.titleLarge)
                }
            }, modifier = Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 520.dp))
    }
}
