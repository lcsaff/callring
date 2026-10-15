package com.greatwall.callring.ui.overlay

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.greatwall.callring.ui.testing.UiTestApplication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OverlaySelfTestServiceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explicitSelfTestIntentShowsAReadOnlySystemWindowAndCloseRemovesIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("com.greatwall.callring.ui.test", context.packageName)
        assertTrue("Grant overlay access to the isolated test package before this test", Settings.canDrawOverlays(context))
        val application = context.applicationContext as UiTestApplication
        val service = Intent(context, CallringOverlayService::class.java)
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        compose.setContent { Text("悬浮窗服务测试宿主") }
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            instrumentation.runOnMainSync {
                application.overlaySessions.dismiss()
                assertNotNull(context.startForegroundService(CallringOverlayService.testPreviewIntent(context)))
            }
            awaitCondition("The service must accept an explicit test session") {
                application.overlaySessions.state.value?.testPreview == true
            }
            var overlayView: View? = null
            awaitCondition("The service must attach a visible system overlay window") {
                instrumentation.runOnMainSync {
                    overlayView = WindowInspector.getGlobalWindowViews().singleOrNull {
                        (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
                            it.isAttachedToWindow && it.isShown && it.getGlobalVisibleRect(Rect())
                    }
                }
                overlayView != null
            }
            awaitCondition("The actual overlay must expose its test title and explanation") {
                val labels = automation.visibleNodes().map { it.text?.toString() }
                "悬浮窗测试" in labels && "仅检查悬浮窗显示，不会拨打电话、播放铃声或保存示例档案。" in labels
            }
            val labels = automation.visibleNodes().map { it.text?.toString() }
            listOf("编辑", "加入白名单", "移出白名单", "报表").forEach { action ->
                assertFalse("Test overlay must not offer $action", action in labels)
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                val destination = File(context.filesDir, "ui-screenshots/overlay-self-test-service.png")
                destination.parentFile!!.mkdirs()
                destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
            } ?: error("System-window screenshot was unavailable")
            val close = automation.visibleNodes().single { it.text?.toString() == "关闭" }
            var clickTarget: AccessibilityNodeInfo? = close
            while (clickTarget != null && !clickTarget.isClickable) clickTarget = clickTarget.parent
            assertNotNull("The visible close control must have a clickable accessibility node", clickTarget)
            assertTrue("Clicking the actual close control must succeed",
                clickTarget!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitCondition("Closing the actual test card must dismiss its session") {
                application.overlaySessions.state.value == null
            }
            awaitCondition("Closing the actual test card must remove its system window") {
                var removed = false
                instrumentation.runOnMainSync {
                    removed = WindowInspector.getGlobalWindowViews().none { it === overlayView }
                }
                removed
            }
        } finally {
            try {
                instrumentation.runOnMainSync {
                    context.stopService(service)
                    application.overlaySessions.dismiss()
                }
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            }
        }
    }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw AssertionError(description)
            SystemClock.sleep(100)
        }
    }

    private fun UiAutomation.visibleNodes(): List<AccessibilityNodeInfo> {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        windows.mapNotNull { it.root }.forEach(queue::add)
        if (queue.isEmpty()) rootInActiveWindow?.let(queue::add)
        val result = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser) result += node
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::add) }
        }
        return result
    }
}
