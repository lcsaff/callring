package com.greatwall.callring.ui.feature.update

import com.greatwall.callring.application.api.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    private val current = AppVersionInfo("0.3.0", 3)
    private val release = AppReleaseInfo("0.4.0", 4, 0, "notes", "callring-0.4.0.apk", 1, "sha", "https://example.com/apk")
    private fun status(phase: UpdatePhase) = UpdateStatus(current, phase)

    private class Fake(
        var onStatus: suspend () -> ApiResult<UpdateStatus>,
        var onCheck: suspend () -> ApiResult<UpdateStatus> = { ApiResult.Success(UpdateStatus(AppVersionInfo("0.3.0", 3))) },
        var onDownload: suspend () -> ApiResult<UpdateStatus> = { ApiResult.Success(UpdateStatus(AppVersionInfo("0.3.0", 3))) },
        var onCancel: suspend () -> ApiResult<UpdateStatus> = { ApiResult.Success(UpdateStatus(AppVersionInfo("0.3.0", 3))) },
        var onInstall: suspend () -> ApiResult<UpdateStatus> = { ApiResult.Success(UpdateStatus(AppVersionInfo("0.3.0", 3))) },
    ) : AppUpdateApi {
        var checks = 0
        var downloads = 0
        var cancels = 0
        var installs = 0
        override suspend fun status() = onStatus()
        override suspend fun check(): ApiResult<UpdateStatus> { checks++; return onCheck() }
        override suspend fun download(): ApiResult<UpdateStatus> { downloads++; return onDownload() }
        override suspend fun cancel(): ApiResult<UpdateStatus> { cancels++; return onCancel() }
        override suspend fun install(): ApiResult<UpdateStatus> { installs++; return onInstall() }
    }

    @Test fun checkPublishesTheReturnedStatus() = runTest {
        val api = Fake(onStatus = { ApiResult.Success(status(UpdatePhase.IDLE)) },
            onCheck = { ApiResult.Success(status(UpdatePhase.UPDATE_AVAILABLE).copy(latest = release)) })
        val model = AppUpdateViewModel(api, backgroundScope)
        model.refresh(); runCurrent()
        assertEquals(UpdatePhase.IDLE, model.state.value.data?.phase)
        model.check(); runCurrent()
        assertEquals(1, api.checks)
        assertEquals(UpdatePhase.UPDATE_AVAILABLE, model.state.value.data?.phase)
        assertEquals(release, model.state.value.data?.latest)
    }

    @Test fun downloadCancelAndInstallEachDriveOneApiCall() = runTest {
        val api = Fake(onStatus = { ApiResult.Success(status(UpdatePhase.UPDATE_AVAILABLE)) },
            onDownload = { ApiResult.Success(status(UpdatePhase.DOWNLOADING)) },
            onCancel = { ApiResult.Success(status(UpdatePhase.UPDATE_AVAILABLE)) },
            onInstall = { ApiResult.Success(status(UpdatePhase.INSTALL_PENDING_VERIFY)) })
        val model = AppUpdateViewModel(api, backgroundScope)
        model.refresh(); runCurrent()
        model.download(); runCurrent()
        assertEquals(UpdatePhase.DOWNLOADING, model.state.value.data?.phase)
        model.cancel(); runCurrent()
        assertEquals(UpdatePhase.UPDATE_AVAILABLE, model.state.value.data?.phase)
        model.install(); runCurrent()
        assertEquals(UpdatePhase.INSTALL_PENDING_VERIFY, model.state.value.data?.phase)
        assertEquals(1, api.downloads)
        assertEquals(1, api.cancels)
        assertEquals(1, api.installs)
    }

    @Test fun failureKeepsTheLastStatusAndReportsTheReason() = runTest {
        val api = Fake(onStatus = { ApiResult.Success(status(UpdatePhase.IDLE)) },
            onCheck = { ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "update.unavailable")) })
        val model = AppUpdateViewModel(api, backgroundScope)
        model.refresh(); runCurrent()
        model.check(); runCurrent()
        assertEquals(UpdatePhase.IDLE, model.state.value.data?.phase)
        assertEquals("update.unavailable", model.mutation.value.error?.message)
    }

    @Test fun statusReadFailureIsExposedWithoutInventingAPhase() = runTest {
        val api = Fake(onStatus = { ApiResult.Failure(ApiError(ErrorCode.SYSTEM_UNAVAILABLE, "update.unavailable")) })
        val model = AppUpdateViewModel(api, backgroundScope)
        model.refresh(); runCurrent()
        assertNull(model.state.value.data)
        assertEquals("update.unavailable", model.state.value.error?.message)
    }
}
