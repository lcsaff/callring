package com.greatwall.callring.ui.app

import android.Manifest
import android.app.Application
import android.os.Looper
import com.greatwall.callring.application.api.ApiResult
import com.greatwall.callring.application.api.SyncRequestReceipt
import com.greatwall.callring.application.api.SyncRequestState
import com.greatwall.callring.ui.testing.UiFakeApi
import org.junit.Assert.assertEquals
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
    override val uiApi = PermissionRecoveryApi()
}

class PermissionRecoveryApi : UiFakeApi() {
    var syncRequests = 0
    override suspend fun requestCallLogSync(): ApiResult<SyncRequestReceipt> {
        syncRequests++
        return ApiResult.Success(SyncRequestReceipt(SyncRequestState.QUEUED, 0))
    }
}
