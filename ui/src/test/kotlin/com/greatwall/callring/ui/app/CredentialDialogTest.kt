package com.greatwall.callring.ui.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.ui.testing.*
import com.greatwall.callring.ui.feature.ai.CredentialDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CredentialDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun plaintextIsNotRestoredAfterCompositionRecreation() {
        val connection = AiConnectionView(AiProtocol.OPENAI, "https://example.com", "model", true)
        val api = object : AiSettingsApi by UnavailableAiSettingsApi {
            override suspend fun getAiView() = ApiResult.Success(AiSettingsView(connection, connection, ConversationBudget(), 1))
            override suspend fun getRecordingView() = ApiResult.Success(RecordingSettingsView(false, false, CaptureMode.MICROPHONE_EXPERIMENTAL, false, 1))
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CredentialDialog(api, AiSlot.ANALYSIS) {} }
        compose.onNodeWithText("替换密钥").performClick()
        compose.onNodeWithTag("credential-key").performTextInput("sentinel-private-key")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("credential-key").assertDoesNotExist()
        compose.onNodeWithText("替换密钥").performClick()
        compose.onNodeWithTag("credential-key").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }
}
