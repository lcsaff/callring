package com.greatwall.callring.ui.feature.report

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.recording.RecordingPlaybackSession
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.shared.*
import com.greatwall.callring.ui.feature.callers.CallHistoryRow
import com.greatwall.callring.ui.feature.callers.CallerProfileField
import com.greatwall.callring.ui.feature.recording.RecordingFeedback
import com.greatwall.callring.ui.format.*
import com.greatwall.callring.ui.state.*
import java.time.ZoneId

private const val ProfileKey = "profile"
private const val ReportKey = "report"
private const val ReportDetailPrefix = "report-detail-"
private const val ReportPagingKey = "report-paging"
private const val HistoryKey = "history"
private const val HistoryCallPrefix = "history-call-"
private const val HistoryMoreKey = "history-more"

internal data class ReportHistory(
    val state: LoadState<CallHistoryPage>,
    val playing: LoadState<RecordingPlaybackSession?>,
    val playback: MutationState,
    val onRefresh: () -> Unit,
    val onMore: () -> Unit,
    val onDetail: (Long) -> Unit,
    val onPlayback: (Long, Long) -> Unit,
    val onTranscript: (CallHistoryEntry) -> Unit,
)

@Composable internal fun ReportScreen(
    state: LoadState<CallerReport>, query: ReportQuery, mutation: MutationState,
    onBack: () -> Unit, onRetry: () -> Unit, onRange: (DateRange) -> Unit, onFilter: (CallFilter) -> Unit,
    onPage: (Int) -> Unit, onExport: () -> Unit, onEdit: () -> Unit, onCapability: (Capability) -> Unit,
    onArchive: ((CallDetail) -> Unit)? = null, onEditField: ((CallerProfileField) -> Unit)? = null,
    history: ReportHistory? = null,
) {
    var profileExpanded by rememberSaveable(query.number) { mutableStateOf(false) }
    var reportExpanded by rememberSaveable(query.number) { mutableStateOf(false) }
    var callsExpanded by rememberSaveable(query.number) { mutableStateOf(false) }
    var dateDialog by rememberSaveable(query.number) { mutableStateOf(false) }
    val report = state.data
    val profile = report?.profile
    val page = history?.state?.data
    val summary = UiFormat.callerSummary(query.number, profile?.name, profile?.region,
        profile?.selfReportedPosition, profile?.position?.name)
    val reportCalls = remember(reportExpanded, report?.details?.items) {
        if (reportExpanded) report?.details?.items.orEmpty().associateBy { "$ReportDetailPrefix${it.id}" } else emptyMap()
    }
    val historyCalls = remember(callsExpanded, page?.entries) {
        val rows = page?.entries.orEmpty()
        (if (callsExpanded) rows else rows.take(3)).associateBy { "$HistoryCallPrefix${it.call.id}" }
    }
    val hasReportPagination = reportExpanded && report != null && report.details.totalItems > report.details.pageSize
    val hasMoreCalls = callsExpanded && page?.nextCursor != null
    val keys = remember(reportCalls, hasReportPagination, historyCalls, hasMoreCalls) {
        buildList {
            add(ProfileKey)
            add(ReportKey)
            addAll(reportCalls.keys)
            if (hasReportPagination) add(ReportPagingKey)
            add(HistoryKey)
            addAll(historyCalls.keys)
            if (hasMoreCalls) add(HistoryMoreKey)
        }
    }
    val listState = rememberReportScroll(query.number, keys,
        reportReady = state.data != null,
        historyReady = history == null || history.state.loaded)
    val profileVisible by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.totalItemsCount == 0 || layout.visibleItemsInfo.any {
                it.key == ProfileKey && it.offset < layout.viewportEndOffset &&
                    it.offset + it.size > layout.viewportStartOffset
            }
        }
    }

    ScreenFrame(if (profileVisible) "号码档案详情" else summary, onBack) {
        LazyColumn(Modifier.fillMaxSize().testTag("report-list"), state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(keys, key = { it }) { key ->
                when (key) {
                    ProfileKey -> Column {
                        ExpandableSectionHeading("号码信息", profileExpanded) { profileExpanded = !profileExpanded }
                        if (profileExpanded) {
                            profile?.let { CallerProfileDetails(it, onEditField) }
                            TextButton(onClick = onEdit) { Text("编辑档案") }
                        } else Text(summary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                    }
                    ReportKey -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ExpandableSectionHeading("报表信息", reportExpanded) { reportExpanded = !reportExpanded }
                        LoadFeedback(state, onRetry)
                        MutationFeedback(mutation, onCapability)
                        if (reportExpanded) {
                            OutlinedButton(onClick = { dateDialog = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("${query.range.startDate} 至 ${query.range.endDateInclusive}")
                            }
                            if (report != null) {
                                ReportSummary(report)
                                QualityNotice(report.quality, onCapability)
                                ReportControls(query.filter, mutation.busy, onFilter, onExport)
                                if (report.details.items.isEmpty() && !state.loading) EmptyContent("所选条件下暂无通话明细")
                            }
                        } else if (report != null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("来电总数 ${report.totals.totalCalls}", Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium)
                                ReportScore(report.score.points)
                            }
                        }
                    }
                    ReportPagingKey -> report?.details?.let {
                        PageControls(it.pageIndex, it.pageSize, it.totalItems, state.loading, onPage)
                    }
                    HistoryKey -> Column {
                        ExpandableSectionHeading("通话记录", callsExpanded) { callsExpanded = !callsExpanded }
                        history?.let {
                            LoadFeedback(it.state, it.onRefresh)
                            RecordingFeedback(it.playing.error, it.playback)
                            if (it.state.loaded && it.state.error == null && page?.entries.isNullOrEmpty()) {
                                EmptyContent("暂无通话记录")
                            }
                        }
                    }
                    HistoryMoreKey -> if (history != null) {
                        TextButton(onClick = history.onMore, enabled = !history.state.loading && history.state.error == null) {
                            Text(if (history.state.loading) "正在加载…" else "加载更多")
                        }
                    }
                    else -> {
                        reportCalls[key]?.let { CallDetailCard(it, onArchive) }
                        if (history != null) historyCalls[key]?.let {
                            CallHistoryRow(it, history.playing.data?.callId, history.playback.busy, history.onDetail,
                                history.onPlayback, history.onTranscript, showCallerSummary = false)
                        }
                    }
                }
            }
        }
    }
    if (dateDialog) ReportRangeDialog(query.range, { dateDialog = false }) { dateDialog = false; onRange(it) }
}

