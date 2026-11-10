package com.greatwall.callring.ui.feature.recording

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.ApiError
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.shared.LoadFeedback
import com.greatwall.callring.ui.format.UiFormat
import com.greatwall.callring.ui.state.*
import java.time.ZoneId

@Composable fun ArchiveScreen(state: LoadState<CallArchive?>, files: LoadState<List<RecordingFile>>, mutation: MutationState,
    onBack: () -> Unit, onRefresh: () -> Unit, onImport: () -> Unit, onList: () -> Unit, onSelect: (RecordingFile) -> Unit,
    onRetry: () -> Unit, onDelete: () -> Unit, onPlay: () -> Unit, onStop: () -> Unit, onEdit: () -> Unit,
    zone: LoadState<ZoneId> = LoadState(), onRetryZone: () -> Unit = {},
    onImportOffline: () -> Unit = {}, onTranscribeOffline: () -> Unit = {}, onCancelOffline: (Long) -> Unit = {},
    history: LoadState<CallHistoryEntry>? = null, isPlaying: Boolean = false,
    playback: MutationState = MutationState(), playbackError: ApiError? = null, onAnalyze: () -> Unit = {},
    suggestions: ArchiveSuggestionsState = ArchiveSuggestionsState(), onRetrySuggestions: () -> Unit = {},
    onConfirmSuggestions: (CallerSuggestions, Set<CallerSuggestionField>) -> Unit = { _, _ -> },
) {
    var deletion by remember { mutableStateOf(false) }
    val call = history?.data?.call
    val archive = state.data.takeIf { history == null || call != null }
    ScreenFrame(if (history == null) "通话录音归档" else "通话详情", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (history != null) LoadFeedback(history, onRefresh)
            else RecordingTimeZoneFeedback(zone, onRetryZone)
            call?.let { details ->
                Text("号码：${details.number}")
                Text(UiFormat.fullTime(details.startedAtEpochMillis, ZoneId.of(details.classification.zoneId)))
                Text("时长：${details.durationSeconds?.let(UiFormat::duration) ?: "未知"}")
                Text("工作分类：${UiFormat.workReason(details.classification.reason)}")
                Text(UiFormat.result(details.result))
            }
            RecordingFeedback(state.error ?: files.error, mutation)
            RecordingFeedback(playbackError, playback)
            if (state.loading && state.data == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (archive == null && (state.loaded || state.error != null) && (history == null || call != null)) {
                val availability = history?.data?.recording?.availability
                Text(when {
                    availability == CallArchiveAvailability.INDEXING -> "正在读取归档状态"
                    availability == CallArchiveAvailability.UNREADABLE || state.error != null -> "录音归档无法读取"
                    else -> "等待录音，尚无本次通话归档"
                })
                if (history != null && availability == CallArchiveAvailability.NONE) Text("转写：暂无")
            }
            archive?.let { item ->
                if (call == null) {
                    Text("号码：${item.number}")
                    zone.data?.let { Text(UiFormat.time(item.startedAtEpochMillis, it)) }
                }
                val hasAudio = item.audioPath != null
                val hasTranscript = item.transcript.isNotBlank()
                Text(if (hasAudio) "录音：可播放" else "录音：缺少音频")
                Text(if (hasTranscript) "转写：已有文字" else "转写：暂无")
                val source = when (item.source) {
                    RecordingSource.SYSTEM -> "系统录音"
                    RecordingSource.IMPORTED -> "手动导入"
                    RecordingSource.MICROPHONE -> "实验性麦克风"
                }
                Text("来源：$source")
                if (hasAudio) Text("质量：${recordingMessage(item.audioQuality)}")
                Text("状态：${archiveStatusLabel(item.status)}")
                if (item.status == ArchiveStatus.COMPLETE && item.enrichmentBaseline?.eligible == false)
                    Text("此号码暂不支持建立档案，转写与分析已保留。")
                item.error?.let { Text(recordingMessage(it), color = MaterialTheme.colorScheme.error) }
                if (hasAudio || isPlaying) {
                    TextButton(onClick = if (isPlaying) onStop else onPlay, enabled = !playback.busy) {
                        Text(if (isPlaying) "停止播放" else "播放")
                    }
                }
                val offline = item.offlineTranscription
                val offlineActive = offline != null && offline.phase != OfflineTranscriptionPhase.FAILED
                val processing = item.status.processing()
                if (offlineActive) {
                    Text(when (offline.phase) {
                        OfflineTranscriptionPhase.PREPARING -> "准备离线模型"
                        OfflineTranscriptionPhase.TRANSCRIBING -> "正在离线转写"
                        OfflineTranscriptionPhase.CANCELLING -> "正在取消离线转写"
                        OfflineTranscriptionPhase.FAILED -> ""
                    })
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { onCancelOffline(offline.id) },
                        enabled = offline.phase != OfflineTranscriptionPhase.CANCELLING) { Text("取消离线转写") }
                } else if (!hasTranscript && hasAudio) {
                    offline?.error?.let { Text(recordingMessage(it), color = MaterialTheme.colorScheme.error) }
                    Button(onClick = onTranscribeOffline, enabled = !processing && !mutation.busy) {
                        Text(if (offline == null) "转为文本" else "重试离线转写")
                    }
                }
                if (hasTranscript) {
                    var expanded by rememberSaveable(item.callId) { mutableStateOf(false) }
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起转写" else "展开转写") }
                    if (expanded) SelectionContainer { Text(item.transcript) }
                    Text("AI 分析会将转写文字发送到配置的服务，可能按服务商价格收费。")
                    Button(onClick = onAnalyze, enabled = !processing && !offlineActive && !mutation.busy && !suggestions.mutation.busy) {
                        Text("AI 分析")
                    }
                }
                if (item.status == ArchiveStatus.ENRICHMENT_FAILED)
                    Button(onClick = onRetry, enabled = !mutation.busy) { Text("重试档案补全（不调用模型）") }
                item.analysis?.let { analysis ->
                    Text("对方自述身份（需核对）", style = MaterialTheme.typography.titleMedium)
                    Text("姓名：${analysis.name.ifBlank { "未提及" }}\n单位：${analysis.organization.ifBlank { "未提及" }}\n职位：${analysis.position.ifBlank { "未提及" }}")
                    Text("部门：${analysis.department.ifBlank { "未提及" }}\n所属地区：${analysis.region.ifBlank { "未提及" }}")
                    Text("自述依据：${analysis.evidence.ifBlank { "无" }}")
                    Text("模型推测（未核实）：${analysis.inferredIdentity.ifBlank { "无" }}")
                    Text("来电目的：${analysis.purpose}")
                    Text("摘要：${analysis.summary}")
                    analysis.actionItems.forEach { Text("• $it") }
                    Text("AI 结果可能不准确，不能作为已经核实的身份。")
                    TextButton(onClick = onEdit, enabled = item.enrichmentBaseline?.eligible != false) { Text("编辑档案") }
                }
                if (item.manualAnalysisState == ManualAnalysisState.PENDING_CONFIRMATION && !processing)
                    CallerSuggestionsSection(suggestions, onRetrySuggestions, onConfirmSuggestions, enabled = !mutation.busy)
                else if (item.manualAnalysisState == ManualAnalysisState.CONFIRMED)
                    Text("已确认写入档案")
            }
            if (history == null || call != null) {
                TextButton(onClick = onRefresh) { Text("刷新状态") }
                Text("可导入已有录音，或选择授权目录内的系统录音；请核对文件与本次通话相符。")
                OutlinedButton(onClick = onImport, enabled = !mutation.busy) { Text("导入音频文件") }
                OutlinedButton(onClick = onImportOffline, enabled = !mutation.busy) { Text("导入用于离线转写") }
                OutlinedButton(onClick = onList, enabled = !mutation.busy) { Text("选择系统录音") }
                if (files.loading) Text("正在读取目录…")
                files.data?.let { entries ->
                    if (entries.isEmpty()) Text("目录中没有可选择的录音；请先在录音设置中授权目录。")
                    entries.forEach { file -> TextButton(onClick = { onSelect(file) }, enabled = !mutation.busy) { Text(file.name) } }
                }
            }
            if (archive != null) TextButton(onClick = { deletion = true }, enabled = !mutation.busy) { Text("删除本次归档") }
        }
    }
    if (deletion) ConfirmationDialog(
        title = "删除本次归档？", message = "删除应用内的录音、转写和分析结果。原始导入文件保留。",
        confirmLabel = "删除", onConfirm = { deletion = false; onDelete() }, onDismiss = { deletion = false },
    )
}
