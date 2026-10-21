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

@Composable fun RecordingArchivesScreen(state: LoadState<List<CallArchive>>, onBack: () -> Unit,
    onRefresh: () -> Unit, onArchive: (CallArchive) -> Unit) {
    ScreenFrame("通话录音", onBack, actions = {
        TextButton(onClick = onRefresh, enabled = !state.loading) { Text("刷新") }
    }) {
        RecordingFeedback(state.error, MutationState())
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.loaded && !state.loading && state.error == null && state.data.isNullOrEmpty()) Text("暂无通话录音")
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.data.orEmpty(), key = { it.callId }) { archive ->
                OutlinedCard(onClick = { onArchive(archive) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(archive.number, style = MaterialTheme.typography.titleMedium)
                        Text(com.greatwall.callring.ui.format.UiFormat.time(archive.startedAtEpochMillis, java.time.ZoneId.systemDefault()))
                        Text(archiveStatusLabel(archive.status))
                        archive.analysis?.summary?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    }
                }
            }
        }
    }
}
