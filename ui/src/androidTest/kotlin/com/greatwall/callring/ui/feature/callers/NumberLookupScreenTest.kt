package com.greatwall.callring.ui.feature.callers

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.callers.NumberCheck
import com.greatwall.callring.ui.designsystem.CallringTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NumberLookupScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun validNumberCanOpenReportEvenWhenItDoesNotMatchTheRingtoneRule() {
        var reports = 0
        compose.setContent { CallringTheme {
            NumberLookupScreen("100", NumberCheck("100", true, false), {}, {}, { reports++ })
        } }
        compose.onNodeWithText("不符合规则号码").assertIsDisplayed()
        compose.onNodeWithText("查看报表").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, reports) }
    }
}
