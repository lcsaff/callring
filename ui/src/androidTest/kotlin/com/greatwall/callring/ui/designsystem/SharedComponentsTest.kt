package com.greatwall.callring.ui.designsystem

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.material3.MaterialTheme
import com.greatwall.callring.application.api.ColorMode
import com.greatwall.callring.ui.designsystem.components.ScoreBadge
import com.greatwall.callring.ui.designsystem.components.ScreenFrame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SharedComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scoreHasAccessibleNumericAndSemanticMeaning() {
        compose.setContent { CallringTheme { ScoreBadge(0) } }
        compose.onNodeWithContentDescription("评分 0 分，不受欢迎程度高").assertIsDisplayed()
    }

    @Test fun unavailableScoreDoesNotLookLikeZero() {
        compose.setContent { CallringTheme { ScoreBadge(null) } }
        compose.onNodeWithContentDescription("暂无评分").assertIsDisplayed()
    }

    @Test fun headerBackActionRemainsDiscoverableAndInvokesCallback() {
        var returned = false
        compose.setContent { CallringTheme { ScreenFrame("外观设置", onBack = { returned = true }) {} } }
        compose.onNodeWithText("外观设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        assertTrue(returned)
    }

    @Test fun explicitDarkSelectionDrivesBothUiAndSemanticScoreMode() {
        val appearance = DefaultAppearance.copy(colorMode = ColorMode.DARK)
        var semanticDark = false
        var displayedSurface = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            CallringTheme(appearance = appearance, systemDark = false) {
                semanticDark = LocalCallringDarkMode.current
                displayedSurface = MaterialTheme.colorScheme.surface
                ScoreBadge(100)
            }
        }
        compose.runOnIdle {
            assertTrue(semanticDark)
            assertEquals(ThemeRegistry.resolve(appearance).dark.surface, displayedSurface)
        }
    }
}
