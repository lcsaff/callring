package com.greatwall.callring

import com.greatwall.callring.application.api.*
import org.junit.Assert.*
import org.junit.Test

class RecordingServiceTest {
    @Test fun preservesCustomPrefixAndRejectsCredentialsInUrl() {
        val connection = AiConnection(AiProtocol.OPENAI, "https://example.com/proxy/v1/", "secret", "custom-model")
        assertEquals("https://example.com/proxy/v1/chat/completions", connection.endpoint("chat/completions"))
        assertFalse(connection.toString().contains("secret"))
        assertThrows(IllegalArgumentException::class.java) {
            connection.copy(url = "https://user:pass@example.com/v1").validate()
        }
    }
    @Test fun transcriptionCannotUseAnthropicProtocol() {
        val connection = AiConnection(AiProtocol.ANTHROPIC, "https://example.com/v1", "key", "model")
        assertThrows(IllegalArgumentException::class.java) { RecordingSettings(transcription = connection).validate() }
    }
    @Test fun emptyConfigurationCanBeSavedWhileDisabled() {
        RecordingSettings().validate()
        assertThrows(IllegalArgumentException::class.java) { RecordingSettings(automaticAnalysis = true).validate() }
    }
}
