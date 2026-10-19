package com.greatwall.callring.ui.app

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.feature.ai.AiSettingsViewModel
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiCompatibilityUiTest {
    @Test fun saveCarriesCompatibilityWithoutResettingBudgetOrConnections()=runTest {
        val connection=AiConnectionView(AiProtocol.OPENAI,"https://example.com","qwen3.8-flash",true)
        val base=AiSettingsView(connection,connection,ConversationBudget(65536,8192),1)
        var submitted:AiSettingsPatch?=null
        val api=object:UiFakeApi(){override val aiSettings=object:AiSettingsApi by UnavailableAiSettingsApi {
            override suspend fun getAiView()=ApiResult.Success(base)
            override suspend fun patchAi(base:AiSettingsView,patch:AiSettingsPatch,analysisSecret:LocalSecretChange,transcriptionSecret:LocalSecretChange):ApiResult<AiSettingsView>{submitted=patch;return ApiResult.Success(base.copy(compatibility=ConversationCompatibility.QWEN_FAST))}
        }}
        val model=AiSettingsViewModel(api,backgroundScope);advanceUntilIdle();model.refresh();runCurrent()
        model.edit(base.copy(compatibility=ConversationCompatibility.QWEN_FAST));model.save();runCurrent()
        assertEquals(PatchField.Set(ConversationCompatibility.QWEN_FAST),submitted?.compatibility)
        assertEquals(PatchField.Keep,submitted?.budget)
        assertEquals(ConnectionPatch(),submitted?.analysis)
    }
}
