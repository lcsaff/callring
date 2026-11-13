package com.greatwall.callring.ui.feature.recording

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.callers.CallHistoryEntry
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.designsystem.components.SectionTitle
import com.greatwall.callring.ui.format.UiFormat
import com.greatwall.callring.ui.state.MutationState
import java.time.ZoneId

@Composable internal fun ArchiveContentSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("archive-section-$title"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { SectionTitle(title); content() }
    }
}

@Composable internal fun ArchiveCallHeader(entry: CallHistoryEntry?, archive: CallArchive?, zone: ZoneId?, onEdit: () -> Unit) {
    val call = entry?.call
    val number = call?.number ?: archive?.number.orEmpty()
    val name = entry?.callerName?.takeIf { it.isNotBlank() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name ?: number, style = MaterialTheme.typography.headlineSmall)
                if (name != null) Text("号码：$number", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onEdit, enabled = archive?.enrichmentBaseline?.eligible != false) { Icon(Icons.Filled.Person, "编辑档案") }
        }
        if (call != null) {
            Text(UiFormat.fullTime(call.startedAtEpochMillis, ZoneId.of(call.classification.zoneId)), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(UiFormat.direction(call.direction)); Text(UiFormat.result(call.result))
                Text("时长：${call.durationSeconds?.let(UiFormat::duration) ?: "未知"}")
            }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text("工作分类：${UiFormat.workReason(call.classification.reason)}", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
        } else if (archive != null) {
            Text(UiFormat.direction(archive.direction), style = MaterialTheme.typography.bodyMedium)
            zone?.let { Text(UiFormat.time(archive.startedAtEpochMillis, it), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable internal fun ArchiveAudio(archive: CallArchive, playing: Boolean, busy: Boolean, onPlay: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (archive.audioPath != null || playing) FilledTonalIconButton(if (playing) onStop else onPlay, enabled = !busy) {
            Icon(if (playing) Icons.Filled.Close else Icons.Filled.PlayArrow, if (playing) "停止播放" else "播放录音")
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when (archive.source) { RecordingSource.SYSTEM -> "系统录音"; RecordingSource.IMPORTED -> "手动导入"; RecordingSource.MICROPHONE -> "实验性麦克风" })
            Text(if (archive.audioPath == null) "缺少音频" else recordingMessage(archive.audioQuality), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Suppress("DEPRECATION")
@Composable internal fun ArchiveTranscript(archive: CallArchive) {
    var expanded by rememberSaveable(archive.callId) { mutableStateOf(false) }
    val text = archive.transcript
    val preview = remember(text) { if (text.codePointCount(0, text.length) <= 180) text else text.substring(0, text.offsetByCodePoints(0, 180)) + "…" }
    SelectionContainer { Text(if (expanded) text else preview, style = MaterialTheme.typography.bodyLarge) }
    val clipboard = LocalClipboardManager.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (preview != text) TextButton({ expanded = !expanded }) { Text(if (expanded) "收起全文" else "展开全文") }
        TextButton({ clipboard.setText(AnnotatedString(text)) }) { Text("复制全文") }
    }
}

@Composable internal fun ArchiveDisclosure(label: String, key: Any?, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    TextButton({ expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f)); Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium)
    }
    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable internal fun ArchiveError(message: String) { Text(recordingMessage(message), color = MaterialTheme.colorScheme.error) }

@Composable internal fun ArchiveAnalysisSection(archive: CallArchive, mutation: MutationState, busy: Boolean,
    suggestions: ArchiveSuggestionsState, onAnalyze: () -> Unit, onRetry: () -> Unit, onEdit: () -> Unit,
    onRetrySuggestions: () -> Unit, onConfirmSuggestions: (CallerSuggestions, Set<CallerSuggestionField>) -> Unit,
    showFeedback: Boolean = false, error: String? = archive.error,
) {
    ArchiveContentSection(if (archive.analysis == null) "内容分析" else "分析结果") {
        if (showFeedback) RecordingFeedback(null, mutation)
        if (error != null || archive.status in setOf(ArchiveStatus.QUEUED, ArchiveStatus.ANALYZING, ArchiveStatus.ENRICHING, ArchiveStatus.ENRICHMENT_FAILED) || archive.status == ArchiveStatus.FAILED && archive.transcript.isNotBlank()) {
            Text(archiveStatusLabel(archive.status)); error?.let { ArchiveError(it) }
            if (error != null && archive.transcript.isBlank()) Text("这是此前自动处理保存的失败状态，本地转写仍可单独重试。", style = MaterialTheme.typography.bodySmall)
        }
        val ineligible = archive.enrichmentBaseline?.eligible == false
        if (ineligible) Text("此号码暂不支持建立档案，转写与分析已保留。")
        archive.analysis?.let { result ->
            if (result.summary.isNotBlank()) Text(result.summary, style = MaterialTheme.typography.bodyLarge)
            if (result.actionItems.isNotEmpty()) { Text("待办", style = MaterialTheme.typography.titleSmall); result.actionItems.forEach { Text("• $it") } }
            ArchiveDisclosure("分析依据与其他信息", archive.callId) {
                if (result.purpose.isNotBlank()) Text("来电目的：${result.purpose}")
                listOf("姓名" to result.name, "单位" to result.organization, "自述职位" to result.position, "部门" to result.department, "所属地区" to result.region)
                    .filter { it.second.isNotBlank() }.forEach { (label, value) -> Text("$label：$value") }
                if (result.evidence.isNotBlank()) Text("自述依据：${result.evidence}")
                if (result.inferredIdentity.isNotBlank()) Text("模型推测（未核实）：${result.inferredIdentity}")
                Text("AI 结果可能不准确，不能作为已经核实的身份。", style = MaterialTheme.typography.bodySmall)
                TextButton(onEdit, enabled = !ineligible) { Text("编辑档案") }
            }
        }
        if (archive.manualAnalysisState == ManualAnalysisState.PENDING_CONFIRMATION && !busy) {
            HorizontalDivider()
            ArchiveDisclosure("档案建议 · 待确认", archive.callId) { CallerSuggestionsSection(suggestions, onRetrySuggestions, onConfirmSuggestions, enabled = !mutation.busy) }
        } else if (archive.manualAnalysisState == ManualAnalysisState.CONFIRMED) Text("已确认写入档案")
        if (archive.status == ArchiveStatus.ENRICHMENT_FAILED) OutlinedButton(onRetry, enabled = !mutation.busy) { Text("重试档案补全（不调用模型）") }
        if (archive.transcript.isNotBlank()) {
            if (archive.analysis == null) Button(onAnalyze, enabled = !busy && !mutation.busy && !suggestions.mutation.busy) { Text("AI 分析") }
            else TextButton(onAnalyze, enabled = !busy && !mutation.busy && !suggestions.mutation.busy) { Text("重新分析") }
            Text("仅发送转写文字到分析服务，可能产生费用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
