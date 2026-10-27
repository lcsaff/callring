package com.greatwall.callring.ui.app

import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.ui.feature.conversation.ConversationViewModel
import com.greatwall.callring.ui.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationDraftTest {
    private val target = AiConnectionView(AiProtocol.OPENAI, "https://example.test", "test", true)

    @Test fun unsentDraftSurvivesPageAndHostModelRecreation() = runTest {
        val api = object : UiFakeApi() {
            override val conversation = object : ConversationApi by FixtureConversationApi {
                override suspend fun newSession(expectedSessionId: String) = ApiResult.Success(Unit)
            }
        }
        val first = ConversationViewModel(api, backgroundScope)
        try {
            first.input.value = "尚未发送\n继续编辑"
            first.newSession()
            runCurrent()
            val recreated = ConversationViewModel(api, backgroundScope)
            assertEquals("尚未发送\n继续编辑", recreated.input.value)
        } finally { first.input.value = "" }
    }

    @Test fun continuingTaskDoesNotDiscardUnsentDraft() = runTest {
        val model = ConversationViewModel(object : UiFakeApi() {
            override val conversation = object : ConversationApi by FixtureConversationApi {
                override suspend fun continueTask(expectedSessionId: String, taskId: String, disclosedTarget: AiConnectionView) = ApiResult.Success(Unit)
            }
        }, backgroundScope)
        try {
            model.input.value = "下一条消息"
            model.continueTask("task", target)
            runCurrent()
            assertEquals("下一条消息", model.input.value)
        } finally { model.input.value = "" }
    }

    @Test fun successfulSendDoesNotDiscardTextEditedWhileRequestWasPending() = runTest {
        val result = CompletableDeferred<ApiResult<Unit>>()
        var submitted = ""
        val conversation = object : ConversationApi by FixtureConversationApi {
            override suspend fun sendTo(expectedSessionId: String, text: String, disclosedTarget: AiConnectionView): ApiResult<Unit> {
                submitted = text
                return result.await()
            }
        }
        val model = ConversationViewModel(object : UiFakeApi() {
            override val conversation = conversation
        }, backgroundScope)
        try {
            model.input.value = "发送这条"
            model.send(target)
            runCurrent()
            model.input.value = "还没发送的新内容"
            result.complete(ApiResult.Success(Unit))
            runCurrent()
            assertEquals("发送这条", submitted)
            assertEquals("还没发送的新内容", model.input.value)
        } finally { model.input.value = "" }
    }

    @Test fun acceptedSendClearsDraftButRejectedSendKeepsIt() = runTest {
        var response: ApiResult<Unit> = ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "failed"))
        val conversation = object : ConversationApi by FixtureConversationApi {
            override suspend fun sendTo(expectedSessionId: String, text: String, disclosedTarget: AiConnectionView) = response
        }
        val model = ConversationViewModel(object : UiFakeApi() {
            override val conversation = conversation
        }, backgroundScope)
        try {
            model.input.value = "保留草稿"
            model.send(target)
            runCurrent()
            assertEquals("保留草稿", model.input.value)
            response = ApiResult.Success(Unit)
            model.send(target)
            runCurrent()
            assertEquals("", model.input.value)
        } finally { model.input.value = "" }
    }
}
