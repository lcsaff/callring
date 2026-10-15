package com.greatwall.callring.ui.feature.callers

import androidx.compose.runtime.Composable
import com.greatwall.callring.application.api.CallerDraft
import com.greatwall.callring.application.api.CallerEditorData
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState

@Composable fun CallerEditorScreen(
    state: LoadState<CallerEditorData>, draft: CallerDraft?, mutation: MutationState, numberEditable: Boolean,
    onBack: () -> Unit, onRetry: () -> Unit, onChange: (CallerDraft) -> Unit, onSave: () -> Unit
) {
    ScreenFrame(if (numberEditable) "新增档案" else "编辑档案", onBack) {
        CallerEditorForm(state, draft, mutation, numberEditable, onRetry, onChange, onSave)
    }
}
