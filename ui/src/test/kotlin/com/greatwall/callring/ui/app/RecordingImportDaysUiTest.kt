package com.greatwall.callring.ui.app

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.ui.testing.*
import com.greatwall.callring.ui.feature.ai.ScopedRecordingSettingsViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingImportDaysUiTest {
    @Test fun externalEditDuringDebounceConflictsWithOriginalDraftBaseline() = runTest {
        val feed = TestChangeFeed()
        var current = RecordingSettingsView(false,false,CaptureMode.SYSTEM_ONLY,false,0)
        val api = object : UiFakeApi() {
            override val changes = feed
            override val aiSettings = object : AiSettingsApi by FixtureAiSettingsApi {
                override suspend fun getRecordingView() = ApiResult.Success(current)
                override suspend fun patchRecording(base: RecordingSettingsView, patch: RecordingSettingsPatch): ApiResult<RecordingSettingsView> {
                    if(base.systemImportDays != current.systemImportDays) return ApiResult.Failure(ApiError(ErrorCode.CONFLICT,"conflict"))
                    current = current.copy(systemImportDays=(patch.systemImportDays as PatchField.Set).value,revision=current.revision+1)
                    return ApiResult.Success(current)
                }
            }
        }
        val model = ScopedRecordingSettingsViewModel(api,backgroundScope)
        runCurrent(); model.systemImportDays("30")
        current = current.copy(systemImportDays=14,revision=1)
        feed.publish(setOf(com.greatwall.callring.contracts.change.DataDomain.RECORDING_SETTINGS)); runCurrent()
        advanceTimeBy(500); runCurrent()
        assertEquals(14,current.systemImportDays)
        assertEquals("30",model.importDaysDraft.value)
        assertEquals(ErrorCode.CONFLICT,model.mutation.value.error?.code)
    }
    @Test fun delayedSaveCannotOverwriteNewerLoadedRevision() = runTest {
        val feed = TestChangeFeed()
        var current = RecordingSettingsView(false,false,CaptureMode.SYSTEM_ONLY,false,0)
        val release = CompletableDeferred<Unit>()
        val api = object : UiFakeApi() {
            override val changes = feed
            override val aiSettings = object : AiSettingsApi by FixtureAiSettingsApi {
                override suspend fun getRecordingView() = ApiResult.Success(current)
                override suspend fun patchRecording(base: RecordingSettingsView, patch: RecordingSettingsPatch): ApiResult<RecordingSettingsView> {
                    current = current.copy(systemImportDays=30,revision=1)
                    val saved = current
                    release.await()
                    return ApiResult.Success(saved)
                }
            }
        }
        val model = ScopedRecordingSettingsViewModel(api,backgroundScope)
        runCurrent(); model.systemImportDays("30"); advanceTimeBy(500); runCurrent()
        current = current.copy(systemImportDays=60,revision=2)
        feed.publish(setOf(com.greatwall.callring.contracts.change.DataDomain.RECORDING_SETTINGS)); runCurrent()
        assertEquals(60,model.state.value.data!!.systemImportDays)
        release.complete(Unit); runCurrent()
        assertEquals("60",model.importDaysDraft.value)
    }
    @Test fun changingImportDaysDoesNotRunUnrelatedAiLoader() = runTest {
        val feed = TestChangeFeed()
        var recordingLoads = 0; var aiLoads = 0
        val recording = com.greatwall.callring.ui.state.ObservedQuery(backgroundScope,feed,
            com.greatwall.callring.contracts.change.ChangeSubscription(setOf(com.greatwall.callring.contracts.change.DataDomain.RECORDING_SETTINGS)),load = { recordingLoads++; ApiResult.Success(7) })
        val ai = com.greatwall.callring.ui.state.ObservedQuery(backgroundScope,feed,
            com.greatwall.callring.contracts.change.ChangeSubscription(setOf(com.greatwall.callring.contracts.change.DataDomain.AI_SETTINGS)),load = { aiLoads++; ApiResult.Success(0) })
        recording.start(backgroundScope); ai.start(backgroundScope); runCurrent()
        assertEquals(1,recordingLoads); assertEquals(1,aiLoads)
        feed.publish(setOf(com.greatwall.callring.contracts.change.DataDomain.RECORDING_SETTINGS)); runCurrent()
        assertEquals(2,recordingLoads); assertEquals(1,aiLoads)
    }
    @Test fun daysDebounceSerializesLatestDraftAndRetainsInvalidOrConflictingInput() = runTest {
        var current = RecordingSettingsView(false, false, CaptureMode.SYSTEM_ONLY, false, 0)
        val requests = mutableListOf<Int>()
        var block: CompletableDeferred<Unit>? = null
        var conflict = false
        val api = object : UiFakeApi() {
            override val aiSettings = object : AiSettingsApi by FixtureAiSettingsApi {
                override suspend fun getRecordingView() = ApiResult.Success(current)
                override suspend fun patchRecording(base: RecordingSettingsView, patch: RecordingSettingsPatch): ApiResult<RecordingSettingsView> {
                    val days = (patch.systemImportDays as PatchField.Set).value
                    requests += days
                    block?.await()
                    if (conflict) return ApiResult.Failure(ApiError(ErrorCode.CONFLICT, "conflict"))
                    current = current.copy(systemImportDays = days, revision = current.revision + 1)
                    return ApiResult.Success(current)
                }
            }
        }
        val model = ScopedRecordingSettingsViewModel(api, backgroundScope)
        model.refresh(); runCurrent()
        model.systemImportDays("14"); advanceTimeBy(499); runCurrent(); assertTrue(requests.isEmpty())
        block = CompletableDeferred()
        advanceTimeBy(1); runCurrent(); assertEquals(listOf(14), requests)
        model.systemImportDays("30"); advanceTimeBy(500); runCurrent(); assertEquals(listOf(14), requests)
        assertEquals("30", model.importDaysDraft.value)
        block!!.complete(Unit); block = null; runCurrent()
        assertEquals(listOf(14, 30), requests)
        assertNull(model.mutation.value.message)
        model.systemImportDays("2147483648"); advanceTimeBy(500); runCurrent()
        assertEquals("2147483648", model.importDaysDraft.value)
        assertEquals(2, requests.size)
        conflict = true
        model.systemImportDays("60"); advanceTimeBy(500); runCurrent()
        assertEquals("60", model.importDaysDraft.value)
        assertEquals(ErrorCode.CONFLICT, model.mutation.value.error?.code)
    }
}
