package com.greatwall.callring.ui.feature.callers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.recording.RecordingPlaybackSession
import com.greatwall.callring.ui.designsystem.LocalCallringDarkMode
import com.greatwall.callring.ui.designsystem.components.*
import com.greatwall.callring.ui.feature.recording.RecordingFeedback
import com.greatwall.callring.ui.feature.shared.LoadFeedback
import com.greatwall.callring.ui.format.UiFormat
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState
import java.time.ZoneId

@Composable
internal fun CallHistoryScreen(
    state: LoadState<CallHistoryPage>, playing: LoadState<RecordingPlaybackSession?>, playback: MutationState,
    onBack: () -> Unit, onRefresh: () -> Unit, onMore: () -> Unit, onArchives: () -> Unit,
    onDetail: (Long) -> Unit, onPlayback: (Long, Long) -> Unit, onTranscript: (CallHistoryEntry) -> Unit,
) {
    ScreenFrame("通话记录", onBack, actions = {
        TextButton(onClick = onArchives) { Text("录音归档") }
    }) {
        LoadFeedback(state, onRefresh)
        RecordingFeedback(playing.error, playback)
        val page = state.data
        if (state.loaded && state.error == null && page?.entries.isNullOrEmpty()) EmptyContent("暂无通话记录")
        val listState = rememberLazyListState()
        var anchorId by rememberSaveable { mutableStateOf<Long?>(null) }
        var anchorOffset by rememberSaveable { mutableIntStateOf(0) }
        LaunchedEffect(page) {
            if (page == null) return@LaunchedEffect
            anchorId?.let { id ->
                val index = page.entries.indexOfFirst { it.call.id == id }
                listState.requestScrollToItem(index.coerceAtLeast(0), if (index < 0) 0 else anchorOffset)
            }
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key is Long }?.let {
                    (it.key as Long) to (-it.offset).coerceAtLeast(0)
                }
            }.collect { anchor ->
                if (anchor != null) {
                    anchorId = anchor.first
                    anchorOffset = anchor.second
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("call-history-list"),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(page?.entries.orEmpty(), key = { it.call.id }) { entry ->
                CallHistoryRow(entry, playing.data?.callId, playback.busy, onDetail, onPlayback, onTranscript)
            }
            if (page?.nextCursor != null) item {
                TextButton(onClick = onMore, enabled = !state.loading && state.error == null) {
                    Text(if (state.loading) "正在加载…" else "加载更多")
                }
            }
        }
    }
}

@Composable
internal fun CallHistoryRow(
    entry: CallHistoryEntry, playing: Long?, busy: Boolean,
    onDetail: (Long) -> Unit, onPlayback: (Long, Long) -> Unit, onTranscript: (CallHistoryEntry) -> Unit,
    showCallerSummary: Boolean = true,
) {
    val call = entry.call
    val recording = entry.recording
    val archiveId = recording.archiveId
    val available = recording.availability == CallArchiveAvailability.AVAILABLE && archiveId != null
    val activeId = playing?.takeIf { it == archiveId || it == call.id || it == entry.linkedScreeningCallId }
    val availabilityLabel = when (recording.availability) {
        CallArchiveAvailability.UNREADABLE -> "归档无法读取"
        CallArchiveAvailability.INDEXING -> "正在读取归档状态"
        else -> null
    }
    val dark = LocalCallringDarkMode.current
    val (workLabel, workColor) = when {
        call.classification.reason == WorkReason.UNKNOWN ->
            "工作状态未知" to MaterialTheme.colorScheme.onSurfaceVariant
        call.classification.working ->
            "工作时间内" to if (dark) Color(0xFFA5C6AA) else Color(0xFF375F46)
        else ->
            "工作时间外" to if (dark) Color(0xFFDBAEAD) else Color(0xFF8B4A4A)
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onDetail(call.id) }.padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (showCallerSummary) {
                    Text(
                        UiFormat.callerSummary(
                            call.number, entry.callerName, entry.callerRegion,
                            entry.callerSelfReportedPosition, entry.callerConfiguredPosition,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(UiFormat.direction(call.direction), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        UiFormat.fullTime(call.startedAtEpochMillis, ZoneId.of(call.classification.zoneId)),
                        modifier = Modifier.semantics { stateDescription = workLabel },
                        style = MaterialTheme.typography.bodyMedium,
                        color = workColor,
                    )
                }
            }
            IconButton(
                onClick = { if (archiveId != null) onPlayback(archiveId, activeId ?: call.id) },
                enabled = available && (recording.hasAudio || activeId != null) && !busy,
            ) {
                val label = when {
                    activeId != null -> "停止录音"
                    available && recording.hasAudio -> "播放录音"
                    else -> availabilityLabel ?: "无可播放录音"
                }
                if (activeId != null) Box(Modifier.size(12.dp).background(LocalContentColor.current)
                    .semantics { contentDescription = label })
                else Icon(Icons.Filled.PlayArrow, contentDescription = label)
            }
            IconButton(onClick = { onTranscript(entry) }, enabled = available && recording.hasTranscript) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = if (available && recording.hasTranscript) "查看转写"
                    else availabilityLabel ?: "无转写")
            }
        }
    }
}

@Composable
internal fun CallHistoryTranscriptDialog(
    selection: CallHistoryTranscript, state: LoadState<String>, onRetry: () -> Unit, onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${selection.number} 的转写") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                LoadFeedback(state, onRetry)
                state.data?.let { androidx.compose.foundation.text.selection.SelectionContainer { Text(it) } }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}
