package com.greatwall.callring.ui.feature.callers

import androidx.compose.runtime.Composable
import com.greatwall.callring.application.api.ApiError
import com.greatwall.callring.application.api.CallerDraft
import com.greatwall.callring.application.api.CallerEditorData
import com.greatwall.callring.application.api.NumberDeletionImpact
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import com.greatwall.callring.ui.designsystem.components.rememberFormBack
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState

@Composable fun CallerEditorScreen(
    state: LoadState<CallerEditorData>, draft: CallerDraft?, mutation: MutationState, numberEditable: Boolean,
    onBack: () -> Unit, onRetry: () -> Unit, onChange: (CallerDraft) -> Unit, onSave: () -> Unit,
    onDeleteRequest: (() -> Unit)? = null,
    impact: NumberDeletionImpact? = null,
    deletionState: MutationState? = null,
    deletionError: ApiError? = null,
    onConfirmDelete: () -> Unit = {},
    onDismissDelete: () -> Unit = {},
) {
    val back = rememberFormBack(onBack, draft != null && state.data?.draft != null && draft != state.data.draft,
        mutation, onSave, canSave = draft?.number?.isNotBlank() == true)
    ScreenFrame(if (numberEditable) "新增档案" else "编辑档案", back) {
        CallerEditorForm(state, draft, mutation, numberEditable, onRetry, onChange, onSave, onDeleteRequest = onDeleteRequest)
    }
    NumberDeletionDialogs(impact, deletionState, deletionError, onDismissDelete, onConfirmDelete)
}
