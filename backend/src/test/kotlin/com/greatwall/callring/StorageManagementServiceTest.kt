package com.greatwall.callring

import com.greatwall.callring.application.storage.*
import com.greatwall.callring.application.ports.storage.*
import com.greatwall.callring.contracts.storage.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.common.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class StorageManagementServiceTest {
    private class Data : StorageArchiveAccess {
        val rows = (1L..120L).associateWith { StorageArchiveState(it, 1, ArchiveStatus.COMPLETE, 10, false) }.toMutableMap()
        var stamp = "owner:1"
        val removed = mutableListOf<Long>()
        var pages = 0
        var maximumLimit = 0
        var preparingPages = 0
        var onRemove: (Long) -> Unit = {}
        var onSnapshot: (Long) -> Unit = {}
        override fun sourceStamp() = stamp
        override fun snapshot(callId: Long) = rows[callId].also { onSnapshot(callId) }
        override suspend fun page(query: ArchiveListQuery): ApiResult<ArchiveListPage> {
            pages++; maximumLimit = maxOf(maximumLimit, query.limit)
            if(preparingPages > 0) { preparingPages--; return ApiResult.Success(ArchiveListPage(emptyList(),null,false,preparing=true,remaining=preparingPages+1L)) }
            val start = query.cursor?.callId ?: 0
            val items = rows.keys.sorted().filter { it > start }.take(query.limit).map { id ->
                ArchiveListItem(id, 1, "123", id, rows.getValue(id).status, RecordingSource.IMPORTED, null, true, "")
            }
            val complete = items.lastOrNull()?.callId == rows.keys.maxOrNull() || items.isEmpty()
            return ApiResult.Success(ArchiveListPage(items, if (complete) null else ArchiveListCursor("o", 1, 0, items.last().callId, query.limit), complete))
        }
        override suspend fun remove(callId: Long, expectedGeneration: Long): ApiResult<Unit> {
            if (callId == 2L) return ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "test.failure"))
            removed += callId; rows.remove(callId); onRemove(callId); return ApiResult.Success(Unit)
        }
    }
    private fun service(data: Data, now: () -> Long = { 100 }) = StorageManagementService(data,
        StorageInventoryPort { StorageInventory(null, 0, null, 0, null) }, now, operationActive={true})
    private fun <T> ApiResult<T>.value() = (this as ApiResult.Success).value

    @Test fun previewPagesAndExcludesBusyWithoutReadingBodies() = runBlocking {
        val data = Data(); data.rows[3] = data.rows.getValue(3).copy(active = true)
        val api = service(data)
        val preview = api.preview(ArchiveCleanupSelection(0, 200, setOf(ArchiveStatus.COMPLETE))).value()
        assertEquals(119, preview.targets.size); assertEquals(1, preview.excludedActive)
        assertEquals(1190L, preview.audioBytes)
        assertNull(api.inventory().value().freeBytes)
    }
    @Test fun coldPreparationContinuesInBoundedPagesUntilCompletePreviewIsReady() = runBlocking {
        val data=Data(); data.preparingPages=2
        val result=service(data).preview(ArchiveCleanupSelection(0,4,setOf(ArchiveStatus.COMPLETE)))
        assertTrue("Preparation must continue without requiring manual retries",result is ApiResult.Success)
        assertEquals(listOf(1L,2L,3L),result.value().targets.map { it.callId })
        assertEquals(5,data.pages); assertEquals(50,data.maximumLimit)
    }
    @Test fun changedSourceRejectsPreviewBeforeAnyDeletion() = runBlocking {
        val data = Data(); val api = service(data)
        val preview = api.preview(ArchiveCleanupSelection(0, 4, setOf(ArchiveStatus.COMPLETE))).value()
        data.stamp = "owner:2"
        assertTrue(api.confirm(preview.token) is ApiResult.Failure)
        assertTrue(data.removed.isEmpty())
    }
    @Test fun confirmingTicketRemainsCancellableDuringValidationAndPreviewEviction() = runBlocking {
        val data=Data(); val api=service(data)
        val preview=api.preview(ArchiveCleanupSelection(0,4,setOf(ArchiveStatus.COMPLETE))).value()
        val validating=CountDownLatch(1); val release=CountDownLatch(1); val first=AtomicBoolean(true)
        data.onSnapshot={ if(first.compareAndSet(true,false)) { validating.countDown(); check(release.await(5,TimeUnit.SECONDS)) } }
        val confirming=async(Dispatchers.Default) { api.confirm(preview.token) }
        try {
            assertTrue(validating.await(3,TimeUnit.SECONDS))
            repeat(8) { api.preview(ArchiveCleanupSelection(1000,2000,setOf(ArchiveStatus.COMPLETE))).value() }
            api.cancel(preview.token)
        } finally { release.countDown() }
        val receipt=confirming.await().value()
        assertTrue(receipt.cancelled)
        assertTrue(receipt.items.all { it.status==CleanupItemStatus.UNSTARTED })
        assertTrue(data.removed.isEmpty())
        assertEquals(receipt,api.confirm(preview.token).value())
    }
    @Test fun ownerClosedDuringInventoryDoesNotReturnLateObservation() = runBlocking {
        val owner=AtomicBoolean(true)
        val api=StorageManagementService(Data(),StorageInventoryPort {
            owner.set(false); StorageInventory(0,0,0,0,0)
        },operationActive=owner::get)
        assertTrue(runCatching { api.inventory() }.exceptionOrNull() is CancellationException)
    }
    @Test fun callerCannotMutatePreviewAuthorizationOrRetainedReceipt() = runBlocking {
        val data=Data(); val api=service(data)
        val statuses=mutableSetOf(ArchiveStatus.COMPLETE)
        val preview=api.preview(ArchiveCleanupSelection(0,4,statuses)).value()
        statuses.clear()
        assertEquals(setOf(ArchiveStatus.COMPLETE),preview.selection.statuses)
        assertThrows(UnsupportedOperationException::class.java) { (preview.targets as MutableList<CleanupTarget>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (preview.selection.statuses as MutableSet<ArchiveStatus>).clear() }
        val receipt=api.confirm(preview.token).value()
        assertEquals(3,receipt.items.size)
        assertThrows(UnsupportedOperationException::class.java) { (receipt.items as MutableList<CleanupItemReceipt>).clear() }
        assertEquals(receipt,api.confirm(preview.token).value())
    }
    @Test fun cancellationPreservesCommittedFailuresAndUnstartedAndNeverReplays() = runBlocking {
        val data = Data(); val api = service(data)
        val preview = api.preview(ArchiveCleanupSelection(0, 5, setOf(ArchiveStatus.COMPLETE))).value()
        data.onRemove = { if (it == 3L) api.cancel(preview.token) }
        val receipt = api.confirm(preview.token).value()
        assertEquals(listOf(CleanupItemStatus.COMMITTED, CleanupItemStatus.FAILED, CleanupItemStatus.COMMITTED, CleanupItemStatus.UNSTARTED), receipt.items.map { it.status })
        assertEquals(receipt, api.confirm(preview.token).value())
        assertEquals(listOf(1L, 3L), data.removed)
    }
    @Test fun expiryAndGenerationAndActiveChangesRejectBeforeCommit() = runBlocking {
        for (kind in 0..2) {
            val data = Data(); var clock = 100L; val api = service(data) { clock }
            val preview = api.preview(ArchiveCleanupSelection(0, 4, setOf(ArchiveStatus.COMPLETE))).value()
            when(kind) { 0 -> clock = preview.expiresAtEpochMillis; 1 -> data.rows[1] = data.rows.getValue(1).copy(generation=2); 2 -> data.rows[1] = data.rows.getValue(1).copy(active=true) }
            assertTrue(api.confirm(preview.token) is ApiResult.Failure); assertTrue(data.removed.isEmpty())
        }
    }
    @Test fun refusesExcessSelectionRatherThanSilentlyTruncating() = runBlocking {
        val data = Data(); (121L..1100L).forEach { data.rows[it] = StorageArchiveState(it,1,ArchiveStatus.COMPLETE,0,false) }
        assertTrue(service(data).preview(ArchiveCleanupSelection(0,2000,setOf(ArchiveStatus.COMPLETE))) is ApiResult.Failure)
    }
    @Test fun tenThousandMetadataRowsArePagedAndOnlySelectedTargetsAreRetained() = runBlocking {
        val data = Data(); (121L..10000L).forEach { data.rows[it] = StorageArchiveState(it,1,ArchiveStatus.COMPLETE,10,false) }
        val api = service(data)
        val preview = api.preview(ArchiveCleanupSelection(9901,10001,setOf(ArchiveStatus.COMPLETE))).value()
        assertEquals(100,preview.targets.size); assertEquals(200,data.pages); assertEquals(50,data.maximumLimit)
        val receipt = api.confirm(preview.token).value()
        assertEquals(100,receipt.items.count { it.status==CleanupItemStatus.COMMITTED })
        assertEquals(9900,data.rows.size)
    }
}
