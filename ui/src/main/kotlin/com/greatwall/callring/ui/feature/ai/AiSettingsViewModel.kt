package com.greatwall.callring.ui.feature.ai

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Both snapshots rendered by the AI page; automatic analysis lives on the recording side. */
data class AiSettingsUi(val ai: AiSettingsView, val recording: RecordingSettingsView)

/**
 * Applies AI and recording settings field by field. Text-like fields coalesce for 500 ms; switches, chips and
 * the thinking mode commit immediately. Automatic analysis is written through one [AiSettingsApi.patchCombined]
 * transaction so the recording-side fields are never overwritten.
 */
class AiSettingsViewModel(
    private val api: CallringUiApi,
    testScope: CoroutineScope? = null,
) : UiViewModel(testScope) {
    private class Queued(
        val request: suspend (AiSettingsUi) -> ApiResult<AiSettingsUi>,
        val message: String? = null,
        val onSuccess: (() -> Unit)? = null,
    )

    private val mutableState = MutableStateFlow(LoadState<AiSettingsUi>())
    val state = mutableState.asStateFlow()
    private val mutableMutation = MutableStateFlow(MutationState())
    val mutation = mutableMutation.asStateFlow()

    private val queue = ArrayDeque<Queued>()
    private var debounced: Queued? = null
    private var debounceJob: Job? = null
    private var writing = false
    private var refreshRequested = false
    private var pendingAnalysis: AiConnectionView? = null
    private var pendingTranscription: AiConnectionView? = null
    private val observation = ChangeObserver(api.changes, ChangeSubscription(setOf(DataDomain.AI_SETTINGS, DataDomain.RECORDING_SETTINGS, DataDomain.RUNTIME), mapOf(DataDomain.RUNTIME to setOf("recordingFolder"))))
    private val refreshCoordinator = RefreshCoordinator(scope, observation::token, { loadForm() }, { result ->
        when (result) {
            is ApiResult.Failure -> mutableState.value = mutableState.value.copy(error = result.error)
            is ApiResult.Success -> mutableState.value = LoadState(result.value, loaded = true)
        }
    }, { loading -> mutableState.value = mutableState.value.copy(loading = loading) })
    init { observation.start(scope) { requestRefresh() } }
    private fun requestRefresh() {
        if (writing) refreshRequested = true else refreshCoordinator.request()
    }
    fun refresh() { if (writing) refreshRequested = true else refreshCoordinator.request(force = true) }

    fun editAnalysis(connection: AiConnectionView) {
        pendingAnalysis = connection
        scheduleDebounced(Queued({ form ->
            val base = form.ai.analysis
            val patch = connectionPatch(base, connection)
            if (patch == ConnectionPatch()) ApiResult.Success(form)
            else patchAi(form, AiSettingsPatch(analysis = patch))
        }, onSuccess = { if (pendingAnalysis == connection) pendingAnalysis = null }))
    }

    fun editTranscription(connection: AiConnectionView) {
        pendingTranscription = connection
        scheduleDebounced(Queued({ form ->
            val base = form.ai.transcription
            val patch = connectionPatch(base, connection)
            if (patch == ConnectionPatch()) ApiResult.Success(form)
            else patchAi(form, AiSettingsPatch(transcription = patch))
        }, onSuccess = { if (pendingTranscription == connection) pendingTranscription = null }))
    }

    fun analysisProtocol(protocol: AiProtocol) = submit(Queued({ form ->
        if (form.ai.analysis.protocol == protocol) ApiResult.Success(form)
        else patchAi(form, AiSettingsPatch(analysis = ConnectionPatch(protocol = PatchField.Set(protocol))))
    }))

    fun thinkingMode(mode: ThinkingMode) = submit(Queued({ form ->
        if (form.ai.analysis.thinkingMode == mode) ApiResult.Success(form)
        else patchAi(form, AiSettingsPatch(analysis = ConnectionPatch(thinkingMode = PatchField.Set(mode))))
    }))

    fun budget(budget: ConversationBudget) = scheduleDebounced(Queued({ form ->
        if (form.ai.budget == budget) ApiResult.Success(form)
        else patchAi(form, AiSettingsPatch(budget = PatchField.Set(budget)))
    }))

    /** One transaction: AI side all Keep, recording side only the automatic-analysis flag. */
    fun automaticAnalysis(enabled: Boolean) = submit(Queued({ form ->
        if (form.recording.automaticAnalysis == enabled) ApiResult.Success(form)
        else when (val result = attempt {
            api.aiSettings.patchCombined(form.recording,
                RecordingSettingsPatch(automaticAnalysis = PatchField.Set(enabled)), form.ai, AiSettingsPatch())
        }) {
            is ApiResult.Success -> ApiResult.Success(form.copy(recording = form.recording.copy(automaticAnalysis = enabled)))
            is ApiResult.Failure -> result
        }
    }))

    fun copyConnection(from: AiSlot, to: AiSlot) = submit(Queued({ form ->
        when (val result = attempt { api.aiSettings.copyConnection(from, to, form.ai.revision) }) {
            is ApiResult.Success -> ApiResult.Success(form.copy(ai = result.value))
            is ApiResult.Failure -> result
        }
    }))

    fun test(slot: AiSlot) = submit(Queued({ form ->
        fun current(connection: AiConnectionView) = ConnectionPatch(
            PatchField.Set(connection.protocol), PatchField.Set(connection.url), PatchField.Set(connection.model))
        val patch = AiSettingsPatch(current(form.ai.analysis), current(form.ai.transcription))
        when (val result = attempt { api.aiSettings.testDraft(form.ai, patch, slot) }) {
            is ApiResult.Success -> ApiResult.Success(form)
            is ApiResult.Failure -> result
        }
    }, message = "连接测试已通过"))

    fun credentialSaved() {
        // The credential dialog writes through patchCombined itself; the page only reloads both snapshots.
        pendingAnalysis = null
        pendingTranscription = null
    }

    /** Cancelling the key dialog keeps the typed connection edits and schedules their normal commit again. */
    fun credentialCancelled() {
        pendingAnalysis?.let { editAnalysis(it) }
        pendingTranscription?.let { editTranscription(it) }
    }

    /**
     * The dialog saves pending connection edits together with the key in one patchCombined transaction, so a
     * debounced background write must not race with it.
     */
    fun credentialInput(slot: AiSlot): CredentialDraft? {
        val form = mutableState.value.data ?: return null
        debounceJob?.cancel()
        debounced = null
        val connection = when (slot) {
            AiSlot.ANALYSIS -> pendingAnalysis?.let { connectionPatch(form.ai.analysis, it) } ?: ConnectionPatch()
            AiSlot.TRANSCRIPTION -> pendingTranscription?.let { connectionPatch(form.ai.transcription, it) }
                ?: ConnectionPatch()
        }
        return CredentialDraft(slot, form.ai, connection)
    }

    private fun connectionPatch(base: AiConnectionView, next: AiConnectionView) = ConnectionPatch(
        change(base.protocol, next.protocol),
        change(base.url, next.url),
        change(base.model, next.model),
    )

    private suspend fun patchAi(form: AiSettingsUi, patch: AiSettingsPatch): ApiResult<AiSettingsUi> =
        when (val result = attempt { api.aiSettings.patchAi(form.ai, patch) }) {
            is ApiResult.Success -> ApiResult.Success(form.copy(ai = result.value))
            is ApiResult.Failure -> result
        }

    private fun <T> change(before: T, after: T): PatchField<T> =
        if (before == after) PatchField.Keep else PatchField.Set(after)

    private fun scheduleDebounced(queued: Queued) {
        debounced = queued
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(500)
            debounced?.let { submit(it) }
            debounced = null
        }
    }

    private fun submit(queued: Queued) {
        queue.addLast(queued)
        startWriter()
    }

    private fun startWriter() {
        if (writing) return
        writing = true
        mutableMutation.value = mutableMutation.value.copy(busy = true, error = null, message = null)
        scope.launch {
            try {
                while (queue.isNotEmpty()) {
                    val queued = queue.removeFirst()
                    val form = mutableState.value.data ?: continue
                    when (val result = queued.request(form)) {
                        is ApiResult.Success -> {
                            mutableState.value = LoadState(result.value, loaded = true)
                            mutableMutation.value = mutableMutation.value.copy(error = null, message = queued.message)
                            queued.onSuccess?.invoke()
                        }
                        is ApiResult.Failure -> reportFailure(result.error)
                    }
                }
            } finally {
                writing = false
                mutableMutation.value = mutableMutation.value.copy(busy = queue.isNotEmpty())
                if (queue.isEmpty() && refreshRequested) { refreshRequested = false; requestRefresh() }
            }
        }
    }

    private suspend fun reportFailure(error: ApiError) {
        val message = if (error.code == ErrorCode.CONFLICT) "已更新，请重新修改。当前输入已保留。" else error.message
        mutableMutation.value = mutableMutation.value.copy(error = error.copy(message = message))
        if (error.code == ErrorCode.CONFLICT) refreshRequested = true
    }

    private suspend fun loadForm(): ApiResult<AiSettingsUi> {
        val ai = when (val result = attempt { api.aiSettings.getAiView() }) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> return result
        }
        val recording = when (val result = attempt { api.aiSettings.getRecordingView() }) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> return result
        }
        return ApiResult.Success(AiSettingsUi(ai, recording))
    }

    private suspend fun <T> attempt(request: suspend () -> ApiResult<T>): ApiResult<T> = try {
        request()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "ai.unavailable", retryable = true))
    }
}

