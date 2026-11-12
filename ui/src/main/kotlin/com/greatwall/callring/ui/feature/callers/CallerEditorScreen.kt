package com.greatwall.callring.ui.feature.callers

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.greatwall.callring.contracts.common.ApiError
import com.greatwall.callring.contracts.common.ErrorCode
import com.greatwall.callring.contracts.callers.CallerDraft
import com.greatwall.callring.contracts.callers.CallerEditorData
import com.greatwall.callring.contracts.command.NumberDeletionImpact
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import com.greatwall.callring.ui.feature.shared.rememberFormBack
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
    field: CallerProfileField? = null,
    onReload: () -> Unit = {},
    draftDirty: Boolean? = null,
) {
    val current = state.data?.draft
    val dirty = draftDirty ?: (draft != null && current != null && draft != current)
    val reloadingConflict = state.loading && mutation.error?.code == ErrorCode.CONFLICT
    val canSave = draft?.number?.isNotBlank() == true && (field == null || dirty && !reloadingConflict)
    val back = rememberFormBack(onBack, dirty, mutation, onSave, canSave = canSave)
    var submittedAt by rememberSaveable { mutableLongStateOf(-1L) }
    val currentBack by rememberUpdatedState(onBack)
    val submit = {
        if (canSave && !mutation.busy) {
            if (field != null) submittedAt = mutation.completed
            onSave()
        }
    }
    val title = field?.let { "编辑${it.label}" } ?: if (numberEditable) "新增档案" else "编辑档案"
    ScreenFrame(title, back) {
        CallerEditorForm(state, draft, mutation, numberEditable, onRetry, onChange, submit,
            onDeleteRequest = onDeleteRequest, field = field, canSave = canSave, onReload = onReload)
    }
    LaunchedEffect(submittedAt, mutation) {
        if (submittedAt >= 0 && !mutation.busy) {
            if (mutation.completed > submittedAt) {
                submittedAt = -1
                currentBack()
            } else if (mutation.error != null) submittedAt = -1
        }
    }
    NumberDeletionDialogs(impact, deletionState, deletionError, onDismissDelete, onConfirmDelete)
}
