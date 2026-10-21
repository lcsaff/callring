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
    onRetry: () -> Unit, onDelete: () -> Unit, onPlay: () -> Unit, onStop: () -> Unit, onAdopt: () -> Unit, onEdit: () -> Unit) {
    var deletion by remember { mutableStateOf(false) }
    var adoption by remember { mutableStateOf(false) }
    ScreenFrame("通话录音归档", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RecordingFeedback(state.error ?: files.error, mutation)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = onRefresh) { Text("刷新状态") }
            Text("可导入已有录音，或选择授权目录内的系统录音；请核对文件与本次通话相符。")
            OutlinedButton(onClick = onImport, enabled = !mutation.busy) { Text("导入音频文件") }
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
                Text(com.greatwall.callring.ui.format.UiFormat.time(item.startedAtEpochMillis, java.time.ZoneId.systemDefault()))
                Text("来源：${when (item.source) { RecordingSource.SYSTEM -> "系统录音"; RecordingSource.IMPORTED -> "手动导入"; RecordingSource.MICROPHONE -> "实验性麦克风" }}")
                Text("质量：${recordingMessage(item.audioQuality)}")
                Text("状态：${archiveStatusLabel(item.status)}")
                item.error?.let { Text(recordingMessage(it), color = MaterialTheme.colorScheme.error) }
                if (item.audioPath != null) {
                    Row { TextButton(onClick = onPlay, enabled = !mutation.busy) { Text("播放") }; TextButton(onClick = onStop) { Text("停止播放") } }
                    Text("分析会将录音发送到配置的转写服务，将文字发送到配置的分析服务，可能按服务商价格收费。")
                    Button(onClick = onRetry, enabled = !mutation.busy) { Text("分析 / 重试") }
                }
                Text("转写文字", style = MaterialTheme.typography.titleMedium)
                Text(item.transcript.ifBlank { "尚无转写结果" })
                item.analysis?.let { analysis ->
                    Text("对方自述身份（需核对）", style = MaterialTheme.typography.titleMedium)
                    Text("姓名：${analysis.name.ifBlank { "未提及" }}\n单位：${analysis.organization.ifBlank { "未提及" }}\n职位：${analysis.position.ifBlank { "未提及" }}")
                    Text("自述依据：${analysis.evidence.ifBlank { "无" }}")
                    Text("模型推测（未核实）：${analysis.inferredIdentity.ifBlank { "无" }}")
                    Text("来电目的：${analysis.purpose}")
                    Text("摘要：${analysis.summary}")
                    analysis.actionItems.forEach { Text("• $it") }
                    Text("AI 结果可能不准确，不能作为已经核实的身份。")
                    TextButton(onClick = { adoption = true }, enabled = !mutation.busy && (analysis.name.isNotBlank() || analysis.organization.isNotBlank())) { Text("采纳自述姓名和单位") }
                    TextButton(onClick = onEdit) { Text("编辑档案") }
                }
                TextButton(onClick = { deletion = true }, enabled = !mutation.busy) { Text("删除本次归档") }
            }
        }
    }
    if (deletion) AlertDialog(onDismissRequest = { deletion = false }, title = { Text("删除本次归档？") }, text = { Text("删除应用内的录音、转写和分析结果。原始导入文件保留。") }, confirmButton = { TextButton(onClick = { deletion = false; onDelete() }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deletion = false }) { Text("取消") } })
    if (adoption) AlertDialog(onDismissRequest = { adoption = false }, title = { Text("确认采纳自述身份？") }, text = { Text("将自述姓名和单位填入号码档案，替换对应的非空字段。请核实；职位和白名单保持不变，模型推测不会采纳。") }, confirmButton = { TextButton(onClick = { adoption = false; onAdopt() }) { Text("确认采纳") } }, dismissButton = { TextButton(onClick = { adoption = false }) { Text("取消") } })
}
