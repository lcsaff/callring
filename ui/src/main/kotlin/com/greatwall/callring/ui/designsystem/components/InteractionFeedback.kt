package com.greatwall.callring.ui.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val LocalFeedbackHost = staticCompositionLocalOf<SnackbarHostState?> { null }
val LocalFeedbackScope = staticCompositionLocalOf<CoroutineScope?> { null }

@Composable
fun FeedbackSnackbar(host: SnackbarHostState) {
    SnackbarHost(host) { data ->
        Snackbar(data, modifier = Modifier.testTag("feedback-snackbar"))
    }
}

/** Each window owns its host. An overlay never attaches feedback to the Activity behind it. */
@Composable
fun FeedbackPanel(content: @Composable () -> Unit) {
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalFeedbackHost provides host, LocalFeedbackScope provides scope) {
        Box {
            content()
            Box(Modifier.align(Alignment.BottomCenter).padding(8.dp)) { FeedbackSnackbar(host) }
        }
    }
}

@Composable
fun MutationSuccessEffect(completed: Long, busy: Boolean, message: String, enabled: Boolean = true) {
    val host = LocalFeedbackHost.current
    val hostScope = LocalFeedbackScope.current
    // A screen first composed after a completed operation must not replay that operation.
    var consumed by rememberSaveable { mutableLongStateOf(completed) }
    LaunchedEffect(completed, busy) {
        if (completed < consumed) consumed = completed // New runner after process recreation.
        if (!busy && completed > consumed) {
            consumed = completed
            // The host outlives the sending screen, including save-and-exit navigation.
            if (enabled && host != null) hostScope?.launch { host.showSnackbar(message, withDismissAction = true) }
        }
    }
}