/** Recording settings edited outside the AI page; every change is an immediate single-field patch. */
class ScopedRecordingSettingsViewModel(
    private val api: CallringUiApi,
    testScope: CoroutineScope? = null,
) : UiViewModel(testScope) {
    private var base: RecordingSettingsView? = null
    private val loader = ObservedQuery(scope, api.changes, ChangeSubscription(setOf(DataDomain.RECORDING_SETTINGS, DataDomain.RUNTIME), mapOf(DataDomain.RUNTIME to setOf("recordingFolder"))),
        load = { api.aiSettings.getRecordingView() }, onSuccess = { base = it })
    val state = loader.state
    private val writer = MutationRunner(scope)
    val mutation = writer.state
    init { loader.start(scope) }
    fun refresh() = loader.request(force = true)

    fun enabled(value: Boolean) = patch(RecordingSettingsPatch(recordingEnabled = PatchField.Set(value)))

    fun captureMode(mode: CaptureMode) = patch(RecordingSettingsPatch(captureMode = PatchField.Set(mode)))

    private fun patch(patch: RecordingSettingsPatch) {
        val original = base ?: return
        writer.run({ api.aiSettings.patchRecording(original, patch) }, "录音设置已更新") {
            base = it
        }
    }

    fun folder(document: DocumentRef) = writer.run({ api.recordings.setSystemFolder(document) }, "系统录音目录已授权")
}
