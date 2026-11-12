package com.greatwall.callring.ui.app

import androidx.lifecycle.SavedStateHandle
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

    @Test fun independentHostsDoNotShareUnsentDrafts() = runTest {
        val first = ConversationViewModel(UiFakeApi(), backgroundScope)
        val second = ConversationViewModel(UiFakeApi(), backgroundScope)
        try {
            first.input.value = "第一窗口的草稿"
            assertEquals("", second.input.value)
            second.input.value = "第二窗口的草稿"
            assertEquals("第一窗口的草稿", first.input.value)
        } finally { first.input.value = ""; second.input.value = "" }
    }

    @Test fun unsentDraftSurvivesPageAndHostModelRecreation() = runTest {
        val api = object : UiFakeApi() {
            override val conversation = object : ConversationApi by FixtureConversationApi {
                override suspend fun newSession(expectedSessionId: String) = ApiResult.Success(Unit)
            }
        }
        val handle = SavedStateHandle()
        val first = ConversationViewModel(api, backgroundScope, savedState = handle)
        try {
            first.input.value = "尚未发送\n继续编辑"
            first.newSession()
            runCurrent()
            val recreated = ConversationViewModel(api, backgroundScope, savedState = handle)
            assertEquals("尚未发送\n继续编辑", recreated.input.value)
        } finally { first.input.value = "" }
    }

    @Test fun restoredHandleRestoresOnlySavedDraftWithoutReplayingConversationActions() = runTest {
        var actions = 0
        val api = object : UiFakeApi() {
            override val conversation = object : ConversationApi by FixtureConversationApi {
                override suspend fun sendTo(expectedSessionId: String, text: String, disclosedTarget: AiConnectionView): ApiResult<Unit> {
                    actions++
                    return ApiResult.Success(Unit)
                }
                override suspend fun newSession(expectedSessionId: String): ApiResult<Unit> {
                    actions++
                    return ApiResult.Success(Unit)
                }
            }
        }
        val handle = SavedStateHandle()
        val first = ConversationViewModel(api, backgroundScope, savedState = handle)
        first.input.value = "系统保存的草稿\n第二行"
        val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        first.input.value = "保存状态之后的新编辑"
        val restored = ConversationViewModel(api, backgroundScope, savedState = restoredHandle)
        runCurrent()
        assertEquals("系统保存的草稿\n第二行", restored.input.value)
        assertEquals("保存状态之后的新编辑", first.input.value)
        assertEquals(0, actions)
    }

    @Test fun successfulSendSavesClearedDraftForTheNextHost() = runTest {
        val api = object : UiFakeApi() {
            override val conversation = object : ConversationApi by FixtureConversationApi {
                override suspend fun sendTo(expectedSessionId: String, text: String, disclosedTarget: AiConnectionView) = ApiResult.Success(Unit)
            }
        }
        val handle = SavedStateHandle()
        val model = ConversationViewModel(api, backgroundScope, savedState = handle)
        model.input.value = "已发送内容"
        model.send(target)
        runCurrent()
        val restored = ConversationViewModel(api, backgroundScope,
            savedState = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        assertEquals("", restored.input.value)
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
