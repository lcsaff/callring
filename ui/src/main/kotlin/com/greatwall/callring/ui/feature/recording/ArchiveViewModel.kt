package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class ArchiveFeedbackSection { RECORDING, TRANSCRIPT, ANALYSIS }

class ArchiveViewModel(private val api: RecordingApi, private val callId: Long,
    private val number: String?, private val startedAt: Long?, testScope: CoroutineScope? = null,
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val changes: ChangeFeed,
    private val callers: CallersApi? = null,
) : UiViewModel(testScope) {
    private val mutableState = MutableStateFlow(LoadState<CallArchive?>())
    val state = mutableState.asStateFlow()
    val playback = RecordingPlaybackState(api, changes, scope, cleanupDispatcher)
    internal val suggestions = ArchiveSuggestionsModel(api, changes, scope)
    private var canonicalId = callId
    private var requestedToken: InvalidationToken? = null
    private fun subscription(id: Long = canonicalId) = ChangeSubscription(setOf(DataDomain.RECORDING_ARCHIVES, DataDomain.RUNTIME),
        mapOf(DataDomain.RUNTIME to setOf("offlineTranscription:$id"), DataDomain.RECORDING_ARCHIVES to buildSet {
            add(id.toString())
            if (callId > 0) add("statistics:$callId")
            facts.value.data?.linkedScreeningCallId?.let { add(it.toString()); add("statistics:$it") }
        }))
    private data class Read(val before: InvalidationSnapshot, val result: ApiResult<CallArchive?>, val identity: Long)
    private val loader: RefreshCoordinator<Read> = RefreshCoordinator(scope, { changes.snapshots().value.token(subscription()) }, {
        val before = changes.snapshots().value
        val historyTarget = facts.value.data?.recording?.archiveId
        if (callers != null && historyTarget == null) return@RefreshCoordinator Read(before, ApiResult.Success(null), callId)
        val result = api.getArchive(historyTarget ?: callId)
        val archive = (result as? ApiResult.Success)?.value
        val identity = if (historyTarget != null) historyTarget else if (archive != null) archive.callId else if (callId > 0 && result is ApiResult.Success) {
            when (val resolved = api.getIdentity(callId)) {
                is ApiResult.Success -> resolved.value.callId
                is ApiResult.Failure -> return@RefreshCoordinator Read(before, resolved, canonicalId)
            }
        } else canonicalId
        Read(before, result, identity)
    }, { read ->
        val current = changes.snapshots().value
        val resolved = subscription(read.identity)
        if (read.before.token(resolved) != current.token(resolved)) loaderRequest()
        else {
            canonicalId = read.identity
            requestedToken = current.token(resolved)
            mutableState.value = when (val result = read.result) {
                is ApiResult.Success -> {
                    suggestions.show(result.value)
                    LoadState(result.value, loaded = true)
                }
                is ApiResult.Failure -> mutableState.value.copy(error = result.error)
            }
        }
    }, { loading -> mutableState.value = mutableState.value.copy(loading = loading) })
    private fun loaderRequest(): Unit { loader.request(force = true) }
    private val facts = MutableStateFlow(LoadState<CallHistoryEntry>())
    val history = facts.asStateFlow()
    private fun factsSubscription(entry: CallHistoryEntry? = facts.value.data): ChangeSubscription {
        val ids = listOfNotNull(callId, entry?.linkedScreeningCallId, entry?.recording?.archiveId)
        val entities = mutableMapOf(DataDomain.RECORDING_ARCHIVES to ids.flatMap { listOf(it.toString(), "statistics:$it") }.toSet())
        (entry?.call?.number ?: number)?.let {
            entities[DataDomain.CALLS] = setOf(it)
            entities[DataDomain.CALLERS] = setOf(it)
        }
        return ChangeSubscription(setOf(DataDomain.CALLS, DataDomain.CALLERS, DataDomain.RECORDING_ARCHIVES), entities)
    }
    private var factsToken: InvalidationToken? = null
    private data class HistoryRead(val before: InvalidationSnapshot, val result: ApiResult<CallHistoryEntry>)
    private val historyLoader: RefreshCoordinator<HistoryRead>? = callers?.let { api ->
        RefreshCoordinator(scope, { changes.snapshots().value.token(factsSubscription()) }, {
            val before = changes.snapshots().value
            val result = try { api.getCallHistoryDetail(callId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "无法加载通话详情，请重试", retryable = true)) }
            HistoryRead(before, result)
        }, { read ->
            val entry = (read.result as? ApiResult.Success)?.value
            val subscription = factsSubscription(entry ?: facts.value.data)
            val token = changes.snapshots().value.token(subscription)
            if (read.before.token(subscription) != token) refreshHistory()
            else {
                factsToken = token
                val previous = facts.value.data?.recording?.archiveId
                val wasLoaded = facts.value.loaded
                facts.value = when (val result = read.result) {
                    is ApiResult.Success -> LoadState(result.value, loaded = true)
                    is ApiResult.Failure -> if (result.error.code == ErrorCode.NOT_FOUND) LoadState(error = result.error)
                        else facts.value.copy(error = result.error)
                }
                val current = facts.value.data?.recording?.archiveId
                if (previous != current || !wasLoaded && entry != null) {
                    mutableState.value = LoadState()
                    suggestions.show(null)
                    loaderRequest()
                }
            }
        }, { loading -> facts.value = facts.value.copy(loading = loading) })
    }
    private fun refreshHistory() { historyLoader?.request(force = true) }

    private val fileLoader = RequestLoader<List<RecordingFile>>(scope)
    val files = fileLoader.state
    private val writer = MutationRunner(scope)
    val mutation = writer.state
    private val feedback = MutableStateFlow(ArchiveFeedbackSection.RECORDING)
    val feedbackSection = feedback.asStateFlow()
    private fun <T> write(section: ArchiveFeedbackSection, request: suspend () -> ApiResult<T>, message: String = "", onSuccess: (T) -> Unit = {}) {
        if (mutation.value.busy) return
        feedback.value = section
        writer.run(request, message, onSuccess)
    }
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            changes.snapshots().collect { snapshot ->
                val token = snapshot.token(subscription())
                if ((callers == null || facts.value.data?.recording?.archiveId != null) && requestedToken != token) {
                    requestedToken = token
                    loader.request()
                }
                if (callers != null) {
                    val nextFactsToken = snapshot.token(factsSubscription())
                    if (factsToken != nextFactsToken) { factsToken = nextFactsToken; historyLoader?.request() }
                }
            }
        }
    }
    fun refresh() {
        if (callers == null || facts.value.loaded) loader.request(force = true)
        refreshHistory()
    }
    fun listSystem() = fileLoader.load { api.systemRecordings() }
    fun import(document: DocumentRef, source: RecordingSource = RecordingSource.IMPORTED) {
        val call = history.value.data?.call
        val targetNumber = call?.number ?: number ?: return
        val targetStartedAt = call?.startedAtEpochMillis ?: startedAt ?: return
        write(ArchiveFeedbackSection.RECORDING, { api.importRecording(callId, targetNumber, targetStartedAt, document, source) }, "录音已导入")
    }
    private fun archiveTarget() = facts.value.data?.recording?.archiveId ?: state.value.data?.callId ?: callId
    fun transcribeOffline() = write(ArchiveFeedbackSection.TRANSCRIPT, { api.transcribeOffline(archiveTarget()) }, "")
    fun cancelOffline(runId: Long) = write(ArchiveFeedbackSection.TRANSCRIPT, { api.cancelOfflineTranscription(archiveTarget(), runId) }, "")
    fun retry() = write(ArchiveFeedbackSection.ANALYSIS, { api.retry(archiveTarget()) }, "已提交档案补全（不调用模型）")
    fun analyze() {
        val baseline = state.value.data
        val target = archiveTarget()
        write(ArchiveFeedbackSection.ANALYSIS, { api.analyzeTranscript(target) }, "已提交 AI 分析") {
            if (state.value.data == baseline) suggestions.show(null)
        }
    }
    fun togglePlayback() {
        val playing = playback.state.value.data?.callId
        val linked = history.value.data?.linkedScreeningCallId
        playback.toggle(archiveTarget(), linked?.takeIf { it == playing } ?: callId)
    }
    fun delete() = write(ArchiveFeedbackSection.RECORDING, { api.delete(archiveTarget()) }, "本次归档已删除")

}
