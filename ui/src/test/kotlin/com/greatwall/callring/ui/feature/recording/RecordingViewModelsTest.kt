package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.application.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecordingViewModelsTest {
    @Test fun identityAdoptionIsExplicitAndDelegatesArchiveGenerationToSharedCommand() = runTest {
        val fake = com.greatwall.callring.ui.testing.UiFakeApi()
        var command: Pair<Long,Long>? = null
        val callers = object : CallersApi by fake {
            override suspend fun getEditor(number: String?) = ApiResult.Success(CallerEditorData(
                CallerDraft("100", "old", "old unit", 42, true), emptyList(), null))
            override suspend fun saveProfile(value: SaveCallerProfile): ApiResult<CallerProfile> {
                error("UI must delegate to shared adoption, not save the profile itself")
            }
        }
        val recordings = object : RecordingApi by UnavailableRecordingApi {
            override suspend fun getArchive(callId: Long): ApiResult<CallArchive?> = ApiResult.Success(CallArchive(
                callId, "100", 0, analysis = CallAnalysis(name = "self reported", organization = "new unit", position = "CEO", inferredIdentity = "inferred")))
        }
        val model = ArchiveViewModel(recordings, callers, 1, "100", 0, this, adoptCommand = { id, generation -> command = id to generation; fake.saveProfile(SaveCallerProfile("100","self reported","new unit",42,true)) })
        model.refresh(); runCurrent()
        assertNull(command)
        model.adoptIdentity(); runCurrent()
        assertEquals(1L to 0L, command)
    }
    @Test fun exitStopsEvenWhilePlayIsPendingAndViewModelScopeIsCancelled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val playbackScope = CoroutineScope(SupervisorJob() + dispatcher)
        val playPending = CompletableDeferred<Unit>()
        val stopPending = CompletableDeferred<Unit>()
        var stopStarted = false
        var stopFinished = false
        val api = object : RecordingApi by UnavailableRecordingApi {
            override suspend fun play(callId: Long): ApiResult<Unit> {
                playPending.await(); return ApiResult.Success(Unit)
            }
            override suspend fun stop(): ApiResult<Unit> {
                stopStarted = true
                stopPending.await()
                stopFinished = true
                return ApiResult.Success(Unit)
            }
        }
        val model = ArchiveViewModel(api, com.greatwall.callring.ui.testing.UiFakeApi(), 1, "100", 0,
            playbackScope, dispatcher)
        model.play(); runCurrent()
        assertTrue(model.mutation.value.busy)
        val cleanup = model.stopForExit()
        playbackScope.cancel()
        runCurrent()
        assertTrue(stopStarted)
        assertFalse(stopFinished)
        stopPending.complete(Unit)
        cleanup.join()
        assertTrue(stopFinished)
    }}
