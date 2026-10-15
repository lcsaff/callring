package com.greatwall.callring.ui.overlay

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayControllerTest {
    @Test fun runtimeFailureAfterRingingCannotKeepTheCardOpenForever() = runTest {
        val fixture = UiFakeApi(true)
        var reads = 0
        val api = object : CallringUiApi by fixture {
            override val runtime = object : RuntimeApi by fixture {
                override suspend fun getStatus(): ApiResult<RuntimeStatus> = if (++reads == 1)
                    ApiResult.Success(fixture.getStatus().value.copy(phoneActivity = PhoneActivity.RINGING))
                else ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "状态读取失败"))
            }
        }
        val sessions = OverlaySessions({ 1000 }, { testScheduler.currentTime })
        val controller = OverlayController(api, sessions, backgroundScope)
        controller.show(IncomingCallUiRequest("one", "100", 1000, CallDecision.NORMAL_RINGING)); runCurrent()
        assertNotNull(sessions.state.value)
        advanceTimeBy(5_001); runCurrent()
        assertNull(sessions.state.value)
    }
    @Test fun incomingSessionNeverReplacesAnOpenDraft() = runTest {
        var now = 1000L
        val sessions = OverlaySessions({ now }, { testScheduler.currentTime })
        val api = object : UiFakeApi(true) {
            override suspend fun getStatus() = ApiResult.Success(super.getStatus().value.copy(phoneActivity = PhoneActivity.RINGING))
        }
        val controller = OverlayController(api, sessions, backgroundScope)
        controller.show(IncomingCallUiRequest("one", "100", now, CallDecision.QUIET_SILENCED)); runCurrent()
        controller.beginEditing(); runCurrent()
        controller.changeDraft(controller.models.value!!.editor!!.draft.value!!.copy(name = "未保存"))
        now++
        controller.show(IncomingCallUiRequest("two", "100", now, CallDecision.NORMAL_RINGING)); runCurrent()
        assertEquals("未保存", controller.models.value!!.editor!!.draft.value!!.name)
        assertEquals("one", controller.models.value!!.presentation.request.sessionId)
        sessions.phone(PhoneActivity.RINGING)
        controller.cancelEditing(); runCurrent()
        assertEquals("two", controller.models.value!!.presentation.request.sessionId)
        assertNull(controller.models.value!!.editor)
    }

    @Test fun lostPhonePermissionClosesTheSession() = runTest {
        val api = object : UiFakeApi(true) {
            override suspend fun getStatus() = ApiResult.Success(super.getStatus().value.copy(
                capabilities = Capability.entries.associateWith { if (it == Capability.PHONE_STATE) AccessState.DENIED else AccessState.GRANTED }))
        }
        val sessions = OverlaySessions({ 1000 }, { testScheduler.currentTime })
        val controller = OverlayController(api, sessions, backgroundScope)
        controller.show(IncomingCallUiRequest("one", "100", 1000, CallDecision.NORMAL_RINGING)); runCurrent()
        assertNull(sessions.state.value)
        assertNull(controller.models.value)
    }

    @Test fun lateRuntimeFromDisposedSessionCannotCloseTheNewOne() = runTest {
        val delayed = CompletableDeferred<RuntimeStatus>()
        var requests = 0
        val api = object : UiFakeApi(true) {
            override suspend fun getStatus(): ApiResult.Success<RuntimeStatus> {
                requests++
                return if (requests == 1) withContext(NonCancellable) { ApiResult.Success(delayed.await()) }
                else ApiResult.Success(super.getStatus().value.copy(phoneActivity = PhoneActivity.RINGING))
            }
        }
        val status = UiFakeApi().getStatus().value.copy(phoneActivity = PhoneActivity.ACTIVE)
        val sessions = OverlaySessions({ 1001 }, { testScheduler.currentTime })
        val controller = OverlayController(api, sessions, backgroundScope)
        controller.show(IncomingCallUiRequest("one", "100", 1000, CallDecision.NORMAL_RINGING)); runCurrent()
        controller.show(IncomingCallUiRequest("two", "100", 1001, CallDecision.QUIET_SILENCED)); runCurrent()
        delayed.complete(status); runCurrent()
        assertEquals("two", sessions.state.value?.request?.sessionId)
        assertEquals("two", controller.models.value?.presentation?.request?.sessionId)
    }

    @Test fun successfulSaveAfterCallEndClosesEditorWithoutLosingWrite() = runTest {
        val api = UiFakeApi(true)
        val sessions = OverlaySessions({ 1000 }, { testScheduler.currentTime })
        val controller = OverlayController(api, sessions, backgroundScope)
        controller.show(IncomingCallUiRequest("one", "100", 1000, CallDecision.NORMAL_RINGING)); runCurrent()
        controller.beginEditing(); runCurrent()
        controller.changeDraft(controller.models.value!!.editor!!.draft.value!!.copy(name = "已保存"))
        sessions.phone(PhoneActivity.ACTIVE)
        controller.save(); runCurrent()
        assertEquals("已保存", api.savedProfile?.name)
        assertNull(api.savedProfile?.whitelisted)
        assertNull(sessions.state.value)
        assertEquals(1L, controller.savedRevision.value)
        controller.cancelEditing(); runCurrent()
        assertEquals(1L, controller.savedRevision.value)
    }
}
