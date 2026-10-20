package com.greatwall.callring.ui.feature.callers

import com.greatwall.callring.contracts.command.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.command.NumberDeletionImpact
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NumberDeletionDialogsTest {
    @get:Rule val compose = createComposeRule()
    private val impact = NumberDeletionImpact("13800138000", true, 12, 3, 48L * 1024 * 1024, 1, false)

    @Test fun firstStepShowsImpactAndSecondStepRequiresTailAndAcknowledgment() {
        var confirmations = 0
        compose.setContent { MaterialTheme { NumberDeletionDialogs(impact, null, null, {}, { confirmations++ }) } }
        compose.onNodeWithText("删除该号码及全部数据？").assertExists()
        compose.onNodeWithText("· 通话记录 12 条").assertExists()
        compose.onNodeWithText("· 录音归档 3 条（音频 48.0 MB）").assertExists()
        compose.onNodeWithTag("delete-continue").performClick()
        compose.onNodeWithText("确认永久删除").assertExists()
        compose.onNodeWithTag("delete-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("delete-tail").performTextInput("8000")
        compose.onNodeWithTag("delete-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("delete-ack").performClick()
        compose.onNodeWithTag("delete-confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assert(confirmations == 1) }
    }

    @Test fun wrongTailKeepsDeleteDisabled() {
        compose.setContent { MaterialTheme { NumberDeletionDialogs(impact, null, null, {}, {}) } }
        compose.onNodeWithTag("delete-continue").performClick()
        compose.onNodeWithTag("delete-tail").performTextInput("0000")
        compose.onNodeWithTag("delete-ack").performClick()
        compose.onNodeWithTag("delete-confirm").assertIsNotEnabled()
    }
}
