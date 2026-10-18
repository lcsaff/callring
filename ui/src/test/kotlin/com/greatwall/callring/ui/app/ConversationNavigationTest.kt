package com.greatwall.callring.ui.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.ui.testing.UiFakeApi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationNavigationTest {
    @get:Rule val compose = createComposeRule()
    private fun show() { compose.setContent { CallringApp(UiFakeApi(), onCapability = {}, onPreviewCall = {}, onPinWidget = {}, onAppSettings = {}) } }
    @Test fun narrowScreenHasFourPrimaryDestinationsAndSeparateSettings() {
        show()
        listOf("home", "callers", "conversation", "settings").forEach { compose.onNodeWithTag("tab-$it").assertExists() }
        compose.onNodeWithTag("tab-conversation").performClick()
        compose.onNodeWithText("AI 沟通").assertExists()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithText("AI 设置").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("录音设置").performScrollTo().assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w900dp-h900dp") fun wideScreenAlsoHasFourPrimaryDestinations() {
        show()
        listOf("home", "callers", "conversation", "settings").forEach { compose.onNodeWithTag("tab-$it").assertExists() }
    }
}
