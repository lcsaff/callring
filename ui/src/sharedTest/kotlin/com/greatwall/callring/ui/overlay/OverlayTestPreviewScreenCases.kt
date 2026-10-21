package com.greatwall.callring.ui.overlay

import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.LoadState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

abstract class OverlayTestPreviewScreenCases {
    @get:Rule val compose = createComposeRule()

    @Test fun testCardExplainsItsPurposeAndOnlyOffersWindowControls() {
        val sessions = OverlaySessions()
        val preview = OverlaySessions::class.java.methods.singleOrNull { it.name == "preview" && it.parameterCount == 2 }
        assertNotNull("Test mode needs an explicit read-only session", preview)
        preview!!.invoke(sessions, IncomingCallUiRequest("test", "100", 1, CallDecision.NORMAL_RINGING), true)
        val session = sessions.state.value!!
        var closed = false
        var collapsed = false
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            OverlayScreen(OverlayContentState(session, LoadState()), {}, { closed = true }, { collapsed = true },
                {}, {}, {}, {}, {}, {}, {}, {}, 620.dp)
        } }
        compose.onNodeWithText("悬浮窗测试").assertIsDisplayed()
        compose.onNodeWithText("仅检查悬浮窗显示，不会拨打电话、播放铃声或保存示例档案。").assertIsDisplayed()
        compose.onNodeWithText("编辑").assertDoesNotExist()
        compose.onNodeWithText("加入白名单").assertDoesNotExist()
        compose.onNodeWithText("报表").assertDoesNotExist()
        compose.onNodeWithText("收起").performClick()
        compose.runOnIdle { assertTrue(collapsed) }
        compose.onNodeWithText("关闭").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }
}
