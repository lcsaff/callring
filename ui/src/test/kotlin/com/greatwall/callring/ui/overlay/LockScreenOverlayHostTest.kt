package com.greatwall.callring.ui.overlay

import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LockScreenOverlayHostTest {
    @Test fun releasingOldServiceCannotCloseNewServicesCard() = runTest {
        val old = OverlayController(UiFakeApi(true), OverlaySessions(), backgroundScope)
        val next = OverlayController(UiFakeApi(true), OverlaySessions(), backgroundScope)
        try {
            LockScreenOverlayHost.show(old)
            LockScreenOverlayHost.show(next)
            LockScreenOverlayHost.hide(old)
            assertSame(next, LockScreenOverlayHost.controller.value)
            LockScreenOverlayHost.hide(next)
            assertNull(LockScreenOverlayHost.controller.value)
        } finally {
            LockScreenOverlayHost.hide(next)
            old.close(); next.close()
        }
    }

    @Test fun switchingBackToOrdinaryWindowInvalidatesLockScreenBinding() = runTest {
        val controller = OverlayController(UiFakeApi(true), OverlaySessions(), backgroundScope)
        try {
            LockScreenOverlayHost.show(controller)
            assertSame(controller, LockScreenOverlayHost.controller.value)
            LockScreenOverlayHost.hide(controller)
            assertNull(LockScreenOverlayHost.controller.value)
        } finally { LockScreenOverlayHost.hide(controller); controller.close() }
    }
}
