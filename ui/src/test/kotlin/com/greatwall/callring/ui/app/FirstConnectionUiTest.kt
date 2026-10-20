package com.greatwall.callring.ui.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.ui.testing.*
import com.greatwall.callring.ui.testing.UiFakeApi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h1600dp")
class FirstConnectionUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun analysisDraftAndLocalKeyReachTheSameSaveAndCancelWritesNothing() = exercise(AiSlot.ANALYSIS)
    @Test fun transcriptionDraftAndLocalKeyReachTheSameSaveAndCancelWritesNothing() = exercise(AiSlot.TRANSCRIPTION)
    private fun exercise(slot: AiSlot) {
        val index = if(slot == AiSlot.ANALYSIS) 0 else 1
        val empty = AiConnectionView(AiProtocol.OPENAI,"","",false)
        var view = AiSettingsView(empty,empty,ConversationBudget(),0)
        val submitted = mutableListOf<Triple<AiSettingsPatch,LocalSecretChange,LocalSecretChange>>()
        val settings = object : AiSettingsApi by FixtureAiSettingsApi {
            override suspend fun getAiView() = ApiResult.Success(view)
            override suspend fun getRecordingView() = ApiResult.Success(RecordingSettingsView(false,false,CaptureMode.SYSTEM_ONLY,false,0))
            override suspend fun patchCombined(recordingBase: RecordingSettingsView, recording: RecordingSettingsPatch, aiBase: AiSettingsView, ai: AiSettingsPatch, analysisSecret: LocalSecretChange, transcriptionSecret: LocalSecretChange): ApiResult<Unit> {
                submitted += Triple(ai,analysisSecret,transcriptionSecret)
                if(submitted.size == 1) return ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE,"test-storage-failed"))
                val saved = AiConnectionView(AiProtocol.OPENAI,"https://first.example/v1","first-model",true)
                view = if(slot == AiSlot.ANALYSIS) view.copy(analysis = saved,revision = 1) else view.copy(transcription = saved,revision = 1)
                return ApiResult.Success(Unit)
            }
        }
        val api = object : UiFakeApi() { override val aiSettings = settings }
        compose.setContent { AiSettingsRoute(api,0,{}) }
        compose.onAllNodesWithText("API 基础地址（含 /v1 或自定义前缀）")[index].performTextInput("https://first.example/v1")
        compose.onAllNodesWithText("模型名称")[index].performTextInput("first-model")
        compose.onAllNodesWithText("本地编辑密钥")[index].performClick()
        compose.onNodeWithText("替换密钥").performClick()
        compose.onNodeWithTag("credential-key").performTextInput("local-sentinel")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }
        compose.onAllNodesWithText("本地编辑密钥")[index].performClick()
        compose.onNodeWithText("替换密钥").performClick()
        compose.onNodeWithTag("credential-key").performTextInput("local-sentinel")
        compose.onNodeWithText("保存密钥").performClick()
        compose.onNodeWithTag("credential-key").assertExists()
        compose.onNodeWithText("保存密钥").performClick()
        compose.runOnIdle {
            assertEquals(2,submitted.size)
            val (patch,analysisKey,transcriptionKey) = submitted.last()
            val connection = if(slot == AiSlot.ANALYSIS) patch.analysis else patch.transcription
            val key = if(slot == AiSlot.ANALYSIS) analysisKey else transcriptionKey
            val other = if(slot == AiSlot.ANALYSIS) transcriptionKey else analysisKey
            assertEquals(PatchField.Set("https://first.example/v1"),connection.url)
            assertEquals(PatchField.Set("first-model"),connection.model)
            assertEquals("local-sentinel",(key as LocalSecretChange.Replace).value)
            assertEquals(LocalSecretChange.Keep,other)
        }
        compose.onAllNodesWithText("密钥已配置").assertCountEquals(1)
        compose.onNodeWithText("保存 AI 设置").assertDoesNotExist()
        compose.onNodeWithTag("settings-save").assertDoesNotExist()
    }
}
