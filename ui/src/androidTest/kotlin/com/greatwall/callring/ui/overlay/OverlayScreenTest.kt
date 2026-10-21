package com.greatwall.callring.ui.overlay

import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OverlayScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun instantDecisionUsesIncomingRequestAndWhitelistUsesExplicitTarget() {
        val api = UiFakeApi()
        val overview = (runBlocking { api.getOverview(OverviewQuery("100", 123)) } as ApiResult.Success).value
        var target: Boolean? = null
        val state = OverlayContentState(OverlaySessionState(IncomingCallUiRequest("one", "100", 123, CallDecision.QUIET_SILENCED)), LoadState(overview, loaded = true))
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) {
            OverlayScreen(state, {}, {}, {}, {}, {}, {}, {}, { target = it }, {}, {}, {}, 620.dp)
        } }
        compose.onNodeWithText("免打扰静音").assertExists()
        compose.onNodeWithText("正常响铃").assertDoesNotExist()
        compose.onNodeWithText("加入白名单").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, target) }
    }

    @Test fun editingAfterCallEndRetainsDraftAndCancelAction() {
        val draft = CallerDraft("100", "草稿姓名", "", null, false)
        val state = OverlayContentState(OverlaySessionState(IncomingCallUiRequest("one", "100", 123, CallDecision.NORMAL_RINGING),
            editing = true, callEnded = true), LoadState(), LoadState(CallerEditorData(draft, emptyList(), null), loaded = true), draft)
        var cancelled = false
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(false)) {
            OverlayScreen(state, {}, {}, {}, {}, { cancelled = true }, {}, {}, {}, {}, {}, {}, 620.dp)
        } }
        compose.onNodeWithText("草稿姓名").assertExists()
        compose.onNodeWithText("通话已结束，保存或取消后关闭卡片").assertExists()
        compose.onNodeWithText("取消编辑").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }
}
