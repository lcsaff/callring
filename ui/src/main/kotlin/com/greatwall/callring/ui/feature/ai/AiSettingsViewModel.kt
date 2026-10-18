package com.greatwall.callring.ui.feature.ai

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class AiSettingsViewModel(private val api: CallringUiApi, testScope: CoroutineScope? = null) : UiViewModel(testScope) {
    private val loader = RequestLoader<AiSettingsView>(scope)
    private val writer = MutationRunner(scope)
    val state = loader.state
    val mutation = writer.state
    val draft = MutableStateFlow<AiSettingsView?>(null)
    val dirty = MutableStateFlow(false)
    private var baseline: AiSettingsView? = null
    init { scope.launch { api.changes.observe().collect { refresh() } } }
    fun refresh() = loader.load(onSuccess = { if(!dirty.value) { baseline = it; draft.value = it } }) { api.aiSettings.getAiView() }
    fun edit(value: AiSettingsView) { if(!mutation.value.busy) { draft.value = value; dirty.value = value != baseline } }
    fun credentialInput(slot: AiSlot): CredentialDraft? {
        val base = baseline ?: return null; val value = draft.value ?: return null
        val before = if(slot == AiSlot.ANALYSIS) base.analysis else base.transcription
        val after = if(slot == AiSlot.ANALYSIS) value.analysis else value.transcription
        fun <T> change(a: T, b: T): PatchField<T> = if(a == b) PatchField.Keep else PatchField.Set(b)
        return CredentialDraft(slot,base,ConnectionPatch(change(before.protocol,after.protocol),change(before.url,after.url),change(before.model,after.model)))
    }
    fun credentialSaved(slot: AiSlot) { scope.launch {
        when(val result = api.aiSettings.getAiView()) {
            is ApiResult.Failure -> refresh()
            is ApiResult.Success -> {
                val saved = result.value
                fun AiSettingsView.rebase() = if(slot == AiSlot.ANALYSIS) copy(analysis = saved.analysis, revision = saved.revision) else copy(transcription = saved.transcription, revision = saved.revision)
                baseline = baseline?.rebase() ?: saved
                draft.value = draft.value?.rebase() ?: saved
                dirty.value = draft.value != baseline
                refresh()
            }
        }
    } }
    fun save() {
        val base = baseline ?: return; val value = draft.value ?: return
        fun <T> change(a: T, b: T): PatchField<T> = if(a == b) PatchField.Keep else PatchField.Set(b)
        fun connection(a: AiConnectionView, b: AiConnectionView) = ConnectionPatch(change(a.protocol,b.protocol),change(a.url,b.url),change(a.model,b.model))
        writer.run({ api.aiSettings.patchAi(base, AiSettingsPatch(connection(base.analysis,value.analysis), connection(base.transcription,value.transcription), change(base.budget,value.budget))) }, "AI 设置已保存") {
            baseline = it; draft.value = it; dirty.value = false; refresh()
        }
    }
    fun test(slot: AiSlot) {
        val base = baseline ?: return; val value = draft.value ?: return
        fun patch(connection: AiConnectionView) = ConnectionPatch(PatchField.Set(connection.protocol),PatchField.Set(connection.url),PatchField.Set(connection.model))
        writer.run({ api.aiSettings.testDraft(base,AiSettingsPatch(patch(value.analysis),patch(value.transcription)),slot) }, "连接测试已通过；草稿尚未保存")
    }
}

class ScopedRecordingSettingsViewModel(private val api: CallringUiApi, testScope: CoroutineScope? = null) : UiViewModel(testScope) {
    private val loader = RequestLoader<RecordingSettings>(scope)
    private val writer = MutationRunner(scope)
    val state = loader.state
    val mutation = writer.state
    val draft = MutableStateFlow<RecordingSettings?>(null)
    val dirty = MutableStateFlow(false)
    private var base: RecordingSettingsView? = null
    private var baseline: RecordingSettings? = null
    init { scope.launch { api.changes.observe().collect { refresh() } } }
    fun refresh() = loader.load(onSuccess = { if(!dirty.value) { baseline = it; draft.value = it } }) {
        when(val result = api.aiSettings.getRecordingView()) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> { if(!dirty.value) base = result.value; ApiResult.Success(result.value.let { RecordingSettings(recordingEnabled = it.recordingEnabled, automaticAnalysis = it.automaticAnalysis, captureMode = it.captureMode, systemFolder = if(it.folderConfigured) "configured" else null) }) }
        }
    }
    fun edit(value: RecordingSettings) { if(!mutation.value.busy) { draft.value = value; dirty.value = value != baseline } }
    fun save() {
        val original = base ?: return; val value = draft.value ?: return
        fun <T> change(a: T, b: T): PatchField<T> = if(a == b) PatchField.Keep else PatchField.Set(b)
        writer.run({ api.aiSettings.patchRecording(original, RecordingSettingsPatch(change(original.recordingEnabled,value.recordingEnabled),change(original.automaticAnalysis,value.automaticAnalysis),change(original.captureMode,value.captureMode))) }, "录音设置已保存") {
            dirty.value = false; refresh()
        }
    }
    fun folder(document: DocumentRef) = writer.run({ api.recordings.setSystemFolder(document) }, "系统录音目录已授权") {
        draft.value = draft.value?.copy(systemFolder = "configured")
        baseline = baseline?.copy(systemFolder = "configured")
        refresh()
    }
}
