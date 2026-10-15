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
import com.greatwall.callring.ui.format.UiFormat
import com.greatwall.callring.ui.state.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun LeavesScreen(
    state: LoadState<List<LeaveEntry>>, mutation: MutationState, zoneId: String,
    onBack: () -> Unit, onRetry: () -> Unit, onSave: (SaveLeave) -> Unit, onDelete: (Long) -> Unit,
) {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrNull()
    var open by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var allDay by rememberSaveable { mutableStateOf(true) }
    var startDate by rememberSaveable { mutableStateOf("") }
    var endDate by rememberSaveable { mutableStateOf("") }
    var startTime by rememberSaveable { mutableStateOf("") }
    var endTime by rememberSaveable { mutableStateOf("") }
    var originalStartText by rememberSaveable { mutableStateOf("") }
    var originalEndText by rememberSaveable { mutableStateOf("") }
    var originalStart by rememberSaveable { mutableStateOf<Long?>(null) }
    var originalEnd by rememberSaveable { mutableStateOf<Long?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var openedAt by rememberSaveable { mutableStateOf(mutation.completed) }
    fun edit(entry: LeaveEntry?) {
        editingId = entry?.id
        // The API gives epoch instants, not an all-day flag. Existing records are always edited as timed.
        allDay = entry == null
        originalStart = entry?.startInclusiveEpochMillis; originalEnd = entry?.endExclusiveEpochMillis
        val displayZone = zone ?: ZoneOffset.UTC
        originalStartText = entry?.let { localTimestamp(it.startInclusiveEpochMillis, displayZone) }.orEmpty()
        originalEndText = entry?.let { localTimestamp(it.endExclusiveEpochMillis, displayZone) }.orEmpty()
        startTime = originalStartText; endTime = originalEndText
        startDate = ""; endDate = ""; note = entry?.note.orEmpty()
        openedAt = mutation.completed; open = true
    }
    LaunchedEffect(mutation.completed) {
        if (mutation.completed < openedAt) openedAt = mutation.completed
        else if (mutation.completed > openedAt) open = false
    }
    SettingsLayout("请假管理", state, mutation, onBack, onRetry) { entries ->
        SettingsCard {
            Text("请假时区：$zoneId")
            InputError(if (zone == null) "当前工作时区无效，请先修改工作时间设置" else null)
            Button({ edit(null) }, enabled = !mutation.busy && zone != null, modifier = Modifier.fillMaxWidth()) { Text("添加请假") }
        }
        if (entries.isEmpty()) EmptyContent("暂无请假记录")
        entries.forEach { entry ->
            SettingsCard {
                val displayZone = zone ?: ZoneOffset.UTC
                Text("${UiFormat.time(entry.startInclusiveEpochMillis, displayZone)} 至 ${UiFormat.time(entry.endExclusiveEpochMillis, displayZone)}")
                Text(when (entry.state) { LeaveState.UPCOMING -> "未开始"; LeaveState.ACTIVE -> "进行中"; LeaveState.FINISHED -> "已结束" },
                    color = MaterialTheme.colorScheme.primary)
                if (entry.note.isNotBlank()) Text(entry.note)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ edit(entry) }, enabled = !mutation.busy && zone != null) { Text("编辑") }
                    TextButton({ onDelete(entry.id) }, enabled = !mutation.busy) { Text("删除") }
                }
            }
        }
    }
    if (open) {
        val firstDate = runCatching { LocalDate.parse(startDate) }.getOrNull()
        val lastDate = runCatching { LocalDate.parse(endDate) }.getOrNull()
        val firstInstant = if (originalStart != null && startTime == originalStartText) originalStart else zone?.let { parseTimestamp(startTime, it) }
        val lastInstant = if (originalEnd != null && endTime == originalEndText) originalEnd else zone?.let { parseTimestamp(endTime, it) }
        val validDates = firstDate != null && lastDate != null && !lastDate.isBefore(firstDate)
        val validTimes = firstInstant != null && lastInstant != null && lastInstant > firstInstant
        AlertDialog(onDismissRequest = { if (!mutation.busy) open = false },
            title = { Text(if (editingId == null) "添加请假" else "编辑请假") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MutationFeedback(mutation)
                    Text("时区：$zoneId")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(allDay, { allDay = true }, enabled = !mutation.busy, label = { Text("全天") })
                        FilterChip(!allDay, { allDay = false }, enabled = !mutation.busy, label = { Text("指定时间") })
                    }
                    if (allDay) {
                        Text("结束日期包含当天。")
                        OutlinedTextField(startDate, { startDate = it }, label = { Text("开始日期（yyyy-MM-dd）") }, singleLine = true,
                            isError = firstDate == null, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth())
                        InputError(if (firstDate == null) "请输入有效开始日期" else null)
                        FieldError(mutation, "period.startDate", "startDate")
                        OutlinedTextField(endDate, { endDate = it }, label = { Text("结束日期（含当天）") }, singleLine = true,
                            isError = !validDates, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth())
                        InputError(if (lastDate == null) "请输入有效结束日期" else if (!validDates) "结束日期不能早于开始日期" else null)
                        FieldError(mutation, "period.endDateInclusive", "endDateInclusive")
                    } else {
                        Text("格式：2026-10-15 09:00；结束时刻不包含在请假期间内。")
                        OutlinedTextField(startTime, { startTime = it }, label = { Text("开始日期与时间") },
                            isError = firstInstant == null, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth())
                        InputError(if (firstInstant == null) "请输入有效开始时间" else null)
                        FieldError(mutation, "period.startInclusiveEpochMillis", "startInclusiveEpochMillis")
                        OutlinedTextField(endTime, { endTime = it }, label = { Text("结束日期与时间") },
                            isError = !validTimes, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth())
                        InputError(if (lastInstant == null) "请输入有效结束时间" else if (!validTimes) "结束时间必须晚于开始时间" else null)
                        FieldError(mutation, "period.endExclusiveEpochMillis", "endExclusiveEpochMillis")
                    }
                    FieldError(mutation, "period", "period.zoneId", "zoneId")
                    OutlinedTextField(note, { note = it }, label = { Text("备注") }, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth())
                    FieldError(mutation, "note", "id")
                }
            }, confirmButton = {
                TextButton({
                    val period = if (allDay) LeavePeriod.AllDay(firstDate!!, lastDate!!, zoneId)
                        else LeavePeriod.Timed(firstInstant!!, lastInstant!!)
                    onSave(SaveLeave(editingId, period, note))
                }, enabled = !mutation.busy && zone != null && (if (allDay) validDates else validTimes)) { Text("保存") }
            }, dismissButton = { TextButton({ open = false }, enabled = !mutation.busy) { Text("取消") } })
    }
}

private fun localTimestamp(epoch: Long, zone: ZoneId): String =
    DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(Instant.ofEpochMilli(epoch).atZone(zone).toLocalDateTime()).replace('T', ' ')

private fun parseTimestamp(raw: String, zone: ZoneId): Long? = runCatching {
    val local = LocalDateTime.parse(raw.trim().replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    // Reject nonexistent local times instead of silently moving the user's input through a DST gap.
    val offset = zone.rules.getValidOffsets(local).firstOrNull() ?: return null
    local.toInstant(offset).toEpochMilli()
}.getOrNull()
