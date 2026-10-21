package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ArchiveViewModel(private val api: RecordingApi, private val callId: Long,
    private val number: String, private val startedAt: Long, testScope: CoroutineScope? = null,
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val adoptCommand: suspend (Long, Long) -> ApiResult<CallerProfile>,
    private val changes: ChangeFeed) : UiViewModel(testScope) {
    private val mutableState = MutableStateFlow(LoadState<CallArchive?>())
    val state = mutableState.asStateFlow()
    private var canonicalId = callId
    private var requestedToken: InvalidationToken? = null
    private fun subscription(id: Long = canonicalId) = ChangeSubscription(setOf(DataDomain.RECORDING_ARCHIVES),
        mapOf(DataDomain.RECORDING_ARCHIVES to buildSet {
            add(id.toString())
            if (callId > 0) add("statistics:$callId")
        }))
    private data class Read(val before: InvalidationSnapshot, val result: ApiResult<CallArchive?>, val identity: Long)
    private val loader: RefreshCoordinator<Read> = RefreshCoordinator(scope, { changes.snapshots().value.token(subscription()) }, {
        val before = changes.snapshots().value
        val result = api.getArchive(callId)
        val archive = (result as? ApiResult.Success)?.value
        val identity = if (archive != null) archive.callId else if (callId > 0 && result is ApiResult.Success) {
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
                is ApiResult.Success -> LoadState(result.value, loaded = true)
                is ApiResult.Failure -> mutableState.value.copy(error = result.error)
            }
        }
    }, { loading -> mutableState.value = mutableState.value.copy(loading = loading) })
    private fun loaderRequest(): Unit { loader.request(force = true) }
    private val fileLoader = RequestLoader<List<RecordingFile>>(scope)
    val files = fileLoader.state
    private val writer = MutationRunner(scope)
    val mutation = writer.state
    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            changes.snapshots().collect { snapshot ->
                val token = snapshot.token(subscription())
                if (requestedToken != token) { requestedToken = token; loader.request() }
            }
        }
    }
    fun refresh() = loader.request(force = true)
    fun listSystem() = fileLoader.load { api.systemRecordings() }
    fun import(document: DocumentRef, source: RecordingSource = RecordingSource.IMPORTED) = writer.run(
        { api.importRecording(callId, number, startedAt, document, source) }, "录音已导入") {}
    fun retry() = writer.run({ api.retry(callId) }, "已提交分析；录音和文字将发送到配置的服务") {}
    fun delete() = writer.run({ api.delete(callId) }, "本次归档已删除") {}
    fun play() = writer.run({ api.play(callId) }, "正在播放")
    fun stop() = writer.run({ api.stop() }, "已停止播放")
    /** Exit cleanup bypasses the mutation gate and finishes even after the owner scope is cancelled. */
    fun stopForExit(): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        withContext(NonCancellable + cleanupDispatcher) {
            try { api.stop() } catch (_: Exception) { /* No UI remains to receive feedback. */ }
        }
    }
    /** Called only after the user confirms the self-reported fields. Never copies inferred identity or position. */
    fun adoptIdentity() {
        val archive = state.value.data ?: return
        writer.run({ adoptCommand(callId, archive.generation) }, "姓名和单位已采纳；请到档案核对")
    }
}
