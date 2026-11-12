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
    @Test fun exitStopsActiveSessionWhileCancellingItsReplacement() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val replacement = CompletableDeferred<Unit>()
        var active: RecordingPlaybackSession? = null
        val stops = mutableListOf<String>()
        val api = object : RecordingApi by FixtureRecordingApi {
            override suspend fun getPlaybackSession(): ApiResult<RecordingPlaybackSession?> = ApiResult.Success(active)
            override suspend fun playOwned(callId: Long, token: String): ApiResult<Unit> {
                if (callId == 2L) replacement.await()
                active = RecordingPlaybackSession(callId, token)
                return ApiResult.Success(Unit)
            }
            override suspend fun stopPlaybackSession(token: String): ApiResult<Unit> {
                stops += token
                if (active?.token == token) active = null
                return ApiResult.Success(Unit)
            }
        }
        val playback = RecordingPlaybackState(api, TestChangeFeed(), backgroundScope, dispatcher)
        playback.toggle(1)
        runCurrent()
        val first = requireNotNull(active)
        playback.toggle(2)
        runCurrent()
        val cleanup = requireNotNull(playback.stopForExit())
        runCurrent()
        cleanup.join()
        replacement.complete(Unit)
        runCurrent()
        assertNull(active)
        assertEquals(2, stops.size)
        assertEquals(first.token, stops.last())
    }

    @Test fun exitStopsEvenWhilePlayIsPendingAndViewModelScopeIsCancelled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val playbackScope = CoroutineScope(SupervisorJob() + dispatcher)
        val playPending = CompletableDeferred<Unit>()
        val stopPending = CompletableDeferred<Unit>()
        var stopStarted = false
        var stopFinished = false
        val api = object : RecordingApi by FixtureRecordingApi {
            override suspend fun playOwned(callId: Long, token: String): ApiResult<Unit> {
                playPending.await(); return ApiResult.Success(Unit)
            }
            override suspend fun stopPlaybackSession(token: String): ApiResult<Unit> {
                stopStarted = true
                stopPending.await()
                stopFinished = true
                return ApiResult.Success(Unit)
            }
        }
        val model = ArchiveViewModel(api, 1, "100", 0,
            playbackScope, dispatcher, changes = TestChangeFeed())
        model.togglePlayback(); runCurrent()
        assertTrue(model.playback.mutation.value.busy)
        val cleanup = requireNotNull(model.playback.stopForExit())
        playbackScope.cancel()
        runCurrent()
        assertTrue(stopStarted)
        assertFalse(stopFinished)
        stopPending.complete(Unit)
        cleanup.join()
        assertTrue(stopFinished)
    }}
