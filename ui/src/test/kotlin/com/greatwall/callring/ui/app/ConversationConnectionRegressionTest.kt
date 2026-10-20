package com.greatwall.callring.ui.app

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.ui.testing.*
import com.greatwall.callring.ui.feature.conversation.ConversationViewModel
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationConnectionRegressionTest {
    @Test fun passiveRefreshNeverCallsPaidProbeAndQueuedSendIsNotProofOfConnection() = runTest {
        val connection = AiConnectionView(AiProtocol.OPENAI, "https://example.test/v1", "test", true)
        var view = AiSettingsView(connection, connection, ConversationBudget(), 0)
        var probes = 0
        val feed = MutableStateFlow(DataChange(0, DataDomain.entries.toSet(), null, null))
        val conversationState = MutableStateFlow(ConversationState("session"))
        val settings = object : AiSettingsApi by FixtureAiSettingsApi {
            override suspend fun getAiView() = ApiResult.Success(view)
            override suspend fun testSlot(slot: AiSlot): ApiResult<Unit> { probes++; return ApiResult.Success(Unit) }
        }
        val conversation = object : ConversationApi by FixtureConversationApi {
            override val state = conversationState
            override suspend fun sendTo(expectedSessionId: String, text: String, disclosedTarget: AiConnectionView): ApiResult<Unit> {
                conversationState.value = ConversationState("session", ConversationPhase.REQUESTING)
                return ApiResult.Success(Unit)
            }
        }
        val api = object : UiFakeApi() {
            override val aiSettings = settings
            override val conversation = conversation
            override val changes = object : ChangeFeed { override fun observe() = feed }
        }
        val model = ConversationViewModel(api, backgroundScope)
        runCurrent()
        feed.value = DataChange(1, DataDomain.entries.toSet(), null, null)
        runCurrent()
        assertEquals(0, probes)
        assertFalse(model.connected.value)
        model.input.value = "hello"
        model.send(connection)
        runCurrent()
        assertFalse(model.connected.value)
        conversationState.value = conversationState.value.copy(exchanges = listOf(ExchangeMetrics(10, 10, 200, connection.resolvedProfile, 1)))
        runCurrent()
        assertTrue(model.connected.value)
        view = view.copy(revision = 1)
        feed.value = DataChange(2, DataDomain.entries.toSet(), null, null)
        runCurrent()
        assertFalse(model.connected.value)
        assertEquals(0, probes)
    }
}
