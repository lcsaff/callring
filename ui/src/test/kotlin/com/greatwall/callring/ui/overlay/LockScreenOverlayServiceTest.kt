package com.greatwall.callring.ui.overlay

import android.app.Application
import android.app.KeyguardManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.greatwall.callring.ui.testing.UiFakeApi
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.runtime.PhoneActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

class LockScreenTestApplication : Application(), UiOverlayOwner {
    override val overlaySessions = OverlaySessions()
    override val uiApi = object : UiFakeApi(true) {

        override suspend fun getStatus() = ApiResult.Success(super.getStatus().value.copy(phoneActivity = PhoneActivity.RINGING))
    }
    override val availableUiApi = kotlinx.coroutines.flow.MutableStateFlow<com.greatwall.callring.contracts.CallringUiApi?>(uiApi)
    override suspend fun reobservePlatform() = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LockScreenTestApplication::class)
@Suppress("DEPRECATION")
class LockScreenOverlayServiceTest {
    @Test fun answeredCallHidesLockedEditorWithoutDiscardingItsDraft() {
        val app = ApplicationProvider.getApplicationContext<LockScreenTestApplication>()
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val service = Robolectric.buildService(CallringOverlayService::class.java).create()
        try {
            service.get().onStartCommand(CallringOverlayService.intent(app,
                IncomingCallUiRequest("live", "100", System.currentTimeMillis(), CallDecision.NORMAL_RINGING)), 0, 1)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            val controller = requireNotNull(LockScreenOverlayHost.controller.value)
            controller.beginEditing()
            app.overlaySessions.phone(PhoneActivity.ACTIVE)
            reconcile(service.get())
            assertTrue(app.overlaySessions.state.value!!.editing)
            assertNull(LockScreenOverlayHost.controller.value)
        } finally { service.destroy() }
    }

    @Test fun lockedPreviewUsesActivityAndUnlockInvalidatesBinding() {
        val app = ApplicationProvider.getApplicationContext<LockScreenTestApplication>()
        ShadowSettings.setCanDrawOverlays(true)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        val service = Robolectric.buildService(CallringOverlayService::class.java).create()
        try {
            service.get().onStartCommand(CallringOverlayService.testPreviewIntent(app), 0, 1)
            assertNotNull(LockScreenOverlayHost.controller.value)
            assertEquals(LockScreenOverlayActivity::class.java.name, shadowOf(app).nextStartedActivity.component!!.className)
            keyguard.setKeyguardLocked(false)
            reconcile(service.get())
            assertNull(LockScreenOverlayHost.controller.value)
        } finally { service.destroy() }
    }

    @Test fun screenOffAndDestroyedServiceReleaseLockScreenWindow() {
        val app = ApplicationProvider.getApplicationContext<LockScreenTestApplication>()
        ShadowSettings.setCanDrawOverlays(true)
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        power.setIsInteractive(true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        val service = Robolectric.buildService(CallringOverlayService::class.java).create()
        try {
            service.get().onStartCommand(CallringOverlayService.testPreviewIntent(app), 0, 1)
            assertNotNull(LockScreenOverlayHost.controller.value)
            power.setIsInteractive(false)
            reconcile(service.get())
            assertNull(LockScreenOverlayHost.controller.value)
            power.setIsInteractive(true)
            reconcile(service.get())
            assertNotNull(LockScreenOverlayHost.controller.value)
        } finally { service.destroy() }
        assertNull(LockScreenOverlayHost.controller.value)
    }

    @Test fun activityWithoutLiveServiceBindingImmediatelyFinishes() {
        val activity = Robolectric.buildActivity(LockScreenOverlayActivity::class.java).create()
        try { assertTrue(activity.get().isFinishing) } finally { activity.destroy() }
    }

    private fun reconcile(service: CallringOverlayService) {
        CallringOverlayService::class.java.getDeclaredMethod("reconcileWindow").apply { isAccessible = true }.invoke(service)
    }
}
