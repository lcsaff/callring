package com.greatwall.callring.ui.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RenderedDestinationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun clickCarriesRenderedConnectionEvenWhenVmHasReceivedANewerOne() {
        val old = AiConnectionView(AiProtocol.OPENAI,"https://old.example","old",true)
        val config = MutableStateFlow(AiSettingsView(old,old,ConversationBudget(),0))
        val attempts = mutableListOf<AiConnectionView>()
        var reads = 0
        val api = object : UiFakeApi() {
            override val changes = object : ChangeFeed { override fun observe() = config.map { DataChange(it.revision,setOf(DataDomain.AI_SETTINGS),null,null) } }
            override val aiSettings = object : AiSettingsApi by UnavailableAiSettingsApi { override suspend fun getAiView(): ApiResult<AiSettingsView> { reads++; return ApiResult.Success(config.value) } }
            override val conversation = object : ConversationApi by UnavailableConversationApi {
                override val state = MutableStateFlow(ConversationState("s"))
                override suspend fun sendTo(expectedSessionId: String, text: String, disclosedTarget: AiConnectionView): ApiResult<Unit> {
                    attempts += disclosedTarget
                    return if(disclosedTarget == config.value.analysis) ApiResult.Success(Unit) else ApiResult.Failure(ApiError(ErrorCode.CONFLICT,"ai.connection_changed"))
                }
            }
        }
        compose.setContent { ConversationRoute(api) }
        compose.onNodeWithText("服务：OPENAI · https://old.example · old · STANDARD").assertExists()
        compose.onNodeWithText("说说你想了解或处理什么").performTextInput("next")
        val renderedClick = compose.onNodeWithContentDescription("发送消息").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { config.value = config.value.copy(analysis = old.copy(url = "https://new.example",model = "new"),revision = 1) }
        compose.waitUntil(3000) { reads >= 2 }
        compose.onNodeWithText("服务：OPENAI · https://old.example · old · STANDARD").assertExists()
        // Dispatch the action captured from the still-visible composition without the test driver's frame advancement.
        compose.runOnIdle { renderedClick() }
        compose.runOnIdle { assertEquals(old,attempts.single()) }
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("服务：OPENAI · https://new.example · new · STANDARD").assertExists()
        compose.onNodeWithContentDescription("发送消息").performClick()
        compose.runOnIdle { assertEquals(config.value.analysis,attempts.last()); assertEquals(2,attempts.size) }
    }
}
