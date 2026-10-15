package com.greatwall.callring.ui.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.shared.*
import com.greatwall.callring.ui.format.*
import com.greatwall.callring.ui.state.*
import java.time.ZoneId

@Composable fun HomeScreen(
    state: LoadState<HomeData>, mutation: MutationState,
    onRetry: () -> Unit, onStatistics: (Boolean) -> Unit, onOverlay: (Boolean) -> Unit, onRingtone: (Boolean) -> Unit,
    onQuietSettings: () -> Unit, onRuntime: () -> Unit, onRingtoneSettings: () -> Unit,
    onReport: (String) -> Unit, onSettings: () -> Unit,
    latestPreview: LoadState<CallDetail?>, onPreviewLatest: () -> Unit, onPinWidget: () -> Unit
) {
    ScreenFrame("规则来电", actions = { TextButton(onClick = onSettings) { Text("设置") } }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LoadFeedback(state, onRetry)
            state.data?.let { data ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("来电有记录，工作有分寸。", style = MaterialTheme.typography.headlineSmall)
                        Text(UiLabels.quiet(data.quietMode), style = MaterialTheme.typography.titleMedium)
                        Text(UiFormat.workReason(data.currentWork.classification.reason), style = MaterialTheme.typography.bodyLarge)
                        data.currentWork.classification.holidayName?.let { Text(it) }
                        TextButton(onClick = onQuietSettings) { Text("调整免打扰") }
                    }
                }
                MutationFeedback(mutation) { onRuntime() }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        SettingRow("来电统计", "记录开启期间的来电，关闭后保留历史", trailing = {
                            Switch(data.statisticsEnabled, onStatistics, Modifier.semantics { contentDescription = "来电统计" }, enabled = !mutation.busy)
                        })
                        HorizontalDivider()
                        SettingRow("来电悬浮窗", "来电时显示号码档案", trailing = {
                            Switch(data.overlayEnabled, onOverlay, Modifier.semantics { contentDescription = "来电悬浮窗" }, enabled = !mutation.busy)
                        })
                    }
                }
                SectionTitle("最近来电")
                val latest = data.latestCall
                if (latest == null) EmptyContent("暂无来电记录") else Card(onClick = { onReport(latest.number) }) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(latest.number, style = MaterialTheme.typography.titleLarge)
                        Text(UiFormat.time(latest.startedAtEpochMillis, ZoneId.of(latest.classification.zoneId)))
                        Text("${UiFormat.result(latest.result)} · ${UiFormat.duration(latest.durationSeconds)}")
                        Text(UiFormat.decision(latest.decision), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!latest.counted) Text("未计入汇总", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("查看号码报表", color = MaterialTheme.colorScheme.primary)
                    }
                }
                OutlinedButton(onClick = onPreviewLatest, enabled = !latestPreview.loading, modifier = Modifier.fillMaxWidth()) {
                    Text("显示最近来电卡片")
                }
                LoadFeedback(latestPreview, onPreviewLatest)
                if (latestPreview.loaded && latestPreview.data == null && latestPreview.error == null && !latestPreview.loading)
                    Text("没有可显示的最近来电")
                OutlinedButton(onClick = onPinWidget, enabled = data.runtime.pinWidgetSupported, modifier = Modifier.fillMaxWidth()) {
                    Text("添加桌面小组件")
                }
                SectionTitle("规则铃声")
                SettingRow(data.ringtone.displayName, "${if (data.ringtone.source == AudioSource.BUILT_IN) "内置铃声" else "导入音频"} · 试听 ${data.ringtone.previewSeconds} 秒",
                    trailing = { Switch(data.ringtone.enabled, onRingtone, Modifier.semantics { contentDescription = "规则铃声" }, enabled = !mutation.busy) })
                TextButton(onClick = onRingtoneSettings) { Text("管理铃声与试听") }
                OutlinedButton(onClick = onRuntime, modifier = Modifier.fillMaxWidth()) { Text("权限与运行条件") }
                Text("${UiLabels.audibility(data.runtime.audibility)} · ${UiLabels.phoneActivity(data.runtime.phoneActivity)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
