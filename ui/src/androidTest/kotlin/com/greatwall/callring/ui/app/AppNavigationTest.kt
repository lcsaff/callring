package com.greatwall.callring.ui.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.greatwall.callring.contracts.callers.CallerDraft
import com.greatwall.callring.contracts.callers.SaveCallerProfile
import com.greatwall.callring.ui.testing.UiFakeApi
import com.greatwall.callring.ui.testing.captureArtifact
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import com.greatwall.callring.ui.app.bridge.ThemeRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun callerReportEditorNavigateAndSaveThroughViewModel() {
        val api = UiFakeApi()
        val baseline = CallerDraft("100", "测试档案", "示例部门", null, false)
        compose.setContent { CallringApp(api, onCapability = {}, onPreviewCall = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.captureArtifact("home")
        compose.onNodeWithTag("tab-callers").performClick()
        compose.captureArtifact("callers")
        compose.onNodeWithText("测试档案").performClick()
        compose.onNodeWithText("号码报表").assertExists()
        compose.captureArtifact("report")
        compose.onNodeWithText("编辑档案").performClick()
        compose.captureArtifact("caller-editor")
        compose.onNode(hasSetTextAction() and hasText("姓名")).performTextReplacement("更新档案")
        compose.onNodeWithText("保存档案").performScrollTo().performClick()
        compose.waitUntil { api.savedProfile != null }
        compose.runOnIdle {
            assertEquals(1, api.profileEdits)
            assertEquals(SaveCallerProfile(baseline.number, "更新档案", baseline.department,
                baseline.positionId, false, baseline = baseline), api.savedProfile)
            assertEquals("更新档案", api.profile.name)
            assertFalse(api.profile.whitelisted)
        }
    }

    @Test fun compactBottomNavigationKeeps44dpContentAndWorkingDestinations() {
        val api = UiFakeApi()
        compose.setContent { CallringApp(api, onCapability = {}, onPreviewCall = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("bottom-bar").assertExists().assertHeightIsEqualTo(44.dp)
        listOf("home", "callers", "conversation", "settings").forEach { route ->
            compose.onNodeWithTag("tab-$route").assertHeightIsEqualTo(44.dp).assertHasClickAction()
        }
        compose.onNodeWithTag("tab-conversation").performClick()
        compose.onNodeWithText("AI 沟通").assertExists()
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("bottom-bar").assertExists()
    }

    @Test fun everySettingsDestinationOpensAndThemeSavesThroughApi() {
        val api = UiFakeApi()
        compose.setContent { CallringApp(api, onCapability = {}, onPreviewCall = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("tab-settings").performClick()
        compose.captureArtifact("settings")
        compose.onNodeWithText("免打扰").assertDoesNotExist()
        val destinations = listOf("我的职位", "工作时间", "节假日同步", "评分设置", "铃声", "运行状态与权限")
        destinations.forEach { title ->
            compose.onNodeWithText(title).performScrollTo().performClick()
            compose.onNodeWithTag("tab-settings").assertDoesNotExist()
            compose.onNodeWithText(title).assertExists()
            compose.captureArtifact("settings-${destinations.indexOf(title)}")
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("tab-settings").assertExists()
        }
        compose.onNodeWithText("外观").performScrollTo().performClick()
        compose.captureArtifact("appearance")
        compose.onNodeWithTag("preset-sky_blue").performScrollTo().performClick()
        compose.onNodeWithTag("settings-save").assertDoesNotExist()
        compose.waitUntil { api.appearance.presetId == "sky_blue" }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("主题色与深浅模式").assertExists()
    }

    @Test fun presetAndCustomThemesRenderInBothModes() {
        val api = UiFakeApi()
        compose.setContent { CallringApp(api, onCapability = {}, onPreviewCall = {}, onPinWidget = {}, onAppSettings = {}) }
        val appearances = ThemeRegistry.Default.presets.map { DefaultAppearance.copy(presetId = it.id) } +
            DefaultAppearance.copy(selection = ThemeSelection.CUSTOM, customSeedRgb = 0x663399)
        appearances.forEach { appearance ->
            listOf(ColorMode.LIGHT, ColorMode.DARK).forEach { mode ->
                runBlocking { api.saveAppearance(appearance.copy(colorMode = mode)) }
                compose.waitForIdle()
                compose.captureArtifact("theme-${if (appearance.selection == ThemeSelection.CUSTOM) "custom" else appearance.presetId}-${mode.name.lowercase()}")
            }
        }
    }
}
