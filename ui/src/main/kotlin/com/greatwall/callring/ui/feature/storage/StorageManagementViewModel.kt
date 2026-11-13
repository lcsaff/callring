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
data class NumberProfileResetUiState(val preview: NumberProfileResetPreview? = null,
    val busy: Boolean = false, val error: ApiError? = null)
class StorageManagementViewModel(private val api: CallringUiApi, testScope: CoroutineScope? = null) : SnapshotViewModel<StorageInventory>(
    api.changes, setOf(DataDomain.RECORDING_ARCHIVES), { api.storage.inventory() }, testScope) {
    private val mutableCleanup = MutableStateFlow(StorageCleanupUiState())
    val cleanup = mutableCleanup.asStateFlow()
    private val mutableNumberReset=MutableStateFlow(NumberProfileResetUiState())
    val numberReset=mutableNumberReset.asStateFlow()
    private var resetGeneration=0L
    private var resetJob:Job?=null
    fun previewNumberProfiles() {
        if(numberReset.value.busy||cleanup.value.busy)return
        discardNumberProfiles()
        val generation=resetGeneration
        mutableNumberReset.value=NumberProfileResetUiState(busy=true)
        resetJob=scope.launch {
            val result=attempt {api.storage.previewNumberProfiles()}
            if(generation!=resetGeneration||!isActive)return@launch
            mutableNumberReset.value=when(result) {
                is ApiResult.Success -> NumberProfileResetUiState(preview=result.value)
                is ApiResult.Failure -> NumberProfileResetUiState(error=result.error)
            }
        }
    }
    fun discardNumberProfiles() {
        if(numberReset.value.busy&&numberReset.value.preview!=null)return
        resetGeneration++;resetJob?.cancel();resetJob=null
        numberReset.value.preview?.let { preview -> scope.launch {attempt {api.storage.discardNumberProfiles(preview.token)}} }
        mutableNumberReset.value=NumberProfileResetUiState()
    }
    fun confirmNumberProfiles(keepRecordings:Boolean) {
        val preview=numberReset.value.preview?:return
        if(numberReset.value.busy||cleanup.value.busy)return
        val generation=resetGeneration
        mutableNumberReset.value=numberReset.value.copy(busy=true,error=null)
        resetJob=scope.launch {
            val result=attempt {api.storage.confirmNumberProfiles(preview.token,keepRecordings)}
            if(generation!=resetGeneration||!isActive)return@launch
            mutableNumberReset.value=when(result) {
                is ApiResult.Success -> NumberProfileResetUiState()
                is ApiResult.Failure -> NumberProfileResetUiState(error=result.error)
            }
        }
    }
    private var generation = 0L
    private var previewJob: Job? = null
    fun preview(selection: ArchiveCleanupSelection) {
        if (cleanup.value.busy||numberReset.value.busy) return
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
        if (cleanup.value.busy || numberReset.value.busy || cleanup.value.receipt != null) return
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
    override fun onCleared() { cancel();discardNumberProfiles(); super.onCleared() }
}
