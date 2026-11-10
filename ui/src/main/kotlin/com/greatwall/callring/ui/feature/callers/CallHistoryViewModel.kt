package com.greatwall.callring.ui.feature.callers

import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.RecordingApi
import com.greatwall.callring.ui.feature.recording.RecordingPlaybackState
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class CallHistoryTranscript(val archiveId: Long, val number: String)

class CallHistoryViewModel(
    private val callers: CallersApi,
    private val recordings: RecordingApi,
    changes: ChangeFeed,
    testScope: CoroutineScope? = null,
) : UiViewModel(testScope) {
    private val mutable = MutableStateFlow(LoadState<CallHistoryPage>())
    val state = mutable.asStateFlow()
    val playback = RecordingPlaybackState(recordings, changes, scope)
    private val transcriptLoader = RequestLoader<String>(scope)
    val transcript = transcriptLoader.state
    private val transcriptSelection = MutableStateFlow<CallHistoryTranscript?>(null)
    val selectedTranscript = transcriptSelection.asStateFlow()
    private var cursor: CallHistoryCursor? = null
    private var pageDepth = 1
    private val subscription = ChangeSubscription(setOf(DataDomain.CALLS, DataDomain.RECORDING_ARCHIVES, DataDomain.CALLERS))
    private val observer = ChangeObserver(changes, subscription)
    private data class Read(val cursor: CallHistoryCursor?, val result: ApiResult<CallHistoryPage>)
    private val loader: RefreshCoordinator<Read> = RefreshCoordinator(scope, observer::token, {
        val requested = cursor
        val result = try { readPages(requested, if (requested == null) pageDepth else 1) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "无法加载通话记录，请重试", retryable = true)) }
        Read(requested, result)
    }, { read ->
        when (val result = read.result) {
            is ApiResult.Success -> {
                val page = result.value
                if (read.cursor != null) pageDepth++
                mutable.value = LoadState(if (read.cursor == null) page else
                    page.copy(entries = mutable.value.data?.entries.orEmpty() + page.entries), loaded = true)
            }
            is ApiResult.Failure -> {
                if (read.cursor != null && result.error.message == "call.history_changed") refresh()
                else mutable.value = mutable.value.copy(error = result.error)
            }
        }
    }, { busy -> mutable.value = mutable.value.copy(loading = busy, error = if (busy) null else mutable.value.error) })

    init { observer.start(scope, ::refresh) }

    private suspend fun readPages(start: CallHistoryCursor?, count: Int): ApiResult<CallHistoryPage> {
        var next = start
        val rows = mutableListOf<CallHistoryEntry>()
        repeat(count) { index ->
            when (val result = callers.queryCallHistory(CallHistoryQuery(cursor = next))) {
                is ApiResult.Failure -> return result
                is ApiResult.Success -> {
                    val page = result.value
                    rows += page.entries
                    next = page.nextCursor
                    if (next == null || index == count - 1) return ApiResult.Success(page.copy(entries = rows.toList()))
                }
            }
        }
        error("At least one page must be requested")
    }

    fun refresh() {
        cursor = null
        loader.request(force = true)
    }

    fun more() {
        if (mutable.value.loading) return
        cursor = mutable.value.data?.nextCursor ?: return
        loader.request(force = true)
    }

    fun openTranscript(row: CallHistoryEntry) {
        val archive = row.recording
        if (archive.availability != CallArchiveAvailability.AVAILABLE || !archive.hasTranscript) return
        val id = archive.archiveId ?: return
        transcriptSelection.value = CallHistoryTranscript(id, row.call.number)
        retryTranscript()
    }

    fun retryTranscript() {
        val selection = transcriptSelection.value ?: return
        transcriptLoader.load(preserveData = false) {
            when (val result = recordings.getArchive(selection.archiveId)) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> result.value?.transcript?.takeIf(String::isNotBlank)?.let { ApiResult.Success(it) }
                    ?: ApiResult.Failure(ApiError(ErrorCode.NOT_FOUND, "转写暂不可用", retryable = true))
            }
        }
    }

    fun closeTranscript() { transcriptSelection.value = null }
}
