package com.greatwall.callring.ui.feature.backup

import com.greatwall.callring.contracts.backup.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.ui.state.UiViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class BackupUiState(val busy: Boolean = false, val preview: BackupPreview? = null,
    val exported: BackupSummary? = null, val restored: Boolean = false, val error: ApiError? = null)

class BackupViewModel(private val api: BackupApi, testScope: CoroutineScope? = null) : UiViewModel(testScope) {
    private val mutable = MutableStateFlow(BackupUiState())
    val state = mutable.asStateFlow()
    private val passwordMutable = MutableStateFlow("")
    val password = passwordMutable.asStateFlow()
    private val audioMutable = MutableStateFlow(true)
    val includeAudio = audioMutable.asStateFlow()
    val progress = api.progress
    private var generation = 0L
    private var job: Job? = null
    private var restoring = false
    fun editPassword(value: String) { passwordMutable.value = value }
    fun includeAudio(value: Boolean) { if (!mutable.value.busy) audioMutable.value = value }
    private fun secret(): CharArray? {
        if (passwordMutable.value.length < 8) {
            val key = if (passwordMutable.value.isEmpty()) "backup.password_required" else "backup.password_too_short"
            mutable.value = mutable.value.copy(error = ApiError(ErrorCode.VALIDATION, key))
            return null
        }
        return passwordMutable.value.toCharArray().also { passwordMutable.value = "" }
    }
    private fun discard(token: String?) {
        if (token != null) scope.launch(NonCancellable) { runCatching { api.discard(token) } }
    }
    private fun launch(secret: CharArray? = null, block: suspend (Long) -> Unit) {
        val ticket = ++generation
        mutable.value = mutable.value.copy(busy = true, error = null, exported = null, restored = false)
        job = scope.launch {
            try { block(ticket) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket == generation) mutable.value = mutable.value.copy(error = ApiError(ErrorCode.STORAGE_FAILURE, "backup.failed", retryable = true))
            } finally {
                secret?.fill(0.toChar())
                if (ticket == generation) { mutable.value = mutable.value.copy(busy = false); restoring = false }
            }
        }.also { launched -> launched.invokeOnCompletion { secret?.fill(0.toChar()) } }
    }
    fun export(destination: DocumentRef) {
        if (mutable.value.busy) return
        val secret = secret() ?: return
        val audio = audioMutable.value
        discard(mutable.value.preview?.token)
        mutable.value = mutable.value.copy(preview = null)
        launch(secret) { ticket ->
            when (val result = api.export(destination, secret, audio)) {
                is ApiResult.Success -> if (ticket == generation) mutable.value = mutable.value.copy(exported = result.value)
                is ApiResult.Failure -> if (ticket == generation) mutable.value = mutable.value.copy(error = result.error)
            }
        }
    }
    fun preview(source: DocumentRef) {
        if (mutable.value.busy) return
        val secret = secret() ?: return
        discard(mutable.value.preview?.token)
        mutable.value = mutable.value.copy(preview = null)
        launch(secret) { ticket ->
            when (val result = api.preview(source, secret)) {
                is ApiResult.Success -> if (ticket == generation && currentCoroutineContext().isActive)
                    mutable.value = mutable.value.copy(preview = result.value)
                else withContext(NonCancellable) { api.discard(result.value.token) }
                is ApiResult.Failure -> if (ticket == generation) mutable.value = mutable.value.copy(error = result.error)
            }
        }
    }
    fun restore() {
        if (mutable.value.busy) return
        val token = mutable.value.preview?.token ?: return
        restoring = true
        mutable.value = mutable.value.copy(preview = null)
        passwordMutable.value = ""
        launch { ticket ->
            when (val result = api.restore(token)) {
                is ApiResult.Success -> if (ticket == generation) mutable.value = mutable.value.copy(restored = result.value.applicationRestartRequested)
                is ApiResult.Failure -> if (ticket == generation) mutable.value = mutable.value.copy(error = result.error)
            }
        }
    }
    fun cancel() {
        // Once replacement starts its journal/owner owns recovery; do not interrupt it from UI.
        if (restoring) return
        generation++
        job?.cancel()
        discard(mutable.value.preview?.token)
        mutable.value = BackupUiState()
        passwordMutable.value = ""
    }
    fun exit() { if (!restoring) cancel() else passwordMutable.value = "" }
    override fun onCleared() { exit(); super.onCleared() }
}
