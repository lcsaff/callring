package com.greatwall.callring

import com.greatwall.callring.application.conversation.operation.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.application.recording.model.AiConnection
import com.greatwall.callring.contracts.ai.AiProtocol
import com.greatwall.callring.contracts.ai.ConversationToolCall
import com.greatwall.callring.application.recording.model.RecordingSettings
import com.greatwall.callring.contracts.ai.ThinkingMode
import com.greatwall.callring.contracts.ai.aiBool
import com.greatwall.callring.contracts.ai.aiObject
import com.greatwall.callring.contracts.ai.aiString
import com.greatwall.callring.application.conversation.ToolRegistry
import com.greatwall.callring.application.ports.BackendFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 工具调用 API 只扩展不破坏：旧参数仍可用，新参数可选，操作集合不变。 */
class ThinkingToolApiCompatibilityTest {
    private val registry = ToolRegistry()
    private fun tool(id: OperationId) = registry.definitions.first { it.id == id }

    @Test fun operationSetAndNumbersStayUnchanged() {
        assertEquals(((1..72).map { "O%02d".format(it) } + (1..9).map { "X%02d".format(it) }).toSet(), registry.definitions.map { it.operationCode }.toSet())
        assertEquals(81, registry.definitions.map { it.name }.toSet().size)
    }

    @Test fun legacyCompatibilityArgumentStillAccepted() {
        // 工具 65 的 ai 字段是扁平入参（与旧 schema 一致），compatibility 仍被接受。
        val call = ConversationToolCall("c1", tool(OperationId.AI_PATCH_SETTINGS).name, aiObject("compatibility" to aiString("QWEN_FAST")))
        registry.validate(call)
    }

    @Test fun thinkingModeArgumentIsOptionalAndAccepted() {
        registry.validate(
            ConversationToolCall("c2", tool(OperationId.AI_PATCH_SETTINGS).name,
                aiObject("analysis" to aiObject("thinkingMode" to aiString("ENABLED")))),
        )
        registry.validate(
            ConversationToolCall("c3", tool(OperationId.RECORDINGS_SAVE_SETTINGS).name,
                aiObject(
                    "recording" to aiObject("automaticAnalysis" to aiBool(true)),
                    "ai" to aiObject("analysis" to aiObject("thinkingMode" to aiString("DISABLED"))),
                )),
        )
    }

    @Test fun unknownArgumentIsStillRejected() {
        val call = ConversationToolCall("c4", tool(OperationId.AI_PATCH_SETTINGS).name, aiObject("thinkingPower" to aiString("x")))
        assertTrue(runCatching { registry.validate(call) }.exceptionOrNull() is BackendFailure)
    }

    @Test fun transcriptionCannotEnableThinking() {
        val enabled = AiConnection(
            protocol = AiProtocol.OPENAI,
            url = "https://api.deepseek.com/v1",
            apiKey = "test-key",
            model = "deepseek-flash",
            thinkingMode = ThinkingMode.ENABLED,
        )
        val failure = runCatching { RecordingSettings(transcription = enabled).validate() }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("ai.transcription_thinking_disabled", failure?.message)
    }

    @Test fun legacySettingsDefaultToThinkingDisabled() {
        assertEquals(ThinkingMode.DISABLED, AiConnection().thinkingMode)
        assertEquals(ThinkingMode.DISABLED, RecordingSettings().analysis.thinkingMode)
        assertEquals(ThinkingMode.DISABLED, RecordingSettings().transcription.thinkingMode)
    }
}
