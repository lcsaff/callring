package com.greatwall.callring.ui.feature.conversation

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ConversationViewModel(private val api: CallringUiApi, testScope: CoroutineScope? = null) : UiViewModel(testScope) {
    val state = api.conversation.state
    val input = MutableStateFlow("")
    val localError = MutableStateFlow<ApiError?>(null)
    val destination = MutableStateFlow<AiConnectionView?>(null)
    /** Only an actual exchange proves connectivity. Passive observers never call a provider. */
    val connected = MutableStateFlow(false)
    private var settingsRevision: Long? = null
    private var requestTarget: Pair<AiConnectionView, Long?>? = null

    init {
        scope.launch {
            api.changes.observe().collect { change ->
                if (change.affects(setOf(DataDomain.AI_SETTINGS))) {
                    when (val result = api.aiSettings.getAiView()) {
                        is ApiResult.Success -> {
                            val view = result.value
                            if (view.analysis != destination.value || view.revision != settingsRevision) {
                                connected.value = false
                                requestTarget = null
                            }
                            destination.value = view.analysis
                            settingsRevision = view.revision
                        }
                        is ApiResult.Failure -> {
                            destination.value = null
                            connected.value = false
                            requestTarget = null
                            localError.value = result.error
                        }
                    }
                }
            }
        }
        scope.launch {
            state.collect { current ->
                val target = requestTarget
                val exchange = current.exchanges.lastOrNull()
                if (target != null && target == destination.value?.let { it to settingsRevision } && exchange != null) {
                    connected.value = exchange.failure == null && exchange.httpStatus in 200..299
                }
            }
        }
    }

    fun send(disclosed: AiConnectionView?) {
        val text = input.value
        request(disclosed) { api.conversation.sendTo(state.value.sessionId, text, it) }
    }

    fun continueTask(taskId: String, disclosed: AiConnectionView?) =
        request(disclosed) { api.conversation.continueTask(state.value.sessionId, taskId, it) }

    private fun request(disclosed: AiConnectionView?, operation: suspend (AiConnectionView) -> ApiResult<Unit>) {
        disclosed ?: return
        scope.launch {
            requestTarget = disclosed to settingsRevision
            when (val result = operation(disclosed)) {
                is ApiResult.Success -> { input.value = ""; localError.value = null }
                is ApiResult.Failure -> { requestTarget = null; connected.value = false; localError.value = result.error }
            }
        }
    }

    fun stop() { scope.launch { api.conversation.stop(state.value.sessionId) } }
    fun newSession() { scope.launch { api.conversation.newSession(state.value.sessionId) } }
}
