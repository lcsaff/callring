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
    @Test fun recreationDuringOwnerSwitchWaitsForAvailableApi() {
        val application = RuntimeEnvironment.getApplication() as PermissionRecoveryApplication
        application.availableUiApi.value = null
        var reads = 0
        application.uiApi.prepareRule = { reads++; ApiResult.Success(com.greatwall.callring.contracts.settings.NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("3", 5)), 0)) }
        val first = Robolectric.buildActivity(CallringActivity::class.java).create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, reads)
        val saved = android.os.Bundle()
        first.saveInstanceState(saved).pause().stop().destroy()
        val second = Robolectric.buildActivity(CallringActivity::class.java).create(saved).start().resume()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, reads)
            application.availableUiApi.value = application.uiApi
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, reads)
        } finally { second.pause().stop().destroy() }
    }

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

    private fun gateOf(activity: CallringActivity): NumberRuleStartupGate =
        CallringActivity::class.java.getDeclaredField("startupGate").apply { isAccessible = true }.get(activity) as NumberRuleStartupGate

    private fun destinationOf(activity: CallringActivity): UiDestination? {
        shadowOf(Looper.getMainLooper()).idle()
        return gateOf(activity).state.value.destination
    }

    @Test fun coldNavigationWaitsForRuleAndRetainsRawTargetOnFailure() {
        val application = RuntimeEnvironment.getApplication() as PermissionRecoveryApplication
        val pending = kotlinx.coroutines.CompletableDeferred<ApiResult<com.greatwall.callring.contracts.settings.NumberRuleSettings>>()
        application.uiApi.prepareRule = { pending.await() }
        val controller = Robolectric.buildActivity(CallringActivity::class.java,
            CallringActivity.intent(application, UiDestination.Report("100"))).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(destinationOf(controller.get()))
            assertEquals(0, application.uiApi.numberChecks)
            pending.complete(ApiResult.Failure(com.greatwall.callring.contracts.common.ApiError(
                com.greatwall.callring.contracts.common.ErrorCode.STORAGE_FAILURE, "storage.failure")))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(UiDestination.Report("100"), gateOf(controller.get()).state.value.pending)
            assertNotNull(gateOf(controller.get()).state.value.error)
            assertEquals(0, application.uiApi.numberChecks)
            application.uiApi.prepareRule = { ApiResult.Success(com.greatwall.callring.contracts.settings.NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("3", 5)), 0)) }
            gateOf(controller.get()).retry()
            assertEquals(UiDestination.Report("100"), destinationOf(controller.get()))
            assertTrue(application.uiApi.numberChecks > 0)
        } finally { controller.pause().stop().destroy() }
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
    override val availableUiApi = kotlinx.coroutines.flow.MutableStateFlow<com.greatwall.callring.contracts.CallringUiApi?>(uiApi)
}

class PermissionRecoveryApi : UiFakeApi() {
    var prepareRule: suspend () -> ApiResult<com.greatwall.callring.contracts.settings.NumberRuleSettings> = {
        ApiResult.Success(com.greatwall.callring.contracts.settings.NumberRuleSettings(listOf(com.greatwall.callring.contracts.settings.NumberRuleEntry("3", 5)), 0))
    }
    var numberChecks = 0
    override suspend fun getNumberRule() = prepareRule()
    override fun checkNumber(raw: String) = super.checkNumber(raw).also { numberChecks++ }

    var syncRequests = 0
    override suspend fun requestCallLogSync(): ApiResult<SyncRequestReceipt> {
        syncRequests++
        return ApiResult.Success(SyncRequestReceipt(SyncRequestState.QUEUED, 0))
    }
}
