package com.greatwall.callring.ui.feature.recording

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.ApiError
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.shared.LoadFeedback
import com.greatwall.callring.ui.state.*
import java.time.ZoneId

@Composable fun ArchiveScreen(state: LoadState<CallArchive?>, files: LoadState<List<RecordingFile>>, mutation: MutationState,
    onBack: () -> Unit, onRefresh: () -> Unit, onImport: () -> Unit, onList: () -> Unit, onSelect: (RecordingFile) -> Unit,
    onRetry: () -> Unit, onDelete: () -> Unit, onPlay: () -> Unit, onStop: () -> Unit, onEdit: () -> Unit,
    zone: LoadState<ZoneId> = LoadState(), onRetryZone: () -> Unit = {},
    onTranscribeOffline: () -> Unit = {}, onCancelOffline: (Long) -> Unit = {},
    history: LoadState<CallHistoryEntry>? = null, isPlaying: Boolean = false,
    playback: MutationState = MutationState(), playbackError: ApiError? = null, onAnalyze: () -> Unit = {},
    feedbackSection: ArchiveFeedbackSection = ArchiveFeedbackSection.RECORDING,
    suggestions: ArchiveSuggestionsState = ArchiveSuggestionsState(), onRetrySuggestions: () -> Unit = {},
    onConfirmSuggestions: (CallerSuggestions, Set<CallerSuggestionField>) -> Unit = { _, _ -> },
) {
    val call = history?.data?.call
    val archive = state.data.takeIf { history == null || call != null }
    val identity = call?.id ?: archive?.callId
    var deletion by remember(identity) { mutableStateOf(false) }
    var menu by remember(identity) { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selecting) { if (selecting) onList() }
    val canManage = history == null || call != null
    ScreenFrame(if (history == null) "录音详情" else "通话详情", onBack, actions = {
        Box {
            IconButton({ menu = true }, enabled = canManage) { Icon(Icons.Filled.MoreVert, "更多操作") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("导入音频文件") }, onClick = { menu = false; onImport() }, enabled = !mutation.busy)
                DropdownMenuItem(text = { Text("选择系统录音") }, onClick = { menu = false; selecting = true }, enabled = !mutation.busy)
                DropdownMenuItem(text = { Text("刷新状态") }, onClick = { menu = false; onRefresh() })
                if (archive != null) {
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("删除本次归档", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; deletion = true }, enabled = !mutation.busy)
                }
            }
        }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("archive-content"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (history != null) LoadFeedback(history, onRefresh) else RecordingTimeZoneFeedback(zone, onRetryZone)
            if (call != null || archive != null) ArchiveCallHeader(history?.data, archive, zone.data, onEdit)
            if (canManage) {
                val offline = archive?.offlineTranscription
                val offlineActive = offline != null && offline.phase != OfflineTranscriptionPhase.FAILED
                val processing = archive?.status?.processing() == true
                val hasTranscript = !archive?.transcript.isNullOrBlank()
                val errorSection = archive?.let(::archiveErrorSection)
                val recordingError = archive?.error?.takeIf { errorSection == ArchiveFeedbackSection.RECORDING }
                val transcriptError = archive?.error?.takeIf { errorSection == ArchiveFeedbackSection.TRANSCRIPT }
                val analysisError = archive?.error?.takeIf { errorSection == ArchiveFeedbackSection.ANALYSIS }
                ArchiveContentSection("录音") {
                    if (feedbackSection == ArchiveFeedbackSection.RECORDING) RecordingFeedback(null, mutation)
                    state.error?.let { ArchiveError(it.message) }
                    RecordingFeedback(playbackError, playback)
                    if (archive == null) {
                        when {
                            history?.data?.recording?.availability == CallArchiveAvailability.UNREADABLE || state.error != null -> Text("录音归档无法读取")
                            history?.data?.recording?.availability == CallArchiveAvailability.INDEXING -> Text("正在读取归档状态")
                            state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            state.loaded -> Text("暂无本次通话录音", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onImport, enabled = !mutation.busy) { Text("导入录音") }
                    } else {
                        ArchiveAudio(archive, isPlaying, playback.busy, onPlay, onStop)
                        recordingError?.let { ArchiveError(it) }
                        if (archive.audioPath == null) OutlinedButton(onImport, enabled = !mutation.busy) { Text("导入录音") }
                    }
                }
                if (archive != null) {
                    ArchiveContentSection("转写文本") {
                        if (feedbackSection == ArchiveFeedbackSection.TRANSCRIPT) RecordingFeedback(null, mutation)
                        // The current local run supersedes a saved failure from an older background attempt.
                        if (offline == null && !hasTranscript) transcriptError?.let { ArchiveError(it) }
                        when {
                            offlineActive -> {
                                Text(when (offline.phase) {
                                    OfflineTranscriptionPhase.PREPARING -> "准备本地模型"
                                    OfflineTranscriptionPhase.TRANSCRIBING -> "正在本地转写"
                                    OfflineTranscriptionPhase.CANCELLING -> "正在取消转写"
                                    OfflineTranscriptionPhase.FAILED -> ""
                                })
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                TextButton({ onCancelOffline(offline.id) }, enabled = offline.phase != OfflineTranscriptionPhase.CANCELLING) { Text("取消转写") }
                            }
                            hasTranscript -> ArchiveTranscript(archive)
                            else -> {
                                Text(if (archive.status == ArchiveStatus.TRANSCRIBING) "正在本地转写" else "暂无转写文本")
                                Text("转写在设备内完成，单条音频最长 10 分钟。", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                offline?.error?.let { ArchiveError(it) }
                                if (archive.audioPath != null) BrandButton(onTranscribeOffline, enabled = !processing && !mutation.busy) {
                                    Text(if (offline?.error == null && transcriptError == null) "转为文本" else "重试转写")
                                }
                            }
                        }
                    }
                    if (hasTranscript || archive.analysis != null || processing || analysisError != null || archive.status == ArchiveStatus.ENRICHMENT_FAILED) {
                        ArchiveAnalysisSection(archive, mutation, processing || offlineActive, suggestions,
                            onAnalyze, onRetry, onEdit, onRetrySuggestions, onConfirmSuggestions,
                            showFeedback = feedbackSection == ArchiveFeedbackSection.ANALYSIS,
                            error = analysisError)
                    }
                }
            }
        }
    }
    if (selecting) AlertDialog(onDismissRequest = { selecting = false }, title = { Text("选择系统录音") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (files.loading) Text("正在读取目录…")
            files.error?.let { ArchiveError(it.message); TextButton(onList) { Text("重新读取") } }
            files.data?.let { entries ->
                if (entries.isEmpty() && !files.loading) Text("目录中没有可选择的录音；请先在录音设置中授权目录。")
                entries.forEach { file -> TextButton({ selecting = false; onSelect(file) }, enabled = !mutation.busy) { Text(file.name) } }
            }
        }
    }, confirmButton = { TextButton({ selecting = false }) { Text("关闭") } })
    if (deletion) ConfirmationDialog("删除本次归档？", "删除应用内的录音、转写和分析结果。原始导入文件保留。", "删除",
        onConfirm = { deletion = false; onDelete() }, onDismiss = { deletion = false }, busy = mutation.busy)
}