@Composable
private fun rememberReportScroll(number: String, keys: List<String>, reportReady: Boolean, historyReady: Boolean): LazyListState {
    val list = remember(number) { LazyListState() }
    var anchorKey by rememberSaveable(number) { mutableStateOf(ProfileKey) }
    var anchorOffset by rememberSaveable(number) { mutableIntStateOf(0) }
    var restoring by remember(number) { mutableStateOf(true) }
    val currentKeys by rememberUpdatedState(keys)
    LaunchedEffect(list, keys, reportReady, historyReady) {
        val isHistoryAnchor = anchorKey.startsWith(HistoryCallPrefix) || anchorKey == HistoryMoreKey
        val index = keys.indexOf(anchorKey)
        val ready = if (isHistoryAnchor) historyReady else reportReady
        if (restoring && (index >= 0 || ready)) {
            val fallback = if (isHistoryAnchor) HistoryKey else ReportKey
            list.scrollToItem(if (index >= 0) index else keys.indexOf(fallback), if (index >= 0) anchorOffset else 0)
            restoring = false
        } else if (!restoring && anchorKey.startsWith(HistoryCallPrefix) && index < 0 && historyReady) {
            anchorKey = HistoryKey
            anchorOffset = 0
            list.scrollToItem(keys.indexOf(HistoryKey))
        }
    }
    LaunchedEffect(list) {
        snapshotFlow {
            if (restoring) null else {
                val index = list.firstVisibleItemIndex
                val key = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key as? String
                key?.let { it to list.firstVisibleItemScrollOffset }
            }
        }.collect { anchor ->
            // Keep a deleted call's anchor until the other effect can return to the call section.
            if (anchor != null && (!anchorKey.startsWith(HistoryCallPrefix) || anchorKey in currentKeys)) {
                anchorKey = anchor.first
                anchorOffset = anchor.second
            }
        }
    }
    return list
}

