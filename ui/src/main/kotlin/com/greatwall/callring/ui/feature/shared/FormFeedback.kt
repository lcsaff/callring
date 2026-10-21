package com.greatwall.callring.ui.feature.shared

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.greatwall.callring.ui.state.MutationState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import com.greatwall.callring.ui.designsystem.components.UnsavedChangesDialog

/** Intercept only unsaved edits/commit, keeping clean-screen and root predictive animations intact. */
@Composable
fun rememberFormBack(
    onBack: () -> Unit, dirty: Boolean, mutation: MutationState,
    onSave: (() -> Unit)? = null, canSave: Boolean = true,
): () -> Unit {
    val busy = mutation.busy
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var leaveAfterSave by rememberSaveable { mutableStateOf(false) }
    var saveStartedAt by rememberSaveable { mutableLongStateOf(mutation.completed) }
    val currentBack by rememberUpdatedState(onBack)
    LaunchedEffect(mutation.completed, mutation.busy, mutation.error) {
        if (leaveAfterSave && !mutation.busy) {
            if (mutation.error != null) leaveAfterSave = false
            else if (mutation.completed > saveStartedAt) { leaveAfterSave = false; currentBack() }
        }
    }
    val requestBack: () -> Unit = {
        if (!busy) {
            if (dirty) confirmDiscard = true else currentBack()
        }
    }
    PredictiveBackHandler(enabled = dirty || busy) { progress ->
        try {
            progress.collect()
            requestBack()
        } catch (cancelled: CancellationException) {
            // A cancelled predictive gesture keeps editing without showing a dialog.
            throw cancelled
        }
    }
    if (confirmDiscard) UnsavedChangesDialog(busy, canSave && onSave != null,
        onSave = {
            confirmDiscard = false
            saveStartedAt = mutation.completed
            leaveAfterSave = true
            onSave?.invoke()
        }, onDiscard = { confirmDiscard = false; currentBack() }, onContinue = { confirmDiscard = false })
    return requestBack
}
