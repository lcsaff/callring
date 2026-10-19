package com.greatwall.callring.ui.feature.ai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.state.MutationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h1200dp")
class AiSettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun connection(
        thinkingSupport: ThinkingSupport = ThinkingSupport.AVAILABLE,
        thinkingNotice: String = "",
        thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
    ) = AiConnectionView(AiProtocol.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "qwen3.8-flash", true, thinkingMode = thinkingMode, thinkingSupport = thinkingSupport,
        thinkingNotice = thinkingNotice)

    private fun defaultAi(support: ThinkingSupport = ThinkingSupport.AVAILABLE,
        notice: String = "", mode: ThinkingMode = ThinkingMode.DISABLED) =
        AiSettingsView(connection(support, notice, mode), connection(support, notice),
            ConversationBudget(), 1)

    private val recording = RecordingSettingsView(false, false, CaptureMode.SYSTEM_ONLY, false, 1)

    private fun show(
        ai: AiSettingsView = defaultAi(),
        onThinkingMode: (ThinkingMode) -> Unit = {},
        onAutomaticAnalysis: (Boolean) -> Unit = {},
        onBudget: (ConversationBudget) -> Unit = {},
    ) {
        compose.setContent { CallringTheme {
            AiSettingsScreen(LoadState(AiSettingsUi(ai, recording), loaded = true), MutationState(), {},
                {}, {}, {}, {}, onThinkingMode, onBudget, onAutomaticAnalysis, {}, { _, _ -> }, {})
        } }
    }

    @Test fun threeGroupsAppearInOrderWithoutCompatibilityOrSaveActions() {
        show()
        val first = compose.onNodeWithText("内容分析与沟通").fetchSemanticsNode().positionInRoot.y
        val second = compose.onNodeWithText("录音转写").fetchSemanticsNode().positionInRoot.y
        val third = compose.onNodeWithText("高级设置").fetchSemanticsNode().positionInRoot.y
        assertTrue(first < second)
        assertTrue(second < third)
        compose.onNodeWithText("高级兼容").assertDoesNotExist()
        compose.onNodeWithText("保存 AI 设置").assertDoesNotExist()
        compose.onNodeWithTag("settings-save").assertDoesNotExist()
        compose.onNodeWithText("思考模式").assertExists()
    }

    @Test fun unsupportedThinkingModeIsDisabledWithAReadableReason() {
        show(defaultAi(ThinkingSupport.UNSUPPORTED, "thinking.model_not_verified"))
        compose.onNodeWithContentDescription("思考模式").assertIsNotEnabled()
        compose.onNodeWithText("当前模型不支持切换思考模式。").assertExists()
    }

    @Test fun availableThinkingModeToggleSendsTheRequestedMode() {
        var modes = mutableListOf<ThinkingMode>()
        show(defaultAi(), onThinkingMode = { modes += it })
        compose.onNodeWithContentDescription("思考模式").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(ThinkingMode.ENABLED), modes) }
    }

    @Test fun automaticAnalysisSwitchLivesInTheRecordingGroupAndCommitsImmediately() {
        var changes = mutableListOf<Boolean>()
        show(onAutomaticAnalysis = { changes += it })
        compose.onNodeWithText("录音就绪后自动分析").performScrollTo().assertExists()
        compose.onNode(hasContentDescription("录音就绪后自动分析") and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState))
            .performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(true), changes) }
    }

    @Test fun advancedSettingsStartCollapsedAndInvalidBudgetNeverSubmits() {
        var budgets = mutableListOf<ConversationBudget>()
        show(onBudget = { budgets += it })
        compose.onNodeWithTag("ai-budget-context").assertDoesNotExist()
        compose.onNodeWithTag("ai-advanced-toggle").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("ai-budget-context").performScrollTo().assertExists()
        compose.onNodeWithTag("ai-budget-context").performTextClearance()
        compose.onNodeWithText("2 项配置需要修正").assertExists()
        compose.runOnIdle { assertTrue(budgets.isEmpty()) }
    }

    @Test fun validBudgetEditIsForwardedToThePageModel() {
        var budgets = mutableListOf<ConversationBudget>()
        show(onBudget = { budgets += it })
        compose.onNodeWithTag("ai-advanced-toggle").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("ai-budget-context").performScrollTo().performTextReplacement("65536")
        compose.runOnIdle { assertEquals(listOf(ConversationBudget(65536, 4096)), budgets) }
    }
}
