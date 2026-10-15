package com.greatwall.callring.ui.overlay

import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.designsystem.DefaultAppearance
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState

@Composable internal fun OverlayRoute(controller: OverlayController, maxHeight: Dp, onDrag: (Offset) -> Unit, onReport: (String) -> Unit) {
    val session by controller.sessions.state.collectAsState()
    val models by controller.models.collectAsState()
    val savedRevision by controller.savedRevision.collectAsState()
    val current = session ?: return
    val model = models?.takeIf { it.presentation.request.sessionId == current.request.sessionId }
    if (model == null) {
        CallringTheme { Surface { Text("正在加载来电信息", Modifier.padding(16.dp)) } }
        return
    }
    val overview by model.presentation.overview.collectAsState()
    val settings by model.presentation.settings.collectAsState()
    val mutation by model.presentation.mutation.collectAsState()
    val editor = model.editor
    val editorState = if (editor != null) editor.state.collectAsState().value else LoadState()
    val draft = if (editor != null) editor.draft.collectAsState().value else null
    val editorMutation = if (editor != null) editor.mutation.collectAsState().value else MutationState()
    CallringTheme(settings.data?.appearance ?: DefaultAppearance) {
        OverlayScreen(OverlayContentState(current, overview, editorState, draft, mutation, editorMutation, savedRevision),
            onDrag, controller::dismiss, controller::collapse, controller::beginEditing, controller::cancelEditing,
            controller::save, controller::changeDraft, controller::whitelist, { onReport(current.request.number) },
            controller::refreshOverview, controller::retryEditor, maxHeight)
    }
}
