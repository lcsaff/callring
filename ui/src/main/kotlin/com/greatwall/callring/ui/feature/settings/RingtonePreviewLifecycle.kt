package com.greatwall.callring.ui.feature.settings

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleStartEffect

/** The page owns preview playback only while its host is visible. */
@Composable
internal fun RingtonePreviewLifecycle(model: RingtoneViewModel) {
    LifecycleStartEffect(model) {
        model.enter()
        onStopOrDispose { model.leave() }
    }
}
