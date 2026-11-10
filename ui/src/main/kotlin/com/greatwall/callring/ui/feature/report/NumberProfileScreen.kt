package com.greatwall.callring.ui.feature.report

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greatwall.callring.contracts.runtime.Capability
import com.greatwall.callring.ui.feature.callers.*

@Composable
internal fun NumberProfileScreen(
    model: ReportViewModel, history: CallHistoryViewModel,
    onBack: () -> Unit, onExport: () -> Unit, onManageProfile: () -> Unit,
    onCapability: (Capability) -> Unit, onOpenCall: (Long) -> Unit, onEditField: (CallerProfileField) -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val query by model.query.collectAsStateWithLifecycle()
    val mutation by model.mutation.collectAsStateWithLifecycle()
    val calls by history.state.collectAsStateWithLifecycle()
    val playing by history.playback.state.collectAsStateWithLifecycle()
    val playback by history.playback.mutation.collectAsStateWithLifecycle()
    val transcript by history.transcript.collectAsStateWithLifecycle()
    val selection by history.selectedTranscript.collectAsStateWithLifecycle()
    DisposableEffect(history) { onDispose { history.playback.stopForExit() } }
    ReportScreen(
        state, query, mutation, onBack, { model.refresh() }, model::range, model::filter, model::page,
        onExport, onManageProfile, onCapability, { onOpenCall(it.id) },
        onEditField = onEditField,
        history = ReportHistory(calls, playing, playback, history::refresh, history::more, onOpenCall,
            history.playback::toggle, history::openTranscript),
    )
    selection?.let { CallHistoryTranscriptDialog(it, transcript, history::retryTranscript, history::closeTranscript) }
}
