package com.greatwall.callring.ui.app

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.ui.testing.*
import com.greatwall.callring.ui.feature.ai.AiSettingsUi
import com.greatwall.callring.ui.feature.ai.AiSettingsViewModel
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiCompatibilityUiTest {
    private val analysis = AiConnectionView(AiProtocol.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "qwen3.8-flash", true)
    private val transcription = AiConnectionView(AiProtocol.OPENAI, "https://api.example.com/v1", "whisper-1", true)
    private val ai = AiSettingsView(analysis, transcription, ConversationBudget(65536, 8192), 1)
    private val recording = RecordingSettingsView(false, false, CaptureMode.SYSTEM_ONLY, false, 1)

    private fun api(
        onAiView: () -> AiSettingsView = { ai },
        onRecordingView: () -> RecordingSettingsView = { recording },
        onPatchAi: (AiSettingsPatch) -> ApiResult<AiSettingsView> = { ApiResult.Success(ai) },
        onPatchCombined: (RecordingSettingsPatch, AiSettingsPatch) -> ApiResult<Unit> = { _, _ -> ApiResult.Success(Unit) },
    ) = object : UiFakeApi() {
        override val aiSettings = object : AiSettingsApi by UnavailableAiSettingsApi {
            override suspend fun getAiView() = ApiResult.Success(onAiView())
            override suspend fun getRecordingView() = ApiResult.Success(onRecordingView())
            override suspend fun patchAi(base: AiSettingsView, patch: AiSettingsPatch,
                analysisSecret: LocalSecretChange, transcriptionSecret: LocalSecretChange) = onPatchAi(patch)
            override suspend fun patchCombined(recordingBase: RecordingSettingsView, recording: RecordingSettingsPatch,
                aiBase: AiSettingsView, ai: AiSettingsPatch, analysisSecret: LocalSecretChange,
                transcriptionSecret: LocalSecretChange) = onPatchCombined(recording, ai)
        }
    }

    @Test fun thinkingModeToggleSendsOnePatchWithOnlyThinkingModeSet() = runTest {
        var submitted: AiSettingsPatch? = null
        val model = AiSettingsViewModel(api(onPatchAi = { patch ->
            submitted = patch
            ApiResult.Success(ai.copy(analysis = analysis.copy(thinkingMode = ThinkingMode.ENABLED), revision = 2))
        }), backgroundScope)
        model.refresh(); runCurrent()
        model.thinkingMode(ThinkingMode.ENABLED); runCurrent()
        assertEquals(AiSettingsPatch(analysis = ConnectionPatch(thinkingMode = PatchField.Set(ThinkingMode.ENABLED))),
            submitted)
        assertEquals(ThinkingMode.ENABLED, model.state.value.data?.ai?.analysis?.thinkingMode)
        assertNull(model.mutation.value.error)
    }

    @Test fun automaticAnalysisUsesOnePatchCombinedWithRecordingOnly() = runTest {
        var recordingPatch: RecordingSettingsPatch? = null
        var aiPatch: AiSettingsPatch? = null
        var currentRecording = recording
        val model = AiSettingsViewModel(api(
            onRecordingView = { currentRecording },
            onPatchCombined = { recordingSide, aiSide ->
                recordingPatch = recordingSide
                aiPatch = aiSide
                currentRecording = currentRecording.copy(automaticAnalysis = true, revision = 2)
                ApiResult.Success(Unit)
            },
        ), backgroundScope)
        model.refresh(); runCurrent()
        model.automaticAnalysis(true); runCurrent()
        assertEquals(RecordingSettingsPatch(automaticAnalysis = PatchField.Set(true)), recordingPatch)
        assertEquals(AiSettingsPatch(), aiPatch)
        assertEquals(true, model.state.value.data?.recording?.automaticAnalysis)
    }

    @Test fun budgetIsCoalescedAndOnlyTheBudgetFieldIsPatched() = runTest {
        var submitted: AiSettingsPatch? = null
        val model = AiSettingsViewModel(api(onPatchAi = { patch ->
            submitted = patch
            ApiResult.Success(ai.copy(budget = ConversationBudget(70000, 8192), revision = 2))
        }), backgroundScope)
        model.refresh(); runCurrent()
        model.budget(ConversationBudget(65536, 8192))
        advanceTimeBy(200)
        model.budget(ConversationBudget(70000, 8192))
        advanceTimeBy(499); runCurrent()
        assertNull(submitted)
        advanceTimeBy(1); runCurrent()
        assertEquals(AiSettingsPatch(budget = PatchField.Set(ConversationBudget(70000, 8192))), submitted)
        assertEquals(ConversationBudget(70000, 8192), model.state.value.data?.ai?.budget)
    }

    @Test fun analysisUrlEditOnlyPatchesTheAnalysisConnectionAfterDebounce() = runTest {
        var submitted: AiSettingsPatch? = null
        val model = AiSettingsViewModel(api(onPatchAi = { patch ->
            submitted = patch
            ApiResult.Success(ai.copy(revision = 2))
        }), backgroundScope)
        model.refresh(); runCurrent()
        model.editAnalysis(analysis.copy(url = "https://custom.example.com/v1"))
        advanceTimeBy(499); runCurrent()
        assertNull(submitted)
        advanceTimeBy(1); runCurrent()
        assertEquals(AiSettingsPatch(analysis = ConnectionPatch(url = PatchField.Set("https://custom.example.com/v1"))),
            submitted)
    }
}
