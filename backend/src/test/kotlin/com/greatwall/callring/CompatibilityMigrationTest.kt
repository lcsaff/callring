package com.greatwall.callring

import com.greatwall.callring.application.api.AiConnection
import com.greatwall.callring.application.api.AiProtocol
import com.greatwall.callring.application.api.ConversationCompatibility
import com.greatwall.callring.application.api.ProviderProfile
import com.greatwall.callring.application.api.RecordingSettings
import com.greatwall.callring.application.api.normalized
import com.greatwall.callring.application.api.resolveConversationProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/** 旧“高级兼容”已废弃：QWEN_FAST 不再让任何保存/连接失败，读取时归一为 AUTO。 */
class CompatibilityMigrationTest {

    @Test fun legacyFastCompatibilityNeverBlocksValidation() {
        val settings = RecordingSettings(
            analysis = AiConnection(protocol = AiProtocol.OPENAI, url = "https://example.com/v1", apiKey = "k", model = "deepseek-chat"),
            conversationCompatibility = ConversationCompatibility.QWEN_FAST,
        )
        settings.validate()
        assertEquals(ProviderProfile.STANDARD, resolveConversationProfile(settings.analysis, settings.conversationCompatibility))
        assertEquals(ConversationCompatibility.AUTO, settings.conversationCompatibility.normalized())
    }

    @Test fun anthropicWithLegacyFastResolvesAnthropicInsteadOfFailing() {
        val anthropic = AiConnection(
            protocol = AiProtocol.ANTHROPIC,
            url = "https://api.deepseek.com/anthropic",
            apiKey = "k",
            model = "deepseek-flash",
        )
        assertEquals(ProviderProfile.ANTHROPIC, resolveConversationProfile(anthropic, ConversationCompatibility.QWEN_FAST))
    }

    @Test fun qwenFastOnOfficialHostStillResolvesFastProfile() {
        val connection = AiConnection(
            url = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            apiKey = "k",
            model = "qwen3.8-flash",
        )
        assertEquals(ProviderProfile.QWEN_FAST, resolveConversationProfile(connection, ConversationCompatibility.QWEN_FAST))
        assertEquals(ProviderProfile.QWEN_FAST, resolveConversationProfile(connection, ConversationCompatibility.AUTO))
    }
}
