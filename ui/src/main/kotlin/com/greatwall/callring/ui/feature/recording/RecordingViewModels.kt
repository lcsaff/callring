package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class RecordingArchivesViewModel(private val api: RecordingApi, testScope: CoroutineScope? = null) : UiViewModel(testScope) {
    private val loader = RequestLoader<List<CallArchive>>(scope)
    val state = loader.state
    fun refresh() = loader.load {
        when (val result = api.listArchives()) {
            is ApiResult.Success -> ApiResult.Success(result.value.sortedWith(
                compareByDescending<CallArchive> { it.startedAtEpochMillis }.thenBy { it.callId }))
            is ApiResult.Failure -> result
        }
    }
}

/** Secrets deliberately stay in memory: no SavedStateHandle or saved instance bundle. */
class RecordingSettingsViewModel(private val api: RecordingApi, testScope: CoroutineScope? = null) : UiViewModel(testScope) {
    private val loader = RequestLoader<RecordingSettings>(scope)
    val state = loader.state
    private val writer = MutationRunner(scope)
    val mutation = writer.state
    private val mutableDraft = MutableStateFlow<RecordingSettings?>(null)
    val draft = mutableDraft.asStateFlow()
    private val mutableDirty = MutableStateFlow(false)
    val dirty = mutableDirty.asStateFlow()
    fun refresh() = loader.load(onSuccess = { if (!dirty.value) mutableDraft.value = it }) { api.getSettings() }
    fun edit(value: RecordingSettings) {
        if (!mutation.value.busy) { mutableDraft.value = value; mutableDirty.value = value != state.value.data }
    }
    fun copyAnalysisToTranscription() { draft.value?.let { edit(it.copy(transcription = it.analysis.copy(protocol = AiProtocol.OPENAI))) } }
    fun copyTranscriptionToAnalysis() { draft.value?.let { edit(it.copy(analysis = it.transcription)) } }
    fun save() { val value = draft.value ?: return
        writer.run({ api.saveSettings(value) }, "设置已保存") { mutableDraft.value = it; mutableDirty.value = false; refresh() }
    }
    fun test(transcription: Boolean) { val value = draft.value ?: return
        writer.run({ api.testConnection(if (transcription) value.transcription else value.analysis, transcription) }, "连接测试成功")
    }
    fun folder(document: DocumentRef) = writer.run({ api.setSystemFolder(document) }, "已授权录音目录") { result ->
        mutableDraft.value = (draft.value ?: result).copy(systemFolder = result.systemFolder)
        mutableDirty.value = mutableDraft.value != result
        refresh()
    }
}

class ArchiveViewModel(private val api: RecordingApi, private val callers: CallersApi, private val callId: Long,
    private val number: String, private val startedAt: Long, testScope: CoroutineScope? = null,
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO) : UiViewModel(testScope) {
    private val loader = RequestLoader<CallArchive?>(scope)
    val state = loader.state
    private val fileLoader = RequestLoader<List<RecordingFile>>(scope)
    val files = fileLoader.state
    private val writer = MutationRunner(scope)
    val mutation = writer.state
    fun refresh() = loader.load { api.getArchive(callId) }
    fun listSystem() = fileLoader.load { api.systemRecordings() }
    fun import(document: DocumentRef, source: RecordingSource = RecordingSource.IMPORTED) = writer.run(
        { api.importRecording(callId, number, startedAt, document, source) }, "录音已导入") { refresh() }
    fun retry() = writer.run({ api.retry(callId) }, "已提交分析；录音和文字将发送到配置的服务") { refresh() }
    fun delete() = writer.run({ api.delete(callId) }, "本次归档已删除") { refresh() }
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
        val analysis = state.value.data?.analysis ?: return
        writer.run({
            when (val current = callers.getEditor(number)) {
                is ApiResult.Failure -> current
                is ApiResult.Success -> current.value.draft.let { draft ->
                    callers.saveProfile(SaveCallerProfile(number, analysis.name.ifBlank { draft.name },
                        analysis.organization.ifBlank { draft.department }, draft.positionId, draft.whitelisted))
                }
            }
        }, "姓名和单位已采纳；请到档案核对")
    }
}

fun recordingMessage(key: String): String = when (key) {
    "ai.invalid_url" -> "服务地址须为 HTTPS，不能包含账户、查询参数或片段"
    "ai.invalid_key" -> "请填写有效 API 密钥"
    "ai.invalid_model" -> "请填写模型名称（最多 256 字）"
    "ai.transcription_requires_openai" -> "转写仅支持 OpenAI 兼容协议"
    "recording.unavailable" -> "当前设备暂不支持录音服务"
    "recording.unverified" -> "音频质量未经验证，可能缺少一方声音"
    "recording.permission_required" -> "需要文件或麦克风访问权限，请重新授权"
    "recording.invalid_configuration_or_audio" -> "配置或音频文件无效，请检查设置并重新选择音频"
    "recording.busy_or_changed" -> "正在处理或归档已变化，请刷新后重试"
    "recording.storage_or_network" -> "存储或网络操作失败，请检查设备空间与连接"
    "recording.missing_audio" -> "录音文件缺失，请重新导入"
    "recording.not_found" -> "尚无归档，请先导入录音"
    "recording.microphone_unverified", "recording.microphone_limited" -> "实验性麦克风录音，可能只有本机一方声音"
    "recording.waiting" -> "等待录音导入"
    else -> when {
        key.startsWith("ai.") -> "AI 请求失败，请检查服务地址、密钥、模型、网络或服务额度后重试"
        key.startsWith("recording.") -> "录音操作未完成，请检查文件访问授权和设备支持后重试"
        else -> "操作未完成，请稍后重试"
    }
}
