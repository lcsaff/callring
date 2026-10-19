package com.greatwall.callring.ui.designsystem.components

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.greatwall.callring.ui.designsystem.CallringTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenFrameCompactTitleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactTitleContentHeightIs48dp() {
        compose.setContent { CallringTheme { ScreenFrame("号码档案", compactTitle = true) {} } }
        compose.onNodeWithTag("screen-frame-topbar").assertHeightIsEqualTo(48.dp)
    }

    @Test fun defaultTitleKeepsTheMaterialExpandedHeight() {
        compose.setContent { CallringTheme { ScreenFrame("外观设置") {} } }
        compose.onNodeWithTag("screen-frame-topbar").assertHeightIsEqualTo(64.dp)
    }

    @Test fun compactTitleKeepsLongTitlesAndBackActionDiscoverable() {
        var returned = false
        val longTitle = "这是一个非常长的标题，用于确认紧凑标题仍然只占一行并且不会被截断成多行"
        compose.setContent { CallringTheme {
            ScreenFrame(longTitle, onBack = { returned = true }, compactTitle = true) {}
        } }
        compose.onNodeWithTag("screen-frame-topbar").assertHeightIsEqualTo(48.dp)
        compose.onNodeWithText(longTitle).assertExists()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assert(returned) }
    }
}
