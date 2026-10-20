package com.greatwall.callring.ui.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.ui.testing.*
import com.greatwall.callring.ui.feature.ai.CopyConnectionDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w600dp-h1000dp")
class AiCompatibilityComposeTest {
    @get:Rule val compose=createComposeRule()
    @Test fun transcriptionToAnalysisPreviewDisclosesAutoAndActualResolvedProfile() {
        val connection=AiConnectionView(AiProtocol.OPENAI,"https://dashscope.aliyuncs.com/compatible-mode/v1","qwen3.8-flash",true,copyToAnalysisProfile=ProviderProfile.QWEN_FAST)
        val base=AiSettingsView(connection.copy(model="other"),connection,ConversationBudget(),9,ConversationCompatibility.STANDARD)
        var copied=false;var finished=false
        val api=object:AiSettingsApi by UnavailableAiSettingsApi {
            override suspend fun getAiView()=ApiResult.Success(base)
            override suspend fun copyConnection(from:AiSlot,to:AiSlot,expectedRevision:Long):ApiResult<AiSettingsView>{assertEquals(AiSlot.TRANSCRIPTION,from);assertEquals(AiSlot.ANALYSIS,to);assertEquals(9L,expectedRevision);copied=true;return ApiResult.Success(base.copy(analysis=connection.copy(resolvedProfile=ProviderProfile.QWEN_FAST),compatibility=ConversationCompatibility.AUTO))}
        }
        compose.setContent{CopyConnectionDialog(api,AiSlot.TRANSCRIPTION,AiSlot.ANALYSIS){finished=it}}
        compose.onNodeWithText("分析兼容方式将重置为 AUTO；目标解析方式：QWEN_FAST。保存后会停止使用旧连接的沟通。").assertExists()
        compose.onNodeWithText("复制并保存").performClick()
        compose.runOnIdle{assertTrue(copied);assertTrue(finished)}
    }
}
