package com.greatwall.callring.ui.app

import com.greatwall.callring.ui.app.bridge.UiApiOwner

import com.greatwall.callring.contracts.runtime.*
import android.Manifest
import android.app.Application
import android.os.Looper
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.runtime.SyncRequestReceipt
import com.greatwall.callring.contracts.runtime.SyncRequestState
import com.greatwall.callring.ui.testing.UiFakeApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PermissionRecoveryApplication::class)
class CallringActivityTest {
    @Test fun recordingArchiveListExternalIntentKeepsIndependentDestination() {
        val intent = CallringActivity.intent(RuntimeEnvironment.getApplication(), UiDestination.RecordingArchives)
        assertEquals("recording-archives", intent.getStringExtra("callring.ui.target"))
        assertEquals(UiDestination.RecordingArchives, UiDestination.fromExternal(intent.getStringExtra("callring.ui.target"), null))
    }

    @Test fun invalidReportDeepLinkFallsBackToLookupInsteadOfABrokenReport() {
        val controller = Robolectric.buildActivity(CallringActivity::class.java,
            CallringActivity.intent(RuntimeEnvironment.getApplication(), UiDestination.Report("999"))).setup()
        try {
            assertEquals(UiDestination.Lookup, destinationOf(controller.get()))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun validReportDeepLinkOpensTheNormalizedNumberReport() {
        val controller = Robolectric.buildActivity(CallringActivity::class.java,
            CallringActivity.intent(RuntimeEnvironment.getApplication(), UiDestination.Report("100"))).setup()
        try {
            assertEquals(UiDestination.Report("100"), destinationOf(controller.get()))
        } finally { controller.pause().stop().destroy() }
    }

    private fun destinationOf(activity: CallringActivity): UiDestination? {
        val field = CallringActivity::class.java.declaredFields.firstOrNull {
            it.name.startsWith("destination") && androidx.compose.runtime.MutableState::class.java.isAssignableFrom(it.type)
        } ?: error("activity destination state field not found")
        field.isAccessible = true
        return (field.get(activity) as androidx.compose.runtime.MutableState<*>).value as UiDestination?
    }

    @Test fun overlayTestWithoutPermissionOpensOverlaySettingsInsteadOfService() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        val controller = Robolectric.buildActivity(CallringActivity::class.java).setup()
        try {
            val action = CallringActivity::class.java.declaredMethods.singleOrNull { it.name == "testOverlay" }
            assertNotNull("Runtime permission screen needs a direct overlay self-test", action)
            action!!.apply { isAccessible = true }.invoke(controller.get())
            val intent = shadowOf(controller.get()).nextStartedActivity
            assertNotNull(intent)
            assertEquals(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, intent.action)
            assertEquals("package:${controller.get().packageName}", intent.dataString)
            assertNull(shadowOf(RuntimeEnvironment.getApplication() as Application).nextStartedService)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun overlayTestWithPermissionStartsOnlyTheExplicitTestPreview() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildActivity(CallringActivity::class.java).setup()
        try {
            val action = CallringActivity::class.java.declaredMethods.singleOrNull { it.name == "testOverlay" }
            assertNotNull("Runtime permission screen needs a direct overlay self-test", action)
            action!!.apply { isAccessible = true }.invoke(controller.get())
            val intent = shadowOf(RuntimeEnvironment.getApplication() as Application).nextStartedService
            assertNotNull(intent)
            assertTrue(intent.getBooleanExtra("testPreview", false))
            assertEquals("com.greatwall.callring.ui.overlay.CallringOverlayService", intent.component?.className)
            assertNull(shadowOf(controller.get()).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun resumeAfterExternalCallLogGrantRequestsSynchronizationThroughTheApi() {
        val application = RuntimeEnvironment.getApplication() as PermissionRecoveryApplication
        val controller = Robolectric.buildActivity(CallringActivity::class.java).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, application.uiApi.syncRequests)
            controller.pause().stop()
            shadowOf(application).grantPermissions(Manifest.permission.READ_CALL_LOG)
            controller.restart().start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, application.uiApi.syncRequests)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}

class PermissionRecoveryApplication : Application(), UiApiOwner {
    override suspend fun reobservePlatform() { uiApi.runtime.getStatus() }
    override val uiApi = PermissionRecoveryApi()
}

class PermissionRecoveryApi : UiFakeApi() {
    var syncRequests = 0
    override suspend fun requestCallLogSync(): ApiResult<SyncRequestReceipt> {
        syncRequests++
        return ApiResult.Success(SyncRequestReceipt(SyncRequestState.QUEUED, 0))
    }
}
