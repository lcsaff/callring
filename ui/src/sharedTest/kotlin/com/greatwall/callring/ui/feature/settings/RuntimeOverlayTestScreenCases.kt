package com.greatwall.callring.ui.feature.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import com.greatwall.callring.ui.designsystem.CallringTheme
import com.greatwall.callring.ui.state.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test

abstract class RuntimeOverlayTestScreenCases {
    @get:Rule val compose = createComposeRule()

    @Test fun runtimeOffersOverlaySelfTestWithoutAnyCallHistory() {
        val status = runBlocking { UiFakeApi().getStatus().value }
        var requests = 0
        val mutation = mutableStateOf(MutationState())
        compose.setContent { CallringTheme {
            RuntimeScreen(LoadState(RuntimeUiData(status, emptyList(), null), loaded = true), mutation.value, {}, {}, {}, {}, {}, { requests++ })
        } }
        compose.onNodeWithText("测试悬浮窗").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("测试成功仅代表当前显示正常；后台来电显示还受系统后台限制影响。").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, requests); mutation.value = MutationState(busy = true) }
        compose.onNodeWithText("测试悬浮窗").assertIsNotEnabled()
    }
}
