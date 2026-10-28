package com.greatwall.callring.application.ports.storage

import com.greatwall.callring.contracts.storage.StorageInventory
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.recording.*

fun interface StorageInventoryPort { fun sample(): StorageInventory }
data class StorageArchiveState(val callId: Long, val generation: Long, val status: ArchiveStatus, val ownedAudioBytes: Long?, val active: Boolean)
interface StorageArchiveAccess {
    fun sourceStamp(): String
    fun snapshot(callId: Long): StorageArchiveState?
    suspend fun page(query: ArchiveListQuery): ApiResult<ArchiveListPage>
    suspend fun remove(callId: Long, expectedGeneration: Long): ApiResult<Unit>
}
