package com.greatwall.callring.application.storage

import com.greatwall.callring.application.ports.storage.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.storage.*
import com.greatwall.callring.contracts.recording.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class StorageManagementService(private val archives: StorageArchiveAccess, private val inventory: StorageInventoryPort,
    private val now: () -> Long = System::currentTimeMillis,
    private val operationActive: () -> Boolean) : StorageManagementApi {
    private class Ticket(val preview: ArchiveCleanupPreview, val stamp: String) {
        val cancelled = AtomicBoolean()
        var running = false
        var receipt: ArchiveCleanupReceipt? = null
    }
    private val tickets = linkedMapOf<String, Ticket>()
    private val mutation = Mutex()
    private fun active() { if (!operationActive()) throw CancellationException("storage.closed") }
    private fun failure(key: String, code: ErrorCode = ErrorCode.CONFLICT) = ApiResult.Failure(ApiError(code, key, retryable = true))
    private fun <T> frozenList(items: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(items))
    private fun <T> frozenSet(items: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(items))
    private suspend fun <T> result(block: suspend () -> ApiResult<T>): ApiResult<T> = withContext(Dispatchers.IO) {
        val result = try { active(); block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure("storage.unavailable", ErrorCode.IO_FAILURE) }
        currentCoroutineContext().ensureActive()
        active()
        result
    }
    override suspend fun inventory(): ApiResult<StorageInventory> = result { ApiResult.Success(inventory.sample()) }
    override suspend fun preview(selection: ArchiveCleanupSelection): ApiResult<ArchiveCleanupPreview> {
        // Bind caller-owned collections before the first dispatcher suspension.
        val bound = selection.copy(statuses = frozenSet(selection.statuses))
        return previewBound(bound)
    }
    private suspend fun previewBound(selection: ArchiveCleanupSelection): ApiResult<ArchiveCleanupPreview> = result {
        if (selection.fromEpochMillis >= selection.beforeEpochMillis || selection.statuses.isEmpty())
            return@result failure("storage.invalid_selection", ErrorCode.VALIDATION)
        val stamp = archives.sourceStamp()
        val targets = mutableListOf<CleanupTarget>()
        var excluded = 0
        var bytes: Long? = 0
        var cursor: ArchiveListCursor? = null
        while (true) {
            currentCoroutineContext().ensureActive(); active()
            val page = when(val pageResult = archives.page(ArchiveListQuery(cursor, 50))) {
                is ApiResult.Failure -> return@result pageResult
                is ApiResult.Success -> pageResult.value
            }
            if (page.preparing) {
                // Each query advances only the store's bounded migration budget. Keep the original
                // source/selection binding and yield cancellably before requesting another batch.
                if (archives.sourceStamp() != stamp) return@result failure("storage.preview_changed")
                delay(50)
                continue
            }
            for (item in page.items) {
                if (item.startedAtEpochMillis < selection.fromEpochMillis || item.startedAtEpochMillis >= selection.beforeEpochMillis || item.status !in selection.statuses) continue
                val snapshot = archives.snapshot(item.callId) ?: return@result failure("storage.preview_changed")
                if (snapshot.generation != item.generation) return@result failure("storage.preview_changed")
                if (snapshot.active) { excluded++; continue }
                targets += CleanupTarget(item.callId, item.generation)
                if (targets.size > 1000) return@result failure("storage.selection_too_large", ErrorCode.VALIDATION)
                bytes = if (bytes == null || snapshot.ownedAudioBytes == null) null else Math.addExact(bytes, snapshot.ownedAudioBytes)
            }
            cursor = page.nextCursor
            if (!page.complete && cursor == null) return@result failure("storage.page_incomplete")
            if (page.complete) break
        }
        if (archives.sourceStamp() != stamp) return@result failure("storage.preview_changed")
        val caller = currentCoroutineContext()
        val preview = ArchiveCleanupPreview(UUID.randomUUID().toString(), frozenList(targets), selection, bytes, excluded, now() + 5 * 60_000)
        synchronized(tickets) {
            active(); caller.ensureActive()
            // Bound retained previews and receipts. An evicted token can never initiate work again.
            if (tickets.size >= 8) tickets.entries.firstOrNull { !it.value.running }?.let { tickets.remove(it.key) }
            tickets[preview.token] = Ticket(preview.copy(targets = frozenList(preview.targets),
                selection = preview.selection.copy(statuses = frozenSet(preview.selection.statuses))), stamp)
        }
        ApiResult.Success(preview)
    }
    override suspend fun confirm(token: String): ApiResult<ArchiveCleanupReceipt> = result { mutation.withLock {
        active()
        // Lookup and reserve are one map operation, including the entire validation interval.
        val ticket = synchronized(tickets) { tickets[token]?.also { it.running = true } }
            ?: return@withLock failure("storage.preview_missing")
        try {
            ticket.receipt?.let { return@withLock ApiResult.Success(it) }
            if (now() >= ticket.preview.expiresAtEpochMillis || archives.sourceStamp() != ticket.stamp)
                return@withLock failure("storage.preview_changed")
            for (target in ticket.preview.targets) {
                currentCoroutineContext().ensureActive(); active()
                val state = archives.snapshot(target.callId)
                if (state == null || state.generation != target.generation || state.active) return@withLock failure("storage.preview_changed")
            }
            val receipts = ticket.preview.targets.map { CleanupItemReceipt(it, CleanupItemStatus.UNSTARTED) }.toMutableList()
            val caller = currentCoroutineContext()
            try {
                for ((index, target) in ticket.preview.targets.withIndex()) {
                    if (ticket.cancelled.get() || !caller.isActive || !operationActive()) break
                    // Each formal delete owns a complete commit. Cancellation cannot lose its receipt.
                    val removed = withContext(NonCancellable) {
                        try { archives.remove(target.callId, target.generation) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failure("storage.delete_failed", ErrorCode.IO_FAILURE) }
                    }
                    receipts[index] = when(removed) {
                        is ApiResult.Success -> CleanupItemReceipt(target, CleanupItemStatus.COMMITTED)
                        is ApiResult.Failure -> CleanupItemReceipt(target, CleanupItemStatus.FAILED, removed.error)
                    }
                    if ((index + 1) % 50 == 0) yield()
                }
            } finally {
                ticket.receipt = ArchiveCleanupReceipt(frozenList(receipts), ticket.cancelled.get() || !caller.isActive || !operationActive())
            }
            ApiResult.Success(requireNotNull(ticket.receipt))
        } finally {
            synchronized(tickets) { ticket.running = false }
        }
    } }
    override fun cancel(token: String) { synchronized(tickets) { tickets[token]?.cancelled?.set(true) } }
}
