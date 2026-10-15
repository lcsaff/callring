package com.greatwall.callring.ui.widget

import android.Manifest
import android.appwidget.*
import android.content.ComponentName
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.ThemeRegistry
import com.greatwall.callring.ui.testing.UiTestApplication
import com.greatwall.callring.ui.testing.captureArtifact
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WidgetHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun twoRealHostSizesShareGlobalQuietTodayLeaveAndAppearanceWithoutNumberConfiguration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val api = (context.applicationContext as UiTestApplication).uiApi
        runBlocking { api.resetWidgetState() }
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 0xCA11)
        val ids = mutableListOf<Int>()
        val views = mutableListOf<AppWidgetHostView>()
        val sizes = listOf(180 to 170, 280 to 210)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            sizes.forEach { (width, height) ->
                val id = host.allocateAppWidgetId().also(ids::add)
                val options = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
                }
                assertTrue("Test widget binding was denied", manager.bindAppWidgetIdIfAllowed(id,
                    ComponentName(context, CallringWidgetReceiver::class.java), options))
                assertNull("A global widget must not require a number binding", runBlocking { api.getBinding(id) }.value)
            }
            host.startListening()
            compose.setContent {
                Column {
                    ids.forEachIndexed { index, id ->
                        AndroidView(modifier = Modifier.width(sizes[index].first.dp).height(sizes[index].second.dp),
                            factory = { activity ->
                                host.createView(activity, id, manager.getAppWidgetInfo(id)).also { views += it }
                            })
                    }
                }
            }
            runBlocking { UiWidgetRefresh.refresh(context, ids.toSet()) }
            compose.waitUntil(20_000) {
                views.size == 2 && views.all {
                    it.text("来电管理") != null && it.text("今日请假") != null && it.quietSwitch() != null
                }
            }
            compose.runOnIdle {
                views.forEach { view ->
                    assertFalse(view.quietSwitch()!!.isChecked)
                    val labels = view.descendants().filterIsInstance<TextView>().map { it.text.toString() }.toList()
                    listOf("号码", "测试档案", "示例部门", "评分", "报表", "白名单", "查看", "更换", "刷新", "选择").forEach { forbidden ->
                        assertFalse("Unexpected number/configuration content: $labels", labels.any { forbidden in it })
                    }
                    assertTrue(labels.any { "免打扰" in it })
                }
            }
            compose.captureArtifact("widget-host-global-two-sizes")

            compose.runOnIdle { views[0].quietSwitch()!!.performClick() }
            compose.waitUntil(10_000) {
                api.quietModeWrites.toList() == listOf(QuietMode.NON_WORK) && views.all { it.quietSwitch()?.isChecked == true }
            }
            compose.runOnIdle { views[1].quietSwitch()!!.performClick() }
            compose.waitUntil(10_000) {
                api.quietModeWrites.toList() == listOf(QuietMode.NON_WORK, QuietMode.OFF) && views.all { it.quietSwitch()?.isChecked == false }
            }
            compose.runOnIdle { views[1].quietSwitch()!!.performClick() }
            compose.waitUntil(10_000) {
                api.quietModeWrites.size == 3 && views.all { it.quietSwitch()?.isChecked == true }
            }

            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            lateinit var originalLeaveAction: View
            compose.runOnIdle {
                originalLeaveAction = views[0].text("今日请假")!!.actionView()
                assertTrue(originalLeaveAction.performClick())
            }
            compose.waitUntil(10_000) { api.leaveWrites.size == 1 && views.all { it.text("今日已请假") != null } }
            assertEquals(LeavePeriod.AllDay(today, today, zone.id), api.leaveWrites.single().period)
            assertNull(api.leaveWrites.single().id)
            compose.runOnIdle {
                // An old RemoteViews action may still be delivered after another host acknowledges it.
                originalLeaveAction.performClick()
                views[1].text("今日已请假")!!.actionView().performClick()
            }

            val appearance = AppearanceSettings(ThemeSelection.CUSTOM, "sky_blue", 0x663399, ColorMode.LIGHT)
            runBlocking { api.saveAppearance(appearance); UiWidgetRefresh.refresh(context) }
            val expectedColor = ThemeRegistry.resolve(appearance).light.onSurface.toArgb()
            compose.waitUntil(10_000) {
                views.all { it.text("来电管理")?.currentTextColor == expectedColor && it.text("今日已请假") != null }
            }
            assertEquals("Repeated actions must not create duplicate leave", 1, api.leaveWrites.size)
            compose.captureArtifact("widget-host-global-custom-theme")

            host.deleteAppWidgetId(ids.removeAt(0))
            runBlocking { UiWidgetRefresh.refresh(context, ids.toSet()) }
            compose.waitUntil(10_000) {
                views[1].text("今日已请假") != null && views[1].quietSwitch()?.isChecked == true
            }
            assertEquals(QuietMode.NON_WORK, runBlocking { api.getQuietHours() }.value.mode)
            assertEquals(1, runBlocking { api.getLeaves() }.value.size)
            assertEquals(1, api.leaveWrites.size)
        } finally {
            ids.forEach(host::deleteAppWidgetId)
            host.stopListening()
            host.deleteHost()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            runBlocking { api.resetWidgetState() }
        }
    }

    private fun View.descendants(): Sequence<View> = sequence {
        yield(this@descendants)
        if (this@descendants is ViewGroup) {
            for (index in 0 until childCount) yieldAll(getChildAt(index).descendants())
        }
    }

    private fun View.text(value: String): TextView? =
        descendants().filterIsInstance<TextView>().firstOrNull { it.text.toString() == value }

    private fun View.quietSwitch(): CompoundButton? = descendants().filterIsInstance<CompoundButton>().singleOrNull()

    private fun View.actionView(): View {
        var target = this
        while (!target.hasOnClickListeners() && target.parent is View) target = target.parent as View
        return target
    }
}
