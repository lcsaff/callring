package com.greatwall.callring.ui.feature.setup

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.ui.feature.home.HomeScreen
import com.greatwall.callring.ui.state.LoadState
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupScreenJvmTest {
    @get:Rule val compose = createComposeRule()
    private val status = RuntimeStatus(Capability.entries.associateWith { AccessState.DENIED }, Audibility.SILENT,
        PhoneActivity.UNKNOWN, PlaybackStatus(PlaybackMode.NONE, PlaybackState.IDLE), false, 0, CoreReadiness.MISSING)
    private fun data(recording: Boolean = false, overlay: Boolean = false) = SetupUiData(status, overlay,
        RecordingSettingsView(recording, false, CaptureMode.MICROPHONE_EXPERIMENTAL, false, 0))

    @Test fun checklistNeverRequestsOnOpenRefreshOrRecompositionAndCanBeSkipped() {
        var requests = 0
        var skipped = 0
        val state = mutableStateOf(LoadState(data(), loaded = true))
        compose.setContent { SetupScreen(state.value, { skipped++ }, {}, { requests++ }, {}, {}, {}) }
        compose.runOnIdle { assertEquals(0, requests); state.value = state.value.copy(loading = true) }
        compose.onNodeWithTag("setup-action-PHONE_STATE").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, requests); state.value = state.value.copy(loading = false) }
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("暂时跳过").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, skipped); assertEquals(1, requests) }
    }

    @Test fun disabledFeaturesDoNotAskForOverlayMicrophoneOrFolder() {
        compose.setContent { SetupScreen(LoadState(data(), loaded = true), {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithTag("setup-action-OVERLAY").assertDoesNotExist()
        compose.onNodeWithTag("setup-action-MICROPHONE").assertDoesNotExist()
        compose.onNodeWithText("录音目录").assertDoesNotExist()
        compose.onNodeWithText("未授予联系人权限时，已保存联系人的来电识别覆盖受限。").assertExists()
    }

    @Test fun enabledFeaturesExposeOnlyTheirExistingUserActionsAndSoundStaysObservational() {
        var capability: Capability? = null
        var recordingOpens = 0
        compose.setContent { SetupScreen(LoadState(data(recording = true, overlay = true), loaded = true), {}, {},
            { capability = it }, {}, { recordingOpens++ }, {}) }
        compose.onNodeWithTag("setup-action-OVERLAY").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Capability.OVERLAY, capability) }
        compose.onNodeWithTag("setup-action-MICROPHONE").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Capability.MICROPHONE, capability) }
        compose.onNodeWithText("选择录音目录").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, recordingOpens) }
        compose.onNodeWithText("当前声音条件：系统静音").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("仅观察系统声音条件，不自动修改静音、振动、音量或系统勿扰。").assertExists()
    }

    @Test fun healthyHomeHasNoPersistentReadinessCard() {
        val home = runBlocking { (UiFakeApi().load() as ApiResult.Success).value }
        compose.setContent { HomeScreen(LoadState(home.copy(runtime = home.runtime.copy(coreReadiness = CoreReadiness.READY)), loaded = true), {}, {}) }
        compose.onNodeWithTag("home-readiness").assertDoesNotExist()
        compose.onNodeWithText("检查配置").assertDoesNotExist()
    }

    @Test fun refreshRetainsContentWithoutProgressAndFreshGrantsRemoveAction() {
        val state = mutableStateOf(LoadState(data(), loaded = true))
        compose.setContent { SetupScreen(state.value, {}, {}, {}, {}, {}, {}) }
        compose.runOnIdle { state.value = state.value.copy(loading = true) }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(data = data().copy(status = status.copy(
            capabilities = status.capabilities + (Capability.PHONE_STATE to AccessState.GRANTED)))) }
        compose.onNodeWithTag("setup-action-PHONE_STATE").assertDoesNotExist()
    }
}
