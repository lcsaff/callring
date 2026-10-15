package com.greatwall.callring.ui.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.ui.testing.UiFakeApi
import com.greatwall.callring.ui.testing.captureArtifact
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.DefaultAppearance
import com.greatwall.callring.ui.designsystem.ThemeRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun callerReportEditorNavigateAndSaveThroughViewModel() {
        val api = UiFakeApi()
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
            assertEquals("更新档案", api.savedProfile?.name)
            assertNull(api.savedProfile?.whitelisted)
        }
    }

    @Test fun everySettingsDestinationOpensAndThemeSavesThroughApi() {
        val api = UiFakeApi()
        compose.setContent { CallringApp(api, onCapability = {}, onPreviewCall = {}, onPinWidget = {}, onAppSettings = {}) }
        compose.onNodeWithTag("tab-settings").performClick()
        compose.captureArtifact("settings")
        val destinations = listOf("免打扰", "职位管理", "工作时间", "节假日与日期覆盖", "请假管理", "评分设置", "铃声", "运行状态与权限")
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
        compose.onNodeWithTag("settings-save").performScrollTo().performClick()
        compose.waitUntil { api.appearance.presetId == "sky_blue" }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("主题色与深浅模式").assertExists()
    }

    @Test fun presetAndCustomThemesRenderInBothModes() {
        val api = UiFakeApi()
        compose.setContent { CallringApp(api, onCapability = {}, onPreviewCall = {}, onPinWidget = {}, onAppSettings = {}) }
        val appearances = ThemeRegistry.presets.map { DefaultAppearance.copy(presetId = it.id) } +
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
