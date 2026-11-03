package com.greatwall.callring.ui.feature.storage

import com.greatwall.callring.contracts.CallringUiApi
import com.greatwall.callring.contracts.storage.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class StorageCleanupUiState(val preview: ArchiveCleanupPreview? = null, val receipt: ArchiveCleanupReceipt? = null,
    val busy: Boolean = false, val error: com.greatwall.callring.contracts.common.ApiError? = null)
class StorageManagementViewModel(private val api: CallringUiApi, testScope: CoroutineScope? = null) : SnapshotViewModel<StorageInventory>(
    api.changes, setOf(DataDomain.RECORDING_ARCHIVES), { api.storage.inventory() }, testScope) {
    private val mutableCleanup = MutableStateFlow(StorageCleanupUiState())
    val cleanup = mutableCleanup.asStateFlow()
    private var generation = 0L
    private var previewJob: Job? = null
    fun preview(selection: ArchiveCleanupSelection) {
        if (cleanup.value.busy) return
        discardPreview()
        val ticket = generation
        mutableCleanup.value = StorageCleanupUiState(busy = true)
        previewJob = scope.launch {
            val result = attempt { api.storage.preview(selection) }
            if (ticket != generation || !isActive) return@launch
            mutableCleanup.value = when(result) {
                is ApiResult.Success -> StorageCleanupUiState(preview = result.value)
                is ApiResult.Failure -> StorageCleanupUiState(error = result.error)
            }
        }
    }
    fun discardPreview() {
        generation++; previewJob?.cancel(); previewJob = null
        cleanup.value.preview?.let { api.storage.cancel(it.token) }
        mutableCleanup.value = StorageCleanupUiState()
    }
    fun confirm() {
        val preview = cleanup.value.preview ?: return
        if (cleanup.value.busy || cleanup.value.receipt != null) return
        val ticket = generation
        mutableCleanup.value = cleanup.value.copy(busy = true, error = null)
        scope.launch {
            val result = attempt { api.storage.confirm(preview.token) }
            if (ticket != generation || !isActive) return@launch
            mutableCleanup.value = when(result) {
                is ApiResult.Success -> StorageCleanupUiState(receipt = result.value)
                is ApiResult.Failure -> StorageCleanupUiState(error = result.error)
            }
            refresh()
        }
    }
    fun cancel() {
        val preview=cleanup.value.preview
        if(preview!=null) api.storage.cancel(preview.token)
        else if(cleanup.value.busy) discardPreview()
    }
    private suspend fun <T> attempt(block: suspend () -> ApiResult<T>): ApiResult<T> = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "storage.unavailable", retryable=true)) }
    override fun onCleared() { cancel(); super.onCleared() }
}
