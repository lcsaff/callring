package com.greatwall.callring.ui.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.ui.testing.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfirmationSummaryTest {
    @get:Rule val compose = createComposeRule()
    @Test fun everyConfirmationShowsItsLocalTargetAndMissingEvidenceCannotConfirm() {
        val pending = MutableStateFlow<ForegroundTicket?>(null)
        val delivered = mutableListOf<ForegroundHostResult>()
        val bridge = object : ForegroundActionApi by UnavailableForegroundActionApi {
            override val pending = pending
            override fun deliver(requestId: String, result: ForegroundHostResult): Boolean { delivered += result; pending.value = null; return true }
        }
        val api = object : UiFakeApi() { override val conversation = object : ConversationApi by UnavailableConversationApi { override val foreground = bridge } }
        compose.setContent { ForegroundActionDialogs(api) }
        val cases = listOf(
            Triple("settings_deletePosition",ConfirmationTarget.Position(37,"actual-role",3,true),"职位 #37：actual-role"),
            Triple("settings_deleteLeave",ConfirmationTarget.Leave(8,10,20,"actual-note"),"备注：actual-note"),
            Triple("recordings_delete",ConfirmationTarget.Recording(9,"31234",123,7),"录音 #9：31234"),
            Triple("recordings_adoptIdentity",ConfirmationTarget.Recording(9,"31234",123,7,ConfirmationTarget.Adoption("old","old-unit","self","new-unit")),"姓名：old → self"),
            Triple("recordings_testConnection",ConfirmationTarget.Connection(AiSlot.TRANSCRIPTION,AiConnectionView(AiProtocol.OPENAI,"https://actual.example","actual-model",true)),"模型：actual-model"))
        for((operation,target,label) in cases) {
            compose.runOnIdle { pending.value = ForegroundTicket(operation,"s","t","call",1,ForegroundActionKind.CONFIRM,aiObject("operation" to aiString(operation),"name" to aiString("untrusted-model-label")),0,600000,true,confirmation = target) }
            compose.onNodeWithText(label).assertExists()
            compose.onNodeWithText("untrusted-model-label",substring = true).assertDoesNotExist()
            compose.onNodeWithText("确认").assertIsEnabled()
            compose.onNodeWithText("取消").performClick()
        }
        compose.runOnIdle { assertEquals(5,delivered.size); assertTrue(delivered.all { it.status == ForegroundResultStatus.CANCELLED }); pending.value = ForegroundTicket("missing","s","t","call",1,ForegroundActionKind.CONFIRM,aiObject(),0,600000,true) }
        compose.onNodeWithText("确认").assertIsNotEnabled()
    }
}
