package com.greatwall.callring.ui.feature.recording

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.state.*

@Composable fun ArchiveScreen(state: LoadState<CallArchive?>, files: LoadState<List<RecordingFile>>, mutation: MutationState,
    onBack: () -> Unit, onRefresh: () -> Unit, onImport: () -> Unit, onList: () -> Unit, onSelect: (RecordingFile) -> Unit,
    onRetry: () -> Unit, onDelete: () -> Unit, onPlay: () -> Unit, onStop: () -> Unit, onEdit: () -> Unit,
    zone: LoadState<java.time.ZoneId> = LoadState(), onRetryZone: () -> Unit = {},
    onImportOffline: () -> Unit = {}, onTranscribeOffline: () -> Unit = {}, onCancelOffline: (Long) -> Unit = {}) {
    var deletion by remember { mutableStateOf(false) }
    ScreenFrame("通话录音归档", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RecordingFeedback(state.error ?: files.error, mutation)
            RecordingTimeZoneFeedback(zone, onRetryZone)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
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
            val archive = state.data
            if (archive == null && state.loaded) Text("等待录音，尚无本次通话归档")
            archive?.let { item ->
                Text("号码：${item.number}")
                zone.data?.let { Text(com.greatwall.callring.ui.format.UiFormat.time(item.startedAtEpochMillis, it)) }
                Text("来源：${when (item.source) { RecordingSource.SYSTEM -> "系统录音"; RecordingSource.IMPORTED -> "手动导入"; RecordingSource.MICROPHONE -> "实验性麦克风" }}")
                Text("质量：${recordingMessage(item.audioQuality)}")
                Text("状态：${archiveStatusLabel(item.status)}")
                if (item.status == ArchiveStatus.COMPLETE && item.enrichmentBaseline?.eligible == false)
                    Text("此号码暂不支持建立档案，转写与分析已保留。")
                item.error?.let { Text(recordingMessage(it), color = MaterialTheme.colorScheme.error) }
                val offline = item.offlineTranscription
                val offlineActive = offline != null && offline.phase != OfflineTranscriptionPhase.FAILED
                if (offlineActive && offline != null) {
                    Text(when (offline.phase) {
                        OfflineTranscriptionPhase.PREPARING -> "准备离线模型"
                        OfflineTranscriptionPhase.TRANSCRIBING -> "正在离线转写"
                        OfflineTranscriptionPhase.CANCELLING -> "正在取消离线转写"
                        OfflineTranscriptionPhase.FAILED -> ""
                    })
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { onCancelOffline(offline.id) },
                        enabled = offline.phase != OfflineTranscriptionPhase.CANCELLING) { Text("取消离线转写") }
                } else if (item.transcript.isBlank()) {
                    offline?.error?.let { Text(recordingMessage(it), color = MaterialTheme.colorScheme.error) }
                    val queued = item.status in setOf(ArchiveStatus.QUEUED, ArchiveStatus.TRANSCRIBING,
                        ArchiveStatus.ANALYZING, ArchiveStatus.ENRICHING)
                    Button(onClick = onTranscribeOffline, enabled = item.audioPath != null && !queued && !mutation.busy) {
                        Text(if (offline == null) "离线转写" else "重试离线转写")
                    }
                }
                if (item.audioPath != null) {
                    Row { TextButton(onClick = onPlay, enabled = !mutation.busy) { Text("播放") }; TextButton(onClick = onStop) { Text("停止播放") } }
                    Text(if (item.transcript.isBlank()) "分析会将录音发送到配置的转写服务，将文字发送到配置的分析服务，可能按服务商价格收费。"
                        else "分析会将已有文字发送到配置的分析服务，可能按服务商价格收费。")
                    if (item.status !in setOf(ArchiveStatus.ENRICHING, ArchiveStatus.ENRICHMENT_FAILED))
                        Button(onClick = onRetry, enabled = !mutation.busy && !offlineActive) { Text("分析 / 重试") }
                }
                if (item.status == ArchiveStatus.ENRICHMENT_FAILED)
                    Button(onClick = onRetry, enabled = !mutation.busy) { Text("重试档案补全（不调用模型）") }
                Text("转写文字", style = MaterialTheme.typography.titleMedium)
                Text(item.transcript.ifBlank { "尚无转写结果" })
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
                TextButton(onClick = { deletion = true }, enabled = !mutation.busy) { Text("删除本次归档") }
            }
        }
    }
    if (deletion) AlertDialog(onDismissRequest = { deletion = false }, title = { Text("删除本次归档？") }, text = { Text("删除应用内的录音、转写和分析结果。原始导入文件保留。") }, confirmButton = { TextButton(onClick = { deletion = false; onDelete() }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deletion = false }) { Text("取消") } })
}
