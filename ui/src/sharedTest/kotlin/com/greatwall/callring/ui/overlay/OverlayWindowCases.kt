package com.greatwall.callring.ui.overlay

import androidx.compose.material3.Text
import android.os.Build
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant

abstract class OverlayWindowCases {
    @get:Rule val compose = createComposeRule()

    @OptIn(InternalComposeUiApi::class)
    @Test fun windowCanHideAndReattachWithAnEditableLiveDraft() {
        val api = object : UiFakeApi(true) {
            override suspend fun getStatus() = ApiResult.Success(super.getStatus().value.copy(phoneActivity = PhoneActivity.RINGING))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var controller: OverlayController
        lateinit var window: OverlayWindow
        var controllerCreated = false
        var windowCreated = false
        compose.setContent { Text("测试宿主") }
        try {
            compose.runOnIdle {
                controller = OverlayController(api, OverlaySessions(), scope)
                controllerCreated = true
                controller.show(IncomingCallUiRequest("window", "100", System.currentTimeMillis(), CallDecision.NORMAL_RINGING))
                window = OverlayWindow(InstrumentationRegistry.getInstrumentation().targetContext, controller) {}
                windowCreated = true
                WindowRecomposerPolicy.withFactory(WindowRecomposerFactory.LifecycleAware) { window.show(false) }
            }
            compose.onNodeWithText("来电档案").assertExists()
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("来电档案").assertIsDisplayed() }.isSuccess }
            compose.onNodeWithText("收起").performClick()
            compose.onNodeWithText("展开").assertIsDisplayed().performClick()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            if (Build.FINGERPRINT != "robolectric") instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.filesDir, "overlay-window.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            compose.runOnIdle { window.hide() }
            compose.runOnIdle { WindowRecomposerPolicy.withFactory(WindowRecomposerFactory.LifecycleAware) { window.show(false) } }
            compose.runOnIdle { controller.beginEditing() }
            compose.runOnIdle { window.show(true) }
            compose.onNode(hasSetTextAction() and hasText("姓名")).performClick().performTextReplacement("重新挂载后可编辑")
            compose.onNodeWithText("重新挂载后可编辑").assertExists()
            compose.runOnIdle {
                val view = OverlayWindow::class.java.getDeclaredField("view").apply { isAccessible = true }.get(window) as android.view.View
                val params = view.layoutParams as android.view.WindowManager.LayoutParams
                assertEquals(0, params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                window.hide()
            }
            compose.runOnIdle { WindowRecomposerPolicy.withFactory(WindowRecomposerFactory.LifecycleAware) { window.show(true) } }
            compose.onNodeWithText("重新挂载后可编辑").assertIsDisplayed()
            compose.onNodeWithText("未设置").performScrollTo().performClick()
            compose.onAllNodesWithText("未设置").onLast().performClick()
            compose.onNodeWithText("取消编辑").performClick()
            compose.waitForIdle()
            val confirmationRoot = withDecorView(hasDescendant(withText("放弃未保存的修改？")))
            onView(withText("放弃未保存的修改？")).inRoot(confirmationRoot).check(matches(isDisplayed()))
            onView(withText("取消")).inRoot(confirmationRoot).perform(click())
            compose.onNodeWithText("重新挂载后可编辑").assertIsDisplayed()
            if (Build.FINGERPRINT != "robolectric") instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(instrumentation.targetContext.filesDir, "overlay-editor-restored.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            compose.onNodeWithText("取消编辑").performClick()
            compose.waitForIdle()
            onView(withText("放弃修改")).inRoot(confirmationRoot).perform(click())
            compose.onNodeWithText("编辑").assertExists()
        } finally {
            compose.runOnIdle { if (windowCreated) window.close(); if (controllerCreated) controller.close(); scope.cancel() }
        }
    }
}
