package com.greatwall.callring.ui.designsystem

import com.greatwall.callring.ui.app.bridge.rememberAppearanceThemeSpec
import com.greatwall.callring.ui.app.bridge.DefaultAppearance
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.ui.designsystem.components.DateRangeSelectionDialog
import com.greatwall.callring.ui.designsystem.components.DateSelectionField
import com.greatwall.callring.ui.designsystem.components.TimeSelectionField
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PickerLayoutJvmTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dateCalendarAndKeyboardKeepActionsVisible() {
        var selected = "2026-10-17"
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) { DateSelectionField("结束日期（含当天）", selected, { selected = it }) } }
        compose.onNodeWithTag("date-结束日期（含当天）").performClick()
        compose.onNodeWithText("确定").assertIsDisplayed()
        capture("date-calendar")
        compose.onNodeWithText("输入日期").performClick()
        compose.onNodeWithText("确定").assertIsDisplayed()
        capture("date-input")
        compose.onNodeWithText("取消").performClick()
        assertEquals("2026-10-17", selected)
    }

    @Test @Config(qualifiers = "w640dp-h360dp")
    fun timeClockAndKeyboardKeepActionsVisibleInShortWindow() {
        var selected = "23:45"
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) { TimeSelectionField("开始时间", selected, { selected = it }) } }
        compose.onNodeWithTag("time-开始时间").performClick()
        compose.onNodeWithText("确定").assertIsDisplayed()
        capture("time-clock-short")
        compose.onNodeWithText("切换为键盘输入").performClick()
        compose.onNodeWithText("确定").assertIsDisplayed()
        capture("time-input-short")
        compose.onNodeWithText("确定").performClick()
        assertEquals("23:45", selected)
    }

    @Test fun dateRangeKeepsSummaryAndConfirmationVisible() {
        val start = LocalDate.of(2026, 10, 1)
        val end = LocalDate.of(2026, 10, 17)
        var selected: Pair<LocalDate, LocalDate>? = null
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) {
            DateRangeSelectionDialog("统计日期范围", start, end, {}, { first, last -> selected = first to last })
        } }
        compose.onNodeWithText("2026-10-01").assertIsDisplayed()
        compose.onNodeWithText("至 2026-10-17").assertIsDisplayed()
        compose.onNodeWithText("确定").assertIsDisplayed()
        capture("date-range")
        compose.onNodeWithText("确定").performClick()
        assertEquals(start to end, selected)
    }

    @Test @Config(qualifiers = "w640dp-h360dp")
    fun dateInputRemainsReachableInShortWindow() {
        var selected = "2026-10-17"
        compose.setContent { CallringTheme(DefaultAppearance.rememberAppearanceThemeSpec(androidx.compose.foundation.isSystemInDarkTheme())) { DateSelectionField("日期", selected, { selected = it }) } }
        compose.onNodeWithTag("date-日期").performClick()
        compose.onNodeWithText("确定").assertIsDisplayed()
        capture("date-calendar-short")
        compose.onNodeWithText("输入日期").performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("date-picker-日期"))).assertIsDisplayed()
        compose.onNodeWithText("确定").assertIsDisplayed().performClick()
        assertEquals("2026-10-17", selected)
    }

    @Test @Config(qualifiers = "w640dp-h360dp")
    fun dateRangeConfirmationRemainsReachableInShortWindow() {
        dateRangeKeepsSummaryAndConfirmationVisible()
    }

    private fun capture(name: String) {
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val folder = File("build/screenshots/pickers").apply { mkdirs() }
            File(folder, "$name-${view.width}x${view.height}.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
