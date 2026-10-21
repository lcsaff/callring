package com.greatwall.callring.ui.feature.report

import com.greatwall.callring.ui.feature.shared.CallerCard
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.shared.*
import com.greatwall.callring.ui.format.*
import com.greatwall.callring.ui.state.*
import java.time.ZoneId

@Composable fun ReportScreen(
    state: LoadState<CallerReport>, query: ReportQuery, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onRange: (DateRange) -> Unit, onFilter: (CallFilter) -> Unit,
    onPage: (Int) -> Unit, onExport: () -> Unit, onEdit: () -> Unit, onCapability: (Capability) -> Unit, onArchive: ((CallDetail) -> Unit)? = null
) {
    var dateDialog by rememberSaveable { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    ScreenFrame("号码报表", onBack, actions = { TextButton(onClick = onEdit) { Text("编辑档案") } }) {
        LazyColumn(Modifier.testTag("report-list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedButton(onClick = { dateDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("${query.range.startDate} 至 ${query.range.endDateInclusive}")
                }
                LoadFeedback(state, onRetry)
                MutationFeedback(mutation, onCapability)
            }
            state.data?.let { report ->
                item { CallerCard(report.profile) }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("区间汇总", style = MaterialTheme.typography.titleMedium)
                            Text("来电总数 ${report.totals.totalCalls}", style = MaterialTheme.typography.headlineSmall)
                            Text("工作时间 ${report.totals.workingCalls} · 非工作时间 ${report.totals.offHoursCalls}")
                            Text("已知通话时长 ${UiFormat.duration(report.totals.knownDurationSeconds)}")
                            Text("时长未知 ${report.totals.unknownDurationCount} 次 · 待确认 ${report.totals.pendingCount} 次")
                            Text("未接 ${report.totals.missedCount} 次 · 拒接 ${report.totals.rejectedCount} 次")
                            ScoreBadge(report.score.points)
                            Text(UiFormat.scoreReason(report.score), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { QualityNotice(report.quality, onCapability) }
                item {
                    OutlinedButton(onClick = onExport, enabled = !mutation.busy, modifier = Modifier.fillMaxWidth()) { Text("导出所选区间全部记录（CSV）") }
                    SectionTitle("逐次明细")
                    Box {
                        TextButton(onClick = { filterMenu = true }) { Text("${UiLabels.filter(query.filter)} ▾") }
                        DropdownMenu(filterMenu, { filterMenu = false }) {
                            CallFilter.entries.forEach { filter -> DropdownMenuItem(text = { Text(UiLabels.filter(filter)) },
                                onClick = { filterMenu = false; onFilter(filter) }) }
                        }
                    }
                    Text("筛选和分页仅影响明细", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (report.details.items.isEmpty() && !state.loading) EmptyContent("所选条件下暂无通话明细")
                }
                items(report.details.items, key = { it.id }) { CallDetailCard(it, onArchive) }
                item { PageControls(report.details.pageIndex, report.details.pageSize, report.details.totalItems, state.loading, onPage) }
            }
        }
    }
    if (dateDialog) ReportRangeDialog(query.range, { dateDialog = false }) { dateDialog = false; onRange(it) }
}

@Composable private fun QualityNotice(quality: DataQuality, onCapability: (Capability) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!quality.statisticsEnabled) Text("来电统计当前已关闭，历史记录仍保留")
        if (quality.callLogAccess != AccessState.GRANTED && quality.callLogAccess != AccessState.NOT_REQUIRED) {
            Text("通话记录权限：${UiLabels.access(quality.callLogAccess)}")
            TextButton(onClick = { onCapability(Capability.CALL_LOG) }) { Text("设置通话记录权限") }
        }
        if (quality.hasUnknownDuration) Text("部分通话时长未知，汇总仅累加已知时长")
        if (quality.hasPendingResults) Text("部分通话结果尚待确认")
        if (quality.hasUnresolvedRecords) Text("存在待核对记录，请查看逐次明细")
        if (quality.hasUnverifiedCalendar) Text("部分日期的日历信息尚未核实")
    }
}

@Composable fun CallDetailCard(call: CallDetail, onArchive: ((CallDetail) -> Unit)? = null) {
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(UiFormat.time(call.startedAtEpochMillis, ZoneId.of(call.classification.zoneId)), style = MaterialTheme.typography.titleSmall)
            Text(UiFormat.result(call.result), style = MaterialTheme.typography.bodyLarge)
            Text(UiFormat.duration(call.durationSeconds))
            Text(UiFormat.workReason(call.classification.reason))
            Text(UiFormat.decision(call.decision), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!call.counted) Text("未计入汇总", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (call.corrected) Text("工作分类已更正；当时响铃处理保留原始事实", color = MaterialTheme.colorScheme.primary)
            Text(if (expanded) "收起记录详情" else "展开记录详情", style = MaterialTheme.typography.labelMedium)
            onArchive?.let { callback -> TextButton(onClick = { callback(call) }) { Text("录音与分析") } }
            if (expanded) {
                Text("数据来源：${when (call.source) { CallSource.SCREENING -> "来电筛选"; CallSource.SYSTEM_CALL_LOG -> "系统通话记录"; CallSource.MERGED -> "已关联核对"; CallSource.UNKNOWN -> "未知" }}")
                Text("原始工作分类：${UiFormat.workReason(call.originalClassification.reason)}")
                Text("工作配置版本 ${call.classification.configVersion} · 请假版本 ${call.classification.leaveVersion}")
                call.classification.holidayName?.let { Text("节日：$it") }
                call.classification.calendarVersion?.let { Text("日历版本：$it") }
                call.decisionNote?.let { Text(it) }
            }
        }
    }
}

@Composable private fun ReportRangeDialog(value: DateRange, onDismiss: () -> Unit, onSave: (DateRange) -> Unit) {
    DateRangeSelectionDialog("报表日期区间", value.startDate, value.endDateInclusive, onDismiss,
        { start, end -> onSave(DateRange(start, end, value.zoneId)) }, confirmLabel = "查询")
}
