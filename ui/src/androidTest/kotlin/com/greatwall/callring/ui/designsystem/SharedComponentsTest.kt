package com.greatwall.callring.ui.designsystem

import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.app.bridge.toThemeSpec
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.greatwall.callring.contracts.settings.ColorMode
import com.greatwall.callring.ui.designsystem.components.ScoreBadge
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SharedComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scoreHasAccessibleNumericAndSemanticMeaning() {
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) { ScoreBadge(0) } }
        compose.onNodeWithContentDescription("评分 0 分，不受欢迎程度高").assertIsDisplayed()
    }

    @Test fun unavailableScoreDoesNotLookLikeZero() {
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) { ScoreBadge(null) } }
        compose.onNodeWithContentDescription("暂无评分").assertIsDisplayed()
    }

    @Test fun headerBackActionRemainsDiscoverableAndInvokesCallback() {
        var returned = false
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) { ScreenFrame("外观设置", onBack = { returned = true }) {} } }
        compose.onNodeWithText("外观设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        assertTrue(returned)
    }

    @Test fun compactTitleContentHeightIs40dpWithoutStatusBarInset() {
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) { ScreenFrame("号码档案", compactTitle = true) {} } }
        compose.onNodeWithTag("screen-frame-topbar").assertHeightIsEqualTo(40.dp)
    }

    @Test fun defaultTitleKeepsTheMaterialExpandedHeight() {
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) { ScreenFrame("录音设置") {} } }
        compose.onNodeWithTag("screen-frame-topbar").assertHeightIsEqualTo(64.dp)
    }

    @Test fun explicitDarkSelectionDrivesBothUiAndSemanticScoreMode() {
        val appearance = DefaultAppearance.copy(colorMode = ColorMode.DARK)
        var semanticDark = false
        var displayedSurface = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            CallringTheme((appearance).rememberAppearanceThemeSpec(false)) {
                semanticDark = LocalCallringDarkMode.current
                displayedSurface = MaterialTheme.colorScheme.surface
                ScoreBadge(100)
            }
        }
        compose.runOnIdle {
            assertTrue(semanticDark)
            assertEquals(appearance.toThemeSpec(false).definition.dark.surface, displayedSurface)
        }
    }
}
