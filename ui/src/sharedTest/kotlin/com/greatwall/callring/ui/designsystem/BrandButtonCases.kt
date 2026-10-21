package com.greatwall.callring.ui.designsystem

import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.greatwall.callring.contracts.settings.ColorMode
import com.greatwall.callring.contracts.settings.ThemeSelection
import com.greatwall.callring.ui.designsystem.components.PrimaryAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

abstract class BrandButtonCases {
    @get:Rule val compose = createComposeRule()

    @Test fun primaryActionsRenderTheExactSelectedRgbInLightAndDarkModes() {
        val appearance = mutableStateOf(DefaultAppearance)
        compose.setContent {
            CallringTheme((appearance.value).rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
                Box(Modifier.width(280.dp)) {
                    PrimaryAction("应用主题", {}, modifier = Modifier.testTag("brand-action"))
                }
            }
        }
        for (rgb in listOf(0xE60012, 0x1976D2, 0x008577, 0x000000, 0xFFFFFF)) {
            for (mode in listOf(ColorMode.LIGHT, ColorMode.DARK)) {
                compose.runOnIdle {
                    appearance.value = DefaultAppearance.copy(selection = ThemeSelection.CUSTOM,
                        customSeedRgb = rgb, colorMode = mode)
                }
                val node = compose.onNodeWithTag("brand-action")
                val captured = if (Build.FINGERPRINT == "robolectric") {
                    // Robolectric renders native Canvas; Android's PixelCopy redraw callback requires a real window.
                    val bounds = node.fetchSemanticsNode().boundsInWindow
                    lateinit var bitmap: Bitmap
                    compose.runOnIdle {
                        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
                        val root = activity.window.decorView
                        val full = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(full))
                        bitmap = Bitmap.createBitmap(full, bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
                    }
                    bitmap.asImageBitmap()
                } else node.captureToImage()
                val pixels = captured.toPixelMap()
                assertEquals("$rgb in $mode was visually shifted", rgb,
                    pixels[pixels.width / 10, pixels.height / 2].toArgb() and 0xFFFFFF)
                val folder = if (Build.FINGERPRINT == "robolectric") File("build/screenshots/brand")
                    else File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ui-screenshots/brand")
                folder.mkdirs()
                File(folder, "${rgb.toString(16)}-${mode.name}.png").outputStream().use {
                    check(captured.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
        }
    }
}
