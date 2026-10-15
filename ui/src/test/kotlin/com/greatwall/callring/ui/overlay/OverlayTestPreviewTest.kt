package com.greatwall.callring.ui.overlay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OverlayTestPreviewTest {
    @Test fun testIntentIsAnExplicitPreviewWithItsOwnIdentity() {
        val factory = CallringOverlayService.Companion.javaClass.methods.singleOrNull { it.name == "testPreviewIntent" }
        assertNotNull("A permission self-test must work without call history", factory)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = factory!!.invoke(CallringOverlayService.Companion, context) as android.content.Intent
        val second = factory.invoke(CallringOverlayService.Companion, context) as android.content.Intent
        assertEquals(CallringOverlayService::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra("preview", false))
        assertTrue(intent.getBooleanExtra("testPreview", false))
        assertFalse(intent.getStringExtra("session").isNullOrBlank())
        assertNotEquals(intent.getStringExtra("session"), second.getStringExtra("session"))
        assertEquals("100", intent.getStringExtra("number"))
        assertTrue(intent.getLongExtra("start", 0) > 0)
    }

    @Test fun testSessionCannotEditOrWhitelistAndDoesNotNeedLivePhoneState() = runTest {
        val api = UiFakeApi()
        val sessions = OverlaySessions()
        val controller = OverlayController(api, sessions, backgroundScope)
        val preview = OverlaySessions::class.java.methods.singleOrNull { it.name == "preview" && it.parameterCount == 2 }
        assertNotNull("Explicit test sessions need a read-only marker", preview)
        preview!!.invoke(sessions, IncomingCallUiRequest("test", "100", 1, CallDecision.NORMAL_RINGING), true)
        runCurrent()
        controller.beginEditing()
        controller.whitelist(true)
        sessions.phone(PhoneActivity.IDLE)
        runCurrent()
        assertNotNull(sessions.state.value)
        assertFalse(sessions.state.value!!.editing)
        assertNull(controller.models.value!!.editor)
        assertFalse(api.profile.whitelisted)
        assertNull(api.savedProfile)
    }
}