@Composable private fun ExpandableSectionHeading(title: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(title, Modifier.weight(1f))
        IconButton(onClick = onToggle, modifier = Modifier.semantics {
            stateDescription = if (expanded) "已展开" else "已收起"
        }) {
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起$title" else "展开$title")
        }
    }
}

@Composable private fun CallerProfileDetails(profile: CallerProfile, onEdit: ((CallerProfileField) -> Unit)?) {
    val configured = profile.position?.let {
        "${it.name} · ${it.level} 级" + if (it.active) "" else "（已停用）"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingRow("号码", profile.number)
        ProfileFieldRow(CallerProfileField.NAME, profile.name, profile, onEdit)
        ProfileFieldRow(CallerProfileField.DEPARTMENT, profile.department, profile, onEdit)
        ProfileFieldRow(CallerProfileField.SELF_REPORTED_POSITION, profile.selfReportedPosition, profile, onEdit)
        ProfileFieldRow(CallerProfileField.REGION, profile.region, profile, onEdit)
        ProfileFieldRow(CallerProfileField.POSITION, configured.orEmpty(), profile, onEdit)
        SettingRow("上下级关系", UiFormat.position(profile.relation))
        ProfileFieldRow(CallerProfileField.WHITELIST, if (profile.whitelisted) "已启用" else "未启用", profile, onEdit)
    }
}

@Composable private fun ProfileFieldRow(
    field: CallerProfileField, value: String, profile: CallerProfile, onEdit: ((CallerProfileField) -> Unit)?,
) {
    SettingRow(field.label, value.ifBlank { "未设置" }, trailing = {
        if (onEdit != null) IconButton(onClick = { onEdit(field) }) {
            Icon(Icons.Filled.Edit, contentDescription = "编辑${field.label}")
        }
    })
    profile.identityMetadata[field.key]?.let { metadata ->
        if (metadata.disputed) Text("有异议", color = MaterialTheme.colorScheme.error)
        if (metadata.evidence.isNotBlank()) {
            Text(metadata.archiveId?.let { "来源（通话归档 $it）" } ?: "来源", style = MaterialTheme.typography.labelMedium)
            Text(metadata.evidence, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ReportScore(points: Int?) {
    if (points == null) Text("评分未知", style = MaterialTheme.typography.labelLarge)
    else ScoreBadge(points)
}

@Composable private fun ReportSummary(report: CallerReport) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("区间汇总", style = MaterialTheme.typography.titleMedium)
            Text("来电总数 ${report.totals.totalCalls}", style = MaterialTheme.typography.headlineSmall)
            Text("工作时间 ${report.totals.workingCalls} · 非工作时间 ${report.totals.offHoursCalls}")
            Text("已知通话时长 ${UiFormat.duration(report.totals.knownDurationSeconds)}")
            Text("时长未知 ${report.totals.unknownDurationCount} 次 · 待确认 ${report.totals.pendingCount} 次")
            Text("未接 ${report.totals.missedCount} 次 · 拒接 ${report.totals.rejectedCount} 次")
            ReportScore(report.score.points)
            Text(UiFormat.scoreReason(report.score), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ReportControls(
    currentFilter: CallFilter, exporting: Boolean, onFilter: (CallFilter) -> Unit, onExport: () -> Unit,
) {
    var filterMenu by remember { mutableStateOf(false) }
    OutlinedButton(onClick = onExport, enabled = !exporting, modifier = Modifier.fillMaxWidth()) {
        Text("导出所选区间全部记录（CSV）")
    }
    SectionTitle("逐次明细")
    Box {
        TextButton(onClick = { filterMenu = true }) { Text("${UiLabels.filter(currentFilter)} ▾") }
        DropdownMenu(filterMenu, { filterMenu = false }) {
            CallFilter.entries.forEach { filter ->
                DropdownMenuItem(text = { Text(UiLabels.filter(filter)) }, onClick = { filterMenu = false; onFilter(filter) })
            }
        }
    }
    Text("筛选和分页仅影响明细", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            onArchive?.let { callback -> TextButton(onClick = { callback(call) }) { Text("通话详情") } }
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
