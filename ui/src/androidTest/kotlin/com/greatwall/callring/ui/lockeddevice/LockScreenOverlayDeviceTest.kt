package com.greatwall.callring.ui.lockeddevice

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.greatwall.callring.contracts.callers.IncomingCallUiRequest
import com.greatwall.callring.contracts.callers.CallDecision
import com.greatwall.callring.contracts.runtime.PhoneActivity
import com.greatwall.callring.ui.overlay.*
import com.greatwall.callring.ui.testing.UiTestApplication
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Explicit unattended run only, outside the standard UI package scan. Leaves keyguard locked. */
@RequiresLockedDevice
class LockScreenOverlayDeviceTest {
    @Test fun actualKeyguardShowsLiveCardAndAnswerRemovesIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as UiTestApplication
        val service = Intent(context, CallringOverlayService::class.java)
        val monitor = instrumentation.addMonitor(LockScreenOverlayActivity::class.java.name, null, false)
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        try {
            shell("input keyevent KEYCODE_SLEEP")
            SystemClock.sleep(800)
            shell("input keyevent KEYCODE_WAKEUP")
            await("Device must actually be locked") { context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
            instrumentation.runOnMainSync {
                check(android.provider.Settings.canDrawOverlays(context)) { "Test package lacks effective draw-over permission" }
                context.startForegroundService(CallringOverlayService.intent(context,
                    IncomingCallUiRequest("locked-${System.nanoTime()}", "100", System.currentTimeMillis(), CallDecision.NORMAL_RINGING)))
            }
            await("Service must retain the incoming session") { app.overlaySessions.state.value != null }
            val activity = instrumentation.waitForMonitorWithTimeout(monitor, 15_000)
            assertNotNull("System must allow the lockscreen Activity", activity)
            await("Card must be attached and shown above the actual keyguard") {
                var shown = false
                instrumentation.runOnMainSync { shown = activity!!.window.decorView.isShown && activity.hasWindowFocus() }
                shown && instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == context.packageName
            }
            assertTrue(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(context.filesDir, "ui-screenshots/locked-incoming.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
            instrumentation.runOnMainSync { app.overlaySessions.phone(PhoneActivity.ACTIVE) }
            await("Answer must remove the session and its lockscreen Activity") {
                app.overlaySessions.state.value == null && activity!!.isFinishing
            }
        } finally {
            instrumentation.runOnMainSync { context.stopService(service); app.overlaySessions.dismiss() }
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw AssertionError(message)
            SystemClock.sleep(100)
        }
    }
}
