package com.greatwall.callring.ui.app

import android.content.pm.ActivityInfo
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Uses live accessibility roots: Compose's old-root frame idler can hang across rotation. */
class HomePagerRestoreDeviceTest {
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            nodes(automation.rootInActiveWindow).firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        error("Expected accessibility node did not appear in the isolated test activity")
    }

    private fun draft(): AccessibilityNodeInfo = find { it.className == "android.widget.EditText" && it.text?.toString() == "旋转保留草稿" }
    private fun click(label: String) {
        val target = generateSequence(find { it.text?.toString() == label }) { it.parent }.first { it.isClickable }
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun selected(label: String) = find { node ->
        node.text?.toString() == label && generateSequence(node) { it.parent }.any { it.isSelected }
    }

    @Test fun rotationRecreationAndSystemBackKeepPageAndDraft() {
        ActivityScenario.launch(CallringActivity::class.java).use { scenario ->
            click("沟通")
            val field = find { it.className == "android.widget.EditText" }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "旋转保留草稿")
            }))
            draft()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            selected("沟通")
            draft()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            scenario.recreate()
            selected("沟通")
            draft()
            // A normal task foreground launch has no explicit navigation request.
            scenario.onActivity { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(it, Intent(it, CallringActivity::class.java)) }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(300)
            selected("沟通")
            draft()
            scenario.onActivity { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(it,
                Intent(it.intent).putExtra("callring.ui.target", "home")) }
            selected("首页")
            click("沟通")
            draft()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            selected("首页")
            click("沟通")
            draft()
        }
    }
}
