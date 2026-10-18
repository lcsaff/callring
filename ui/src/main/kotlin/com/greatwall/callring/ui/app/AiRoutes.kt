package com.greatwall.callring.ui.app

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.feature.ai.*
import com.greatwall.callring.ui.feature.conversation.*

@Composable internal fun AiSettingsRoute(api: CallringUiApi, signal: Long, onBack: () -> Unit) {
    val model: AiSettingsViewModel = uiViewModel { AiSettingsViewModel(api) }
    val state by model.state.collectAsStateWithLifecycle(); val draft by model.draft.collectAsStateWithLifecycle()
    val dirty by model.dirty.collectAsStateWithLifecycle(); val mutation by model.mutation.collectAsStateWithLifecycle()
    var credential by remember { mutableStateOf<AiSlot?>(null) }
    var copy by remember { mutableStateOf<Pair<AiSlot,AiSlot>?>(null) }
    ActiveRefresh(signal, refresh = model::refresh)
    AiSettingsScreen(state,draft,dirty,mutation,onBack,model::edit,model::save,{ credential = it },{ from,to -> copy = from to to },model::test)
    credential?.let { CredentialDialog(api.aiSettings,it) { credential = null; model.refresh() } }
    copy?.let { CopyConnectionDialog(api.aiSettings,it.first,it.second) { copy = null; model.refresh() } }
}
@Composable internal fun ConversationRoute(api: CallringUiApi) {
    val model: ConversationViewModel = uiViewModel { ConversationViewModel(api) }
    val state by model.state.collectAsStateWithLifecycle(); val input by model.input.collectAsStateWithLifecycle()
    val error by model.localError.collectAsStateWithLifecycle(); val target by model.target.collectAsStateWithLifecycle()
    ConversationScreen(state,input,target,error,{ model.input.value = it },model::send,model::stop,model::newSession)
}
