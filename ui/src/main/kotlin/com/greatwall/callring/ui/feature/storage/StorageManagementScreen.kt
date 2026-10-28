package com.greatwall.callring.ui.feature.storage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.recording.ArchiveStatus
import com.greatwall.callring.contracts.storage.*
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.recording.archiveStatusLabel
import com.greatwall.callring.ui.state.LoadState
import java.time.LocalDate
import java.time.ZoneId

internal fun storageBytes(value: Long?): String = value?.let { "$it 字节" } ?: "未知"
private fun storageError(key: String): String = when(key) {
    "storage.selection_too_large" -> "本次最多选择 1,000 条，请缩小日期或状态范围。"
    "storage.preview_changed", "storage.preview_missing", "recording.page_changed" -> "录音或使用状态已变化，或预览已过期，请重新预览。"
    "storage.invalid_selection" -> "请选择有效日期范围和至少一种状态。"
    else -> "无法完成存储操作，请刷新后重试。"
}

@Composable fun StorageManagementScreen(state: LoadState<StorageInventory>, cleanup: StorageCleanupUiState,
    zone: ZoneId?, onBack: () -> Unit, onRefresh: () -> Unit, onPreview: (ArchiveCleanupSelection) -> Unit,
    onDiscard: () -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var from by rememberSaveable { mutableStateOf("1970-01-01") }
    var before by rememberSaveable { mutableStateOf("") }
    var selectedNames by rememberSaveable { mutableStateOf(listOf("COMPLETE", "FAILED", "READY")) }
    val selected = selectedNames.map(ArchiveStatus::valueOf).toSet()
    var invalidDates by remember { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(zone) { if (before.isEmpty() && zone != null) before = LocalDate.now(zone).plusDays(1).toString() }
    val preview = cleanup.preview
    ScreenFrame("存储管理", onBack, actions = {
        TextButton(onClick = onRefresh, enabled = !cleanup.busy) { Text("刷新") }
    }) {
        if (state.loading && state.data == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize().testTag("storage-content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                state.data?.let { inventory ->
                    Text("自有录音音频：${storageBytes(inventory.ownedAudioBytes)}")
                    Text("数据库：${storageBytes(inventory.databaseBytes)}")
                    Text("临时文件：${storageBytes(inventory.temporaryBytes)}")
                    Text("更新缓存：${storageBytes(inventory.updateCacheBytes)}")
                    Text("内部存储可用：${storageBytes(inventory.freeBytes)}")
                }
                if (state.error != null) Text("存储占用读取失败，请重试。", color = MaterialTheme.colorScheme.error)
                Text("删除数据库记录后空间可在应用内复用，数据库文件不一定立即缩小。临时文件与更新缓存仅显示占用。")
                Text("手动清理录音归档", style = MaterialTheme.typography.titleMedium)
                Text("按工作时间配置的时区选择日期。每次最多 1,000 条，预览五分钟有效。不会自动按保留期删除。")
                if (zone == null) Text("请等待工作时区加载。") else Text("时区：${zone.id}")
                OutlinedTextField(from, { from=it; invalidDates=false; onDiscard() }, label={Text("开始日期（含，yyyy-MM-dd）")}, enabled=!cleanup.busy, modifier=Modifier.fillMaxWidth())
                OutlinedTextField(before, { before=it; invalidDates=false; onDiscard() }, label={Text("结束日期（不含，yyyy-MM-dd）")}, enabled=!cleanup.busy, modifier=Modifier.fillMaxWidth())
                ArchiveStatus.entries.forEach { status ->
                    Row {
                        Checkbox(status in selected, onCheckedChange={ checked ->
                            selectedNames=(if(checked) selected+status else selected-status).map { it.name }; onDiscard()
                        }, enabled=!cleanup.busy)
                        Text(archiveStatusLabel(status), Modifier.padding(top=12.dp))
                    }
                }
                if (invalidDates) Text("请输入有效的起止日期。", color=MaterialTheme.colorScheme.error)
                Button(onClick={
                    val selection=runCatching {
                        val configured=checkNotNull(zone)
                        ArchiveCleanupSelection(LocalDate.parse(from).atStartOfDay(configured).toInstant().toEpochMilli(),
                            LocalDate.parse(before).atStartOfDay(configured).toInstant().toEpochMilli(), selected)
                    }.getOrNull()
                    if(selection==null) invalidDates=true else onPreview(selection)
                }, enabled=zone!=null && !cleanup.busy) { Text("生成清理预览") }
                if(cleanup.busy && preview==null) {
                    Text("正在准备录音索引和清理预览…")
                    OutlinedButton(onClick=onCancel) { Text("取消预览") }
                }
                cleanup.error?.let { Text(storageError(it.message), color=MaterialTheme.colorScheme.error) }
            }
            if (preview != null) item {
                Text("将删除 ${preview.targets.size} 条归档，预计释放音频 ${storageBytes(preview.audioBytes)}。")
                Text("预览状态：${preview.selection.statuses.joinToString("、") { archiveStatusLabel(it) }}")
                zone?.let { configured ->
                    Text("日期：${java.time.Instant.ofEpochMilli(preview.selection.fromEpochMillis).atZone(configured).toLocalDate()}（含）至 ${java.time.Instant.ofEpochMilli(preview.selection.beforeEpochMillis).atZone(configured).toLocalDate()}（不含）")
                }
                Text("因采集、处理或播放暂不可清理的归档 ${preview.excludedActive} 条；采集尚未结束时暂停全部归档清理。")
                Text("删除包括应用自有音频、转写和分析；原始系统录音、统计事实与审计记录保留。")
                if (cleanup.busy) {
                    Text("正在逐项清理，已完成的删除不能撤销。")
                    OutlinedButton(onClick=onCancel) { Text("停止未开始项") }
                } else Button(onClick={ confirm=true }, enabled=preview.targets.isNotEmpty()) { Text("删除这些归档") }
            }
            cleanup.receipt?.let { receipt ->
                item {
                    Text("已删除 ${receipt.items.count { it.status==CleanupItemStatus.COMMITTED }} 条，失败 ${receipt.items.count { it.status==CleanupItemStatus.FAILED }} 条，未开始 ${receipt.items.count { it.status==CleanupItemStatus.UNSTARTED }} 条。")
                    if(receipt.cancelled) Text("已停止未开始项。已完成的删除无法撤销。")
                    Text("释放量可能因文件已丢失或其他空间变化与预估不同。再次操作需重新生成预览。")
                }
                items(receipt.items,key={it.target.callId}) { item ->
                    Text("归档 ${item.target.callId}："+when(item.status) {
                        CleanupItemStatus.COMMITTED -> "已删除"
                        CleanupItemStatus.FAILED -> "失败（文件不可访问或归档状态变化）"
                        CleanupItemStatus.UNSTARTED -> "未开始"
                    })
                }
            }
        }
    }
    if (confirm && preview != null) ConfirmationDialog("确认删除录音归档", "永久删除 ${preview.targets.size} 条归档及应用自有音频、转写和分析。此操作无法撤销。", "确认删除",
        onConfirm={ confirm=false; onConfirm() }, onDismiss={ confirm=false }, busy=cleanup.busy)
}
