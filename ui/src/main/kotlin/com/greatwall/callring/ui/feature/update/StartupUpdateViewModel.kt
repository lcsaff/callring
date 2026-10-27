package com.greatwall.callring.ui.feature.update

import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.ui.state.UiViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One quiet check per main UI owner; configuration changes retain the result and dismissal. */
class StartupUpdateViewModel(api: AppUpdateApi, testScope: CoroutineScope? = null) : UiViewModel(testScope) {
    private val mutableRelease = MutableStateFlow<AppReleaseInfo?>(null)
    val release = mutableRelease.asStateFlow()
    private val mutableFinished = MutableStateFlow(false)
    val finished = mutableFinished.asStateFlow()

    init {
        scope.launch {
            try {
                val existing = (api.status() as? ApiResult.Success)?.value ?: return@launch
                if (existing.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING,
                        UpdatePhase.VERIFIED, UpdatePhase.INSTALL_PERMISSION_REQUIRED,
                        UpdatePhase.INSTALL_PENDING_VERIFY, UpdatePhase.UNSUPPORTED_DEVICE)) return@launch
                val checked = (api.check() as? ApiResult.Success)?.value ?: return@launch
                ensureActive()
                if (checked.phase == UpdatePhase.UPDATE_AVAILABLE) mutableRelease.value = checked.latest
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Automatic checks are silent; the update page retains manual retry and error feedback.
            } finally {
                mutableFinished.value = true
            }
        }
    }

    fun dismiss() { mutableRelease.value = null }
}
