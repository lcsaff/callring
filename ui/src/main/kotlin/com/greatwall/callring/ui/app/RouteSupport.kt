package com.greatwall.callring.ui.app

import androidx.compose.runtime.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable internal inline fun <reified T : ViewModel> uiViewModel(
    key: String? = null, noinline create: (SavedStateHandle) -> T
): T = viewModel(key = key, factory = remember { viewModelFactory { initializer { create(createSavedStateHandle()) } } })

/** Refresh follows the visible destination's lifetime, including authorization returns. */
@Composable internal fun ActiveRefresh(
    signal: Long, nextBoundary: Long? = null, intervalMillis: Long? = null, refresh: () -> Unit
) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(refresh)
    LaunchedEffect(owner, signal, nextBoundary, intervalMillis) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            current()
            if (intervalMillis != null || nextBoundary != null) while (isActive) {
                val boundaryDelay = nextBoundary?.minus(System.currentTimeMillis())?.coerceAtLeast(1_000)
                delay(minOf(intervalMillis ?: 60_000, boundaryDelay ?: 60_000))
                current()
            }
        }
    }
}
