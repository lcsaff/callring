package com.greatwall.callring.ui.testing

import android.os.PowerManager
import androidx.test.runner.AndroidJUnitRunner

/** Keep the isolated UI test activity visible across scenario transitions. */
class UiTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        @Suppress("DEPRECATION")
        val display = targetContext.getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "callring-ui-tests:display")
        display.acquire(10 * 60_000L)
        try {
            check(!targetContext.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
                "Unlock the test device before running UI instrumentation"
            }
            super.onStart()
        } finally { if (display.isHeld) display.release() }
    }
}
