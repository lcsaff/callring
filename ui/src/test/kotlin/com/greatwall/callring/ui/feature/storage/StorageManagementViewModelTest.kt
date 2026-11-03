package com.greatwall.callring.ui.feature.storage

import com.greatwall.callring.contracts.storage.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StorageManagementViewModelTest {
    @Test fun cancellingPreparationStopsPreviewJobBeforeAnyTokenExists() = runTest {
        var cancelled=false
        val api=object:UiFakeApi() {
            override val storage=object:StorageManagementApi {
                override suspend fun inventory()=ApiResult.Success(StorageInventory(0,0,0,0,0))
                override suspend fun preview(selection:ArchiveCleanupSelection):ApiResult<ArchiveCleanupPreview> {
                    try { awaitCancellation() } finally { cancelled=true }
                }
                override suspend fun confirm(token:String):ApiResult<ArchiveCleanupReceipt> = error("No preview has been confirmed")
                override fun cancel(token:String) = error("No token exists during preparation")
            }
        }
        val model=StorageManagementViewModel(api,backgroundScope)
        model.preview(ArchiveCleanupSelection(0,10,setOf(ArchiveStatus.COMPLETE))); runCurrent()
        assertTrue(model.cleanup.value.busy)
        model.cancel(); runCurrent()
        assertTrue(cancelled); assertFalse(model.cleanup.value.busy); assertNull(model.cleanup.value.preview)
    }
    @Test fun changingSelectionDiscardsLatePreviewAndDoesNotConfirmIt() = runTest {
        val pending = CompletableDeferred<ApiResult<ArchiveCleanupPreview>>()
        var confirmations = 0
        val api = object : UiFakeApi() {
            override val storage = object : StorageManagementApi {
                override suspend fun inventory() = ApiResult.Success(StorageInventory(null,0,0,0,null))
                override suspend fun preview(selection: ArchiveCleanupSelection) = pending.await()
                override suspend fun confirm(token: String): ApiResult<ArchiveCleanupReceipt> { confirmations++; return ApiResult.Success(ArchiveCleanupReceipt(emptyList(),false)) }
                override fun cancel(token: String) = Unit
            }
        }
        val model = StorageManagementViewModel(api, backgroundScope)
        model.preview(ArchiveCleanupSelection(0,10,setOf(ArchiveStatus.COMPLETE))); runCurrent()
        model.discardPreview()
        pending.complete(ApiResult.Success(ArchiveCleanupPreview("old",listOf(CleanupTarget(1,1)),ArchiveCleanupSelection(0,10,setOf(ArchiveStatus.COMPLETE)),1,0,100)))
        runCurrent(); model.confirm(); runCurrent()
        assertNull(model.cleanup.value.preview); assertEquals(0,confirmations)
    }
}
