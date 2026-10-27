package com.greatwall.callring.ui.feature.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.feature.ai.*
import com.greatwall.callring.ui.feature.recording.RecordingSettingsScreen
import com.greatwall.callring.ui.feature.settings.components.SettingsLayout
import com.greatwall.callring.ui.state.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h1200dp")
class SettingsFeedbackTest {
    @get:Rule val compose = createComposeRule()
    private val recording = RecordingSettingsView(false, false, CaptureMode.SYSTEM_ONLY, false, 1)
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test fun settingsSaveHasNoSnackbarAndFailuresRemainVisible() {
        val mutation = mutableStateOf(MutationState())
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) {
            SettingsLayout("设置", LoadState(Unit, loaded = true), mutation.value, {}, {}) {}
        } }
        compose.runOnIdle { mutation.value = MutationState(completed = 1, message = "我的职位已更新") }
        compose.onNodeWithText("我的职位已更新").assertDoesNotExist()
        compose.onNodeWithTag("feedback-snackbar").assertDoesNotExist()
        compose.runOnIdle { mutation.value = MutationState(error = ApiError(ErrorCode.INTERNAL, "保存失败")) }
        compose.onNodeWithText("保存失败").assertExists()
    }

    @Test fun recordingSaveAndRefreshHaveNoSuccessTextOrProgress() {
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) {
            RecordingSettingsScreen(LoadState(recording, loading = true, loaded = true),
                MutationState(completed = 1, message = "录音设置已更新"), {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("录音设置已更新").assertDoesNotExist()
        compose.onAllNodes(progress).assertCountEquals(0)
        compose.onNodeWithText("启用录音归档").assertExists()
    }

    @Test fun aiRefreshKeepsSettingsVisibleWithoutProgress() {
        val connection = AiConnectionView(AiProtocol.OPENAI, "https://example.com", "model", false)
        val ai = AiSettingsView(connection, connection, ConversationBudget(), 1)
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) {
            AiSettingsScreen(LoadState(AiSettingsUi(ai, recording), loading = true, loaded = true),
                MutationState(busy = true), {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {})
        } }
        compose.onAllNodes(progress).assertCountEquals(0)
        compose.onNodeWithText("内容分析与沟通").assertExists()
    }
}
