package com.greatwall.callring.ui.app

import androidx.compose.runtime.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.greatwall.callring.ui.state.TimeRefreshSchedule
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable internal inline fun <reified T : ViewModel> uiViewModel(
    key: String? = null, noinline create: (SavedStateHandle) -> T
): T = viewModel(key = key, factory = remember { viewModelFactory { initializer { create(createSavedStateHandle()) } } })

/** Only actual time signals, elapsed boundaries, and elapsed intervals invalidate time-derived DTOs. */
@Composable internal fun TimeRefresh(
    schedule: TimeRefreshSchedule, signal: Long, nextBoundary: Long? = null, intervalMillis: Long? = null, refresh: () -> Unit
) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(refresh)
    LaunchedEffect(owner, schedule, signal, nextBoundary, intervalMillis) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                val now = schedule.clock.millis()
                if (schedule.due(now, schedule.clock.zone, signal, nextBoundary, intervalMillis)) current()
                val wait = schedule.delayMillis(now, nextBoundary, intervalMillis) ?: break
                delay(wait)
            }
        }
    }
}
