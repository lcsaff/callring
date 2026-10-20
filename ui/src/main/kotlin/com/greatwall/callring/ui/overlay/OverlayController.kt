package com.greatwall.callring.ui.overlay

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.feature.callers.CallerEditorViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class OverlayModels(val presentation: OverlayViewModel, val editor: CallerEditorViewModel? = null)

/** Owns API observers for one displayed session; Android windows stay in the platform adapter. */
class OverlayController(
    private val api: CallringUiApi,
    val sessions: OverlaySessions,
    private val scope: CoroutineScope
) {
    private val mutableModels = MutableStateFlow<OverlayModels?>(null)
    val models = mutableModels.asStateFlow()
    private val mutableSavedRevision = MutableStateFlow(0L)
    val savedRevision = mutableSavedRevision.asStateFlow()
    private var sessionScope: CoroutineScope? = null
    private var editorScope: CoroutineScope? = null
    private val sessionObserver = scope.launch {
        sessions.state.collect { session ->
            if (session?.request?.sessionId != mutableModels.value?.presentation?.request?.sessionId) {
                clearSession()
                if (session != null) attachSession(session.request)
            }
        }
    }

    fun show(request: IncomingCallUiRequest) = sessions.accept(request)
    fun dismiss() = sessions.dismiss()
    fun collapse() = sessions.toggleCollapsed()
    fun refreshRuntime() { mutableModels.value?.presentation?.refreshRuntime() }
    fun refreshOverview() { mutableModels.value?.presentation?.refreshOverview() }
    fun whitelist(enabled: Boolean) {
        if (sessions.state.value?.testPreview != true) mutableModels.value?.presentation?.whitelist(enabled)
    }

    fun beginEditing() {
        if (sessions.state.value?.testPreview == true) return
        val current = mutableModels.value ?: return
        if (current.editor != null) return
        val parent = sessionScope ?: return
        val editLifetime = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        editorScope = editLifetime
        val editor = CallerEditorViewModel(current.presentation.request.number, api.callers, api.changes, SavedStateHandle(), editLifetime)
        mutableModels.value = current.copy(editor = editor)
        sessions.beginEditing()
        editLifetime.launch {
            editor.mutation.collect { mutation ->
                if (mutation.completed > 0 && !mutation.busy && mutableModels.value?.editor === editor) {
                    mutableSavedRevision.value++
                    finishEditing(editor)
                }
            }
        }
    }

    fun changeDraft(draft: CallerDraft) { mutableModels.value?.editor?.edit { draft } }
    fun save() { mutableModels.value?.editor?.save() }
    fun retryEditor() { mutableModels.value?.editor?.refresh() }
    fun cancelEditing() {
        val editor = mutableModels.value?.editor ?: return
        if (!editor.mutation.value.busy) finishEditing(editor)
    }

    fun close() {
        sessionObserver.cancel()
        clearSession()
    }

    private fun finishEditing(editor: CallerEditorViewModel) {
        sessions.finishEditing()
        if (mutableModels.value?.editor === editor) {
            editorScope?.cancel()
            editorScope = null
            mutableModels.value = mutableModels.value?.copy(editor = null)
        }
    }

    private fun attachSession(request: IncomingCallUiRequest) {
        val lifetime = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        sessionScope = lifetime
        val model = OverlayViewModel(request, api, lifetime)
        mutableModels.value = OverlayModels(model)
        lifetime.launch {
            model.settings.collect { snapshot ->
                if (snapshot.data?.overlayEnabled == false && current(request) && sessions.state.value?.preview != true) sessions.dismiss()
            }
        }
        lifetime.launch {
            model.runtime.collect { snapshot ->
                if (!current(request)) return@collect
                val status = snapshot.data
                if (snapshot.error != null) sessions.phone(PhoneActivity.UNKNOWN)
                else if (status != null) {
                    val access = status.capabilities[Capability.PHONE_STATE]
                    if ((access == AccessState.DENIED || access == AccessState.UNAVAILABLE) && sessions.state.value?.preview != true) sessions.dismiss()
                    else sessions.phone(status.phoneActivity)
                }
            }
        }
        lifetime.launch {
            var polls = 0
            while (isActive) {
                delay(1_000)
                model.refreshRuntime()
                if (++polls % 10 == 0 && model.settings.value.data == null) model.refreshSettings()
            }
        }
    }

    private fun current(request: IncomingCallUiRequest) = sessions.state.value?.request?.sessionId == request.sessionId

    private fun clearSession() {
        editorScope?.cancel(); editorScope = null
        sessionScope?.cancel(); sessionScope = null
        mutableModels.value = null
    }
}
