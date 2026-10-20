package com.greatwall.callring.ui.app

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.*
import com.greatwall.callring.ui.feature.ai.*
import com.greatwall.callring.ui.feature.conversation.*

@Composable internal fun AiSettingsRoute(api: CallringUiApi, signal: Long, onBack: () -> Unit) {
    val model: AiSettingsViewModel = uiViewModel { AiSettingsViewModel(api) }
    val state by model.state.collectAsStateWithLifecycle(); val mutation by model.mutation.collectAsStateWithLifecycle()
    var credential by remember { mutableStateOf<CredentialDraft?>(null) }
    var copy by remember { mutableStateOf<Pair<AiSlot,AiSlot>?>(null) }
    AiSettingsScreen(state, mutation, onBack, model::refresh,
        model::editAnalysis, model::analysisProtocol, model::editTranscription,
        model::thinkingMode, model::budget, model::automaticAnalysis,
        { credential = model.credentialInput(it) }, { from,to -> copy = from to to }, model::test)
    credential?.let { input -> CredentialDialog(api.aiSettings,input.slot,input) { saved -> credential = null; if(saved) model.credentialSaved() else model.credentialCancelled() } }
    copy?.let { request->CopyConnectionDialog(api.aiSettings,request.first,request.second) { saved->copy = null; if(saved)model.credentialSaved()else model.credentialCancelled() } }
}
@Composable internal fun ConversationRoute(api: CallringUiApi, onOpenSettings: () -> Unit = {}) {
    val model: ConversationViewModel = uiViewModel { ConversationViewModel(api) }
    val state by model.state.collectAsStateWithLifecycle(); val input by model.input.collectAsStateWithLifecycle()
    val error by model.localError.collectAsStateWithLifecycle(); val displayed by model.destination.collectAsStateWithLifecycle()
    val connected by model.connected.collectAsStateWithLifecycle()
    val renderedTarget = displayed
    val target = renderedTarget?.let { "${it.protocol} · ${it.url} · ${it.model} · ${it.resolvedProfile}" }.orEmpty()
    // Capture the same view used by this composition, never a newer unrendered VM value.
    ConversationScreen(state,input,target,error,{ model.input.value = it },{ model.send(renderedTarget) },model::stop,model::newSession,{task->model.continueTask(task,renderedTarget)},onOpenSettings,connected)
}
