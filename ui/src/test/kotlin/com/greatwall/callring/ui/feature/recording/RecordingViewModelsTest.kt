package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecordingViewModelsTest {
    @Test fun exitStopsEvenWhilePlayIsPendingAndViewModelScopeIsCancelled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val playbackScope = CoroutineScope(SupervisorJob() + dispatcher)
        val playPending = CompletableDeferred<Unit>()
        val stopPending = CompletableDeferred<Unit>()
        var stopStarted = false
        var stopFinished = false
        val api = object : RecordingApi by FixtureRecordingApi {
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
        val model = ArchiveViewModel(api, 1, "100", 0,
            playbackScope, dispatcher, changes = TestChangeFeed())
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
