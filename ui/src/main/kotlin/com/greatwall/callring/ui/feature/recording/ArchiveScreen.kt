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
            if (call != null || archive != null) {
                ArchiveSection("通话信息") {
                    if (call != null) {
                        Text("号码：${call.number}")
                        Text(UiFormat.fullTime(call.startedAtEpochMillis, ZoneId.of(call.classification.zoneId)))
                        Text("时长：${call.durationSeconds?.let(UiFormat::duration) ?: "未知"}")
                        Text("工作分类：${UiFormat.workReason(call.classification.reason)}")
                        Text(UiFormat.result(call.result))
                    } else if (archive != null) {
                        Text("号码：${archive.number}")
                        zone.data?.let { Text(UiFormat.time(archive.startedAtEpochMillis, it)) }
                    }
                }
            }
            RecordingFeedback(null, mutation)
            if (history == null || call != null) {
                val hasTranscript = !archive?.transcript.isNullOrBlank()
                val analysisStatus = archive?.status?.takeIf { status ->
                    when (status) {
                        ArchiveStatus.QUEUED, ArchiveStatus.ANALYZING,
                        ArchiveStatus.ENRICHING, ArchiveStatus.ENRICHMENT_FAILED -> true
                        ArchiveStatus.FAILED -> hasTranscript
                        else -> false
                    }
                }
                val offline = archive?.offlineTranscription
                val offlineActive = offline != null && offline.phase != OfflineTranscriptionPhase.FAILED
                val processing = archive?.status?.processing() == true
                ArchiveSection("录音与转写") {
                    state.error?.let { ArchiveError(it.message) }
                    RecordingFeedback(playbackError, playback)
                    if (archive == null) {
                        val availability = history?.data?.recording?.availability
                        when {
                            availability == CallArchiveAvailability.UNREADABLE || state.error != null -> Text("录音归档无法读取")
                            availability == CallArchiveAvailability.INDEXING -> Text("正在读取归档状态")
                            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            state.loaded -> Text("等待录音，尚无本次通话归档")
                        }
                    } else {
                        val hasAudio = archive.audioPath != null
                        val source = when (archive.source) {
                            RecordingSource.SYSTEM -> "系统录音"
                            RecordingSource.IMPORTED -> "手动导入"
                            RecordingSource.MICROPHONE -> "实验性麦克风"
                        }
                        Text("来源：$source")
                        if (hasAudio) Text(recordingMessage(archive.audioQuality))
                        else Text("录音：缺少音频")
                        if (analysisStatus == null) {
                            if (archive.status == ArchiveStatus.FAILED) Text(archiveStatusLabel(archive.status))
                            archive.error?.let { ArchiveError(it) }
                        }
                        if (hasAudio || isPlaying) {
                            TextButton(onClick = if (isPlaying) onStop else onPlay, enabled = !playback.busy) {
                                Text(if (isPlaying) "停止播放" else "播放")
                            }
                        }
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
                        } else if (!hasTranscript) {
                            Text(if (archive.status == ArchiveStatus.TRANSCRIBING) archiveStatusLabel(archive.status) else "转写：暂无")
                            offline?.error?.let { ArchiveError(it) }
                            if (hasAudio) Button(onClick = onTranscribeOffline, enabled = !processing && !mutation.busy) {
                                Text(if (offline == null) "转为文本" else "重试离线转写")
                            }
                        }
                        if (hasTranscript) {
                            var expanded by rememberSaveable(archive.callId) { mutableStateOf(false) }
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起转写" else "展开转写") }
                            if (expanded) SelectionContainer { Text(archive.transcript) }
                        }
                    }
                }
                if (archive != null) {
                    val ineligible = archive.status == ArchiveStatus.COMPLETE && archive.enrichmentBaseline?.eligible == false
                    if (hasTranscript || analysisStatus != null || archive.analysis != null || archive.manualAnalysisState != null || ineligible) {
                        ArchiveSection("分析与档案补全") {
                            analysisStatus?.let {
                                Text(archiveStatusLabel(it))
                                archive.error?.let { message -> ArchiveError(message) }
                            }
                            if (ineligible) Text("此号码暂不支持建立档案，转写与分析已保留。")
                            if (hasTranscript) {
                                Text("AI 分析会将转写文字发送到配置的服务，可能按服务商价格收费。")
                                Button(onClick = onAnalyze,
                                    enabled = !processing && !offlineActive && !mutation.busy && !suggestions.mutation.busy) {
                                    Text("AI 分析")
                                }
                            }
                            if (archive.status == ArchiveStatus.ENRICHMENT_FAILED)
                                Button(onClick = onRetry, enabled = !mutation.busy) { Text("重试档案补全（不调用模型）") }
                            archive.analysis?.let { analysis ->
                                ArchiveAnalysisResult(analysis)
                                TextButton(onClick = onEdit, enabled = archive.enrichmentBaseline?.eligible != false) { Text("编辑档案") }
                            }
                            if (archive.manualAnalysisState == ManualAnalysisState.PENDING_CONFIRMATION && !processing)
                                CallerSuggestionsSection(suggestions, onRetrySuggestions, onConfirmSuggestions, enabled = !mutation.busy)
                            else if (archive.manualAnalysisState == ManualAnalysisState.CONFIRMED)
                                Text("已确认写入档案")
                        }
                    }
                }
                ArchiveSection("归档管理") {
                    TextButton(onClick = onRefresh) { Text("刷新状态") }
                    OutlinedButton(onClick = onImport, enabled = !mutation.busy) { Text("导入音频文件") }
                    OutlinedButton(onClick = onImportOffline, enabled = !mutation.busy) { Text("导入用于离线转写") }
                    OutlinedButton(onClick = onList, enabled = !mutation.busy) { Text("选择系统录音") }
                    files.error?.let { ArchiveError(it.message) }
                    if (files.loading) Text("正在读取目录…")
                    files.data?.let { entries ->
                        if (entries.isEmpty()) Text("目录中没有可选择的录音；请先在录音设置中授权目录。")
                        entries.forEach { file -> TextButton(onClick = { onSelect(file) }, enabled = !mutation.busy) { Text(file.name) } }
                    }
                    if (archive != null) TextButton(onClick = { deletion = true }, enabled = !mutation.busy) { Text("删除本次归档") }
                }
            }
        }
    }
    if (deletion) ConfirmationDialog(
        title = "删除本次归档？", message = "删除应用内的录音、转写和分析结果。原始导入文件保留。",
        confirmLabel = "删除", onConfirm = { deletion = false; onDelete() }, onDismiss = { deletion = false },
    )
}

@Composable
private fun ArchiveSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(title)
        content()
    }
}

@Composable
private fun ArchiveError(message: String) {
    Text(recordingMessage(message), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ArchiveAnalysisResult(analysis: CallAnalysis) {
    Text("对方自述身份（需核对）", style = MaterialTheme.typography.titleMedium)
    Text("姓名：${analysis.name.ifBlank { "未提及" }}\n单位：${analysis.organization.ifBlank { "未提及" }}\n职位：${analysis.position.ifBlank { "未提及" }}")
    Text("部门：${analysis.department.ifBlank { "未提及" }}\n所属地区：${analysis.region.ifBlank { "未提及" }}")
    Text("自述依据：${analysis.evidence.ifBlank { "无" }}")
    Text("模型推测（未核实）：${analysis.inferredIdentity.ifBlank { "无" }}")
    Text("来电目的：${analysis.purpose}")
    Text("摘要：${analysis.summary}")
    analysis.actionItems.forEach { Text("• $it") }
    Text("AI 结果可能不准确，不能作为已经核实的身份。")
}
