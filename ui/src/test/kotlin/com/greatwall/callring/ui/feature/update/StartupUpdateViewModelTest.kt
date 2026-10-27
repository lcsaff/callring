package com.greatwall.callring.ui.feature.update

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupUpdateViewModelTest {
    private val current = AppVersionInfo("0.3.0", 3)
    private val release = AppReleaseInfo("0.4.0", 4, null, "notes", "update.apk", 1, "sha", "https://example.com/apk")
    private val available = UpdateStatus(current, UpdatePhase.UPDATE_AVAILABLE, release)

    private class Fake(private val initial: UpdateStatus, val result: suspend () -> ApiResult<UpdateStatus>) : AppUpdateApi {
        var checks = 0
        override suspend fun status() = ApiResult.Success(initial)
        override suspend fun check(): ApiResult<UpdateStatus> { checks++; return result() }
        override suspend fun download(): ApiResult<UpdateStatus> = error("must be manual")
        override suspend fun cancel(): ApiResult<UpdateStatus> = error("must be manual")
        override suspend fun install(): ApiResult<UpdateStatus> = error("must be manual")
    }

    @Test fun checksOnceAndDismissalStaysConsumed() = runTest {
        val api = Fake(UpdateStatus(current)) { ApiResult.Success(available) }
        val model = StartupUpdateViewModel(api, backgroundScope)
        runCurrent()
        assertEquals(release, model.release.value)
        model.dismiss()
        runCurrent()
        assertNull(model.release.value)
        assertEquals(1, api.checks)
    }

    @Test fun nonAvailablePhasesAndFailuresStaySilent() = runTest {
        for (phase in UpdatePhase.entries.filter { it != UpdatePhase.UPDATE_AVAILABLE }) {
            val model = StartupUpdateViewModel(Fake(UpdateStatus(current)) {
                ApiResult.Success(available.copy(phase = phase))
            }, backgroundScope)
            runCurrent()
            assertNull("phase=$phase", model.release.value)
        }
        for (result in listOf<suspend () -> ApiResult<UpdateStatus>>(
            { ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "update.check_failed")) },
            { throw IllegalStateException("network unavailable") },
        )) {
            val model = StartupUpdateViewModel(Fake(UpdateStatus(current), result), backgroundScope)
            runCurrent()
            assertNull(model.release.value)
        }
    }

    @Test fun doesNotInterruptDownloadVerificationOrPendingInstallation() = runTest {
        for (phase in listOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING, UpdatePhase.VERIFIED,
                UpdatePhase.INSTALL_PERMISSION_REQUIRED, UpdatePhase.INSTALL_PENDING_VERIFY, UpdatePhase.UNSUPPORTED_DEVICE)) {
            val api = Fake(available.copy(phase = phase)) { error("unexpected check") }
            val model = StartupUpdateViewModel(api, backgroundScope)
            runCurrent()
            assertEquals(0, api.checks)
            assertNull(model.release.value)
        }
    }

    @Test fun cancelledOwnerCannotPublishLateResponse() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val result = CompletableDeferred<ApiResult<UpdateStatus>>()
        val model = StartupUpdateViewModel(Fake(UpdateStatus(current)) {
            withContext(NonCancellable) { result.await() }
        }, owner)
        runCurrent()
        owner.cancel()
        result.complete(ApiResult.Success(available))
        runCurrent()
        assertNull(model.release.value)
    }
}
