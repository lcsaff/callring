package com.greatwall.callring.ui.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.feature.conversation.ConversationScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h800dp")
class ConversationProgressReviewTest {
    @get:Rule val compose=createComposeRule()
    @Test fun latestReplyAfterLongTableAndTenVerifiedReceiptsIsVisible(){
        val table="| Row | Value |\n|---|---|\n"+(1..40).joinToString("\n"){"| $it | Synthetic |"}
        val state=mutableStateOf(ConversationState("s",messages=listOf(ConversationMessage("assistant",table))))
        compose.setContent{MaterialTheme{ConversationScreen(state.value,"","OPENAI · https://example.com/v1 · fixture · STANDARD",null,{},{},{},{})}}
        compose.runOnIdle{state.value=state.value.copy(phase=ConversationPhase.COMPLETED,messages=state.value.messages+ConversationMessage("user","New request")+ConversationMessage("assistant","LATEST-ANSWER"),receipts=(1..10).map{ToolReceipt("op$it","call$it",ToolStatus.COMMITTED,aiObject("name" to aiString("Synthetic-$it")),verification=VerificationStatus.VERIFIED)})}
        compose.waitUntil(5000){runCatching{compose.onNodeWithText("LATEST-ANSWER").assertIsDisplayed();true}.getOrDefault(false)}
        compose.onNodeWithText("LATEST-ANSWER").assertIsDisplayed()
        compose.onNodeWithText("姓名：Synthetic-1").assertDoesNotExist()
        compose.onNodeWithText("查看 10 项已核验操作").assertExists().performScrollTo().performClick()
        compose.onNodeWithText("姓名：Synthetic-1").assertExists()
    }
    @Test fun activeHistoryReadingKeepsItsPositionUntilReturnToLatest(){
        val state=mutableStateOf(ConversationState("s",messages=(1..35).map{ConversationMessage("assistant","Older result $it\n\nBody $it")}))
        compose.setContent{MaterialTheme{ConversationScreen(state.value,"","configured",null,{},{},{},{})}}
        repeat(3){compose.onNodeWithTag("conversation-messages").performTouchInput{swipeDown()}}
        compose.onNodeWithText("回到最新").assertIsDisplayed()
        compose.runOnIdle{state.value=state.value.copy(messages=state.value.messages+ConversationMessage("assistant","NEWEST-WHILE-READING"),phase=ConversationPhase.STOPPED)}
        compose.onNodeWithText("NEWEST-WHILE-READING").assertIsNotDisplayed()
        compose.onNodeWithText("回到最新").performClick()
        compose.onNodeWithText("NEWEST-WHILE-READING").assertIsDisplayed()
    }
    @Test fun conversationShowsNoServiceLabelAndNeverLeaksFullTarget(){
        val target=mutableStateOf("OPENAI · https://one.example/v1/custom · first · STANDARD")
        compose.setContent{MaterialTheme{ConversationScreen(ConversationState("s"),"input",target.value,null,{},{},{},{})}}
        compose.onNodeWithText(target.value).assertDoesNotExist()
        compose.onNodeWithText("服务：").assertDoesNotExist()
        compose.onNodeWithContentDescription("AI 设置").assertExists()
        compose.onNodeWithTag("ai-ready-dot").assertContentDescriptionEquals("就绪")
        compose.runOnIdle{target.value="OPENAI · https://two.example/other · second · STANDARD"}
        compose.onNodeWithText(target.value).assertDoesNotExist()
        compose.onNodeWithContentDescription("发送消息").assertIsEnabled()
    }
}
