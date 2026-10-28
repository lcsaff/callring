package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ArchivePagingState(val nextCursor: ArchiveListCursor? = null, val complete: Boolean = false,
    val preparing: Boolean = false, val remaining: Long = 0)

/** One bounded page in flight; source invalidation discards the old page chain. */
class RecordingArchivesViewModel(private val api: RecordingApi, testScope: CoroutineScope? = null, changes: ChangeFeed) : UiViewModel(testScope) {
    private val mutable = MutableStateFlow(LoadState<List<ArchiveListItem>>())
    val state = mutable.asStateFlow()
    private val pagingMutable = MutableStateFlow(ArchivePagingState())
    val paging = pagingMutable.asStateFlow()
    private val subscription = ChangeSubscription(setOf(DataDomain.RECORDING_ARCHIVES))
    private var requested: ArchiveListCursor? = null
    private var preparation: Job? = null
    private var reset = 0L
    private var observed: InvalidationToken? = null
    private data class Read(val cursor: ArchiveListCursor?, val result: ApiResult<ArchiveListPage>)
    private val loader: RefreshCoordinator<Read> = RefreshCoordinator(scope,
        { changes.snapshots().value.token(subscription) },
        {
            val cursor = requested
            Read(cursor, try { api.queryArchives(ArchiveListQuery(cursor)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "recording.storage_or_network", retryable = true)) })
        }, { read ->
            when (val result = read.result) {
                is ApiResult.Failure -> {
                    if (read.cursor != null && result.error.message == "recording.page_changed") refresh()
                    else mutable.value = mutable.value.copy(error = result.error)
                }
                is ApiResult.Success -> {
                    val page = result.value
                    pagingMutable.value = ArchivePagingState(page.nextCursor, page.complete, page.preparing, page.remaining)
                    if (page.preparing) {
                        val ticket = reset
                        preparation?.cancel()
                        preparation = scope.launch {
                            delay(100)
                            if (ticket == reset) loaderRequest()
                        }
                    } else {
                        val items = if (read.cursor == null) page.items else mutable.value.data.orEmpty() + page.items
                        mutable.value = LoadState(items, loaded = true)
                    }
                }
            }
        }, { loading -> mutable.value = mutable.value.copy(loading = loading) })

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            changes.snapshots().collect {
                val next = it.token(subscription)
                if (next != observed) { observed = next; refresh() }
            }
        }
    }
    private fun loaderRequest() { loader.request(parameter = requested to reset, force = true) }
    fun refresh() {
        reset++
        preparation?.cancel()
        requested = null
        pagingMutable.value = ArchivePagingState()
        loaderRequest()
    }
    fun more() {
        if (mutable.value.loading || pagingMutable.value.preparing || pagingMutable.value.complete) return
        requested = pagingMutable.value.nextCursor ?: return
        loaderRequest()
    }
}
