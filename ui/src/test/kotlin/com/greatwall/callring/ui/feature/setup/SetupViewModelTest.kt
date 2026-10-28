package com.greatwall.callring.ui.feature.setup

import com.greatwall.callring.contracts.CallringUiApi
import com.greatwall.callring.contracts.ai.AiSettingsApi
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.runtime.*
import com.greatwall.callring.ui.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    @Test fun observationsRefreshOnlyForStatusAndEnabledFeatureDependencies() = runTest {
        val feed = TestChangeFeed()
        var reads = 0
        val fixture = UiFakeApi()
        var recording = RecordingSettingsView(false, false, CaptureMode.SYSTEM_ONLY, false, 0)
        val api = object : CallringUiApi by fixture {
            override val changes = feed
            override val runtime = object : RuntimeApi by fixture {
                override suspend fun getStatus() = fixture.getStatus().also { reads++ }
            }
            override val aiSettings = object : AiSettingsApi by FixtureAiSettingsApi {
                override suspend fun getRecordingView() = ApiResult.Success(recording)
            }
        }
        val model = SetupViewModel(api, backgroundScope)
        runCurrent()
        assertEquals(1, reads)
        feed.entity(DataDomain.RUNTIME, "diagnostics", "ringtonePlayback"); runCurrent()
        feed.publish(setOf(DataDomain.RECORDING_ARCHIVES, DataDomain.AI_SETTINGS)); runCurrent()
        assertEquals(1, reads)
        recording = recording.copy(recordingEnabled = true)
        feed.publish(setOf(DataDomain.RECORDING_SETTINGS)); runCurrent()
        assertEquals(2, reads)
        assertTrue(model.state.value.data!!.recording!!.recordingEnabled)
        feed.entity(DataDomain.RUNTIME, "recordingFolder"); runCurrent()
        assertEquals(3, reads)
        feed.entity(DataDomain.RUNTIME, "status"); runCurrent()
        assertEquals(4, reads)
        feed.publish(setOf(DataDomain.OVERLAY_SETTINGS)); runCurrent()
        assertEquals(5, reads)
    }

    @Test fun refreshKeepsOldObservationAndErrorsDoNotTurnUnknownOptionalSettingsIntoDisabled() = runTest {
        val fixture = UiFakeApi()
        var fail = false
        val gate = CompletableDeferred<Unit>()
        val api = object : CallringUiApi by fixture {
            override val runtime = object : RuntimeApi by fixture {
                override suspend fun getStatus(): ApiResult<RuntimeStatus> {
                    if (fail) { gate.await(); return ApiResult.Failure(ApiError(ErrorCode.INTERNAL, "状态读取失败")) }
                    return fixture.getStatus()
                }
            }
        }
        val model = SetupViewModel(api, backgroundScope)
        runCurrent()
        val loaded = model.state.value.data
        assertNotNull(loaded)
        assertNull(loaded!!.recording)
        assertNotNull(loaded.recordingError)
        fail = true
        model.refresh(); runCurrent()
        assertEquals(loaded, model.state.value.data)
        gate.complete(Unit); runCurrent()
        assertEquals(loaded, model.state.value.data)
        assertEquals("状态读取失败", model.state.value.error!!.message)
    }
}
