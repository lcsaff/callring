package com.greatwall.callring.ui.testing

import android.os.PowerManager
import androidx.test.runner.AndroidJUnitRunner

/** Keep the isolated UI test activity visible across scenario transitions. */
class UiTestRunner : AndroidJUnitRunner() {
    private var lockScreenRun = false
    override fun onCreate(arguments: android.os.Bundle) {
        lockScreenRun = arguments.getString("class") == "com.greatwall.callring.ui.lockeddevice.LockScreenOverlayDeviceTest"
        if (!arguments.containsKey("class")) {
            arguments.putString("notAnnotation", com.greatwall.callring.ui.lockeddevice.RequiresLockedDevice::class.java.name)
        }
        super.onCreate(arguments)
    }
    override fun onStart() {
        @Suppress("DEPRECATION")
        val display = targetContext.getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "callring-ui-tests:display")
        display.acquire(10 * 60_000L)
        try {
            check(lockScreenRun || !targetContext.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
                "Unlock the test device before running UI instrumentation"
            }
            super.onStart()
        } finally { if (display.isHeld) display.release() }
    }
}
