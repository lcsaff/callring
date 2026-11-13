package com.greatwall.callring.ui.testing

import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** Observable calls and delayed model reply, confined to isolated tests. */
class HomePagerFixture : UiFakeApi() {
    fun publish(domain: com.greatwall.callring.contracts.change.DataDomain) { feed.publish(setOf(domain)) }
    var homeReads = 0
    var summaryReads = 0
    var aiReads = 0
    val searches = mutableListOf<CallerSearch>()
    var sends = 0
    var stops = 0
    val reply = CompletableDeferred<Unit>()
    val conversationState = MutableStateFlow(ConversationState("pager-test"))
    private val target = AiConnectionView(AiProtocol.OPENAI, "https://example.test", "test", true)
    override val aiSettings = object : AiSettingsApi by FixtureAiSettingsApi {
        override suspend fun getAiView(): ApiResult<AiSettingsView> {
            aiReads++
            return ApiResult.Success(AiSettingsView(target, ConversationBudget(), 0))
        }
    }
    override val conversation = object : ConversationApi by FixtureConversationApi {
        override val state = conversationState
        override suspend fun sendTo(expectedSessionId: String, text: String, disclosedTarget: AiConnectionView): ApiResult<Unit> {
            sends++
            state.value = state.value.copy(phase = ConversationPhase.REQUESTING, messages = listOf(ConversationMessage("user", text)))
            reply.await()
            state.value = state.value.copy(phase = ConversationPhase.COMPLETED,
                messages = state.value.messages + ConversationMessage("assistant", "切页期间完成的回复"))
            return ApiResult.Success(Unit)
        }
        override suspend fun stop(expectedSessionId: String): ApiResult<Unit> { stops++; return ApiResult.Success(Unit) }
    }
    override suspend fun load() = super.load().also { homeReads++ }
    override suspend fun getSummary() = super.getSummary().also { summaryReads++ }
    override suspend fun search(query: CallerSearch): ApiResult<Page<CallerProfile>> {
        searches += query
        val from = query.page.pageIndex * query.page.pageSize
        return ApiResult.Success(Page((from until from + query.page.pageSize).map {
            profile.copy(number = (10000 + it).toString(), name = "档案$it")
        }, query.page.pageIndex, query.page.pageSize, 150, SnapshotMeta(0, 0)))
    }
}
