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
    "storage.reset_preview_changed" -> "数据已变化或预览已过期，请重新生成清空预览。"
    "backup.call_active" -> "通话或录音进行中，请结束后重试。"
    "backup.phone_state_required" -> "请先授权读取电话状态，再清空应用数据。"
    else -> "无法完成存储操作，请刷新后重试。"
}

@Composable fun StorageManagementScreen(state: LoadState<StorageInventory>, cleanup: StorageCleanupUiState,
    zone: ZoneId?, onBack: () -> Unit, onRefresh: () -> Unit, onPreview: (ArchiveCleanupSelection) -> Unit,
    onDiscard: () -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit,
    numberReset: NumberProfileResetUiState, onPreviewNumberProfiles: () -> Unit,
    onDiscardNumberProfiles: () -> Unit, onConfirmNumberProfiles: (Boolean) -> Unit) {
    var from by rememberSaveable { mutableStateOf("1970-01-01") }
    var before by rememberSaveable { mutableStateOf("") }
    var selectedNames by rememberSaveable { mutableStateOf(listOf("COMPLETE", "FAILED", "READY")) }
    val selected = selectedNames.map(ArchiveStatus::valueOf).toSet()
    var invalidDates by remember { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var confirmNumberReset by rememberSaveable(numberReset.preview?.token) { mutableStateOf(false) }
    var keepRecordings by rememberSaveable(numberReset.preview?.token) { mutableStateOf(true) }
    LaunchedEffect(zone) { if (before.isEmpty() && zone != null) before = LocalDate.now(zone).plusDays(1).toString() }
    val preview = cleanup.preview
    ScreenFrame("数据管理", onBack, actions = {
        TextButton(onClick = onRefresh, enabled = !cleanup.busy&&!numberReset.busy) { Text("刷新") }
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
            }
            item {
                Text("临时操作：清空全部号码档案",style=MaterialTheme.typography.titleMedium)
                Text("删除应用内全部号码档案、通话记录和桌面组件号码绑定。设备通话记录和系统原录音保留；之后可重新同步历史。")
                Button(onClick=onPreviewNumberProfiles,enabled=!numberReset.busy&&!cleanup.busy) {Text("预览清空全部号码档案")}
                if(numberReset.busy)Text("正在准备或清空数据，请稍候…")
                numberReset.error?.let {Text(storageError(it.message),color=MaterialTheme.colorScheme.error)}
                numberReset.preview?.let {p ->
                    Text("将清空 ${p.profileCount} 个号码档案、${p.callCount} 条通话记录和 ${p.widgetBindingCount} 个组件绑定。")
                    Text("关联录音归档 ${p.archiveCount} 条，应用音频 ${storageBytes(p.audioBytes)}。预览五分钟有效。")
                    Text("下一步可选择是否保留录音、转写和分析。清空完成后会重新打开应用。")
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick=onDiscardNumberProfiles,enabled=!numberReset.busy){Text("放弃清空")}
                        Button(onClick={confirmNumberReset=true},enabled=!numberReset.busy&&!cleanup.busy){Text("继续清空全部档案")}
                    }
                }
            }
            item {
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
    if(confirmNumberReset&&numberReset.preview!=null) ConfirmationDialog(
        "再次确认清空全部号码档案",
        "全部号码档案、应用内通话记录和组件号码绑定将被删除，无法撤销。设备通话记录和系统原录音不会删除。",
        "确认清空",onConfirm={confirmNumberReset=false;onConfirmNumberProfiles(keepRecordings)},
        onDismiss={confirmNumberReset=false},busy=numberReset.busy,
        content={
            Row {
                Checkbox(keepRecordings,{keepRecordings=it},enabled=!numberReset.busy,modifier=Modifier.testTag("reset-keep-recordings"))
                Text("保留录音、转写和分析",Modifier.padding(top=12.dp))
            }
            Text(if(keepRecordings)"保留应用内录音内容并暂停旧任务。重新同步匹配历史时复用已有内容。" else "同时删除应用内录音归档、应用自有音频、转写和分析。系统原录音保留，可重新导入。")
        },
    )
}
