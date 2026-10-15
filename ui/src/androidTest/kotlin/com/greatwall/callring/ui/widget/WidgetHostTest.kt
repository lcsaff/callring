package com.greatwall.callring.ui.widget

import android.Manifest
import android.appwidget.*
import android.content.ComponentName
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.designsystem.ThemeRegistry
import com.greatwall.callring.ui.testing.UiTestApplication
import com.greatwall.callring.ui.testing.captureArtifact
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WidgetHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun twoRealHostViewsKeepBindingsActionsAndThemeIndependent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val api = (context.applicationContext as UiTestApplication).uiApi
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 0xCA11)
        val ids = mutableListOf<Int>()
        val views = mutableListOf<AppWidgetHostView>()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.BIND_APPWIDGET)
        try {
            repeat(2) { index ->
                val id = host.allocateAppWidgetId().also(ids::add)
                val options = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, if (index == 0) 180 else 280)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, if (index == 0) 180 else 280)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, if (index == 0) 170 else 210)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, if (index == 0) 170 else 210)
                }
                assertTrue("Test widget binding was denied", manager.bindAppWidgetIdIfAllowed(id,
                    ComponentName(context, CallringWidgetReceiver::class.java), options))
                runBlocking { api.saveBinding(id, if (index == 0) "100" else "200") }
            }
            host.startListening()
            compose.setContent {
                Column {
                    ids.forEachIndexed { index, id -> AndroidView(modifier = Modifier
                        .width(if (index == 0) 200.dp else 320.dp)
                        .height(if (index == 0) 190.dp else 230.dp), factory = { activity ->
                        host.createView(activity, id, manager.getAppWidgetInfo(id)).also { views += it }
                    }) }
                }
            }
            runBlocking { UiWidgetRefresh.refresh(context, ids.toSet()) }
            compose.waitUntil(20_000) { views.size == 2 && views[0].text("测试号码100") != null && views[1].text("测试号码200") != null }
            compose.captureArtifact("widget-host-two")
            compose.runOnIdle {
                assertNull(views[0].text("示例部门"))
                assertNotNull(views[1].text("示例部门"))
                assertNotNull(views[1].text("更换号码"))
                assertNotNull(views[1].text("刷新"))
                assertNotNull(views[0].text("不受欢迎程度评分 80"))
                assertNotNull(views[1].text("不受欢迎程度评分 20"))
                val link = views[0].text("加入白名单")!!
                var click: View = link
                while (!click.hasOnClickListeners() && click.parent is View) click = click.parent as View
                assertTrue(click.performClick())
            }
            compose.waitUntil(10_000) { api.whitelist["100"] == true }
            assertFalse(api.whitelist["200"] ?: false)
            val appearance = AppearanceSettings(ThemeSelection.CUSTOM, "sky_blue", 0x663399, ColorMode.LIGHT)
            runBlocking { api.saveAppearance(appearance); UiWidgetRefresh.refresh(context) }
            val expected = ThemeRegistry.resolve(appearance).light.onSurface
            val color = expected.toArgb()
            compose.waitUntil(10_000) { views.all { it.text(if (it === views[0]) "测试号码100" else "测试号码200")?.currentTextColor == color } }
            compose.captureArtifact("widget-host-custom-theme")
            host.deleteAppWidgetId(ids.removeAt(0))
            assertEquals("200", runBlocking { api.getBinding(ids.single()) }.value?.number)
        } finally {
            ids.forEach(host::deleteAppWidgetId)
            host.stopListening()
            host.deleteHost()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun View.text(value: String): TextView? {
        if (this is TextView && text.toString() == value) return this
        if (this is ViewGroup) for (index in 0 until childCount) getChildAt(index).text(value)?.let { return it }
        return null
    }
}
