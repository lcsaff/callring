package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RingtoneViewModelTest {
    @Test fun leavingBeforeQueuedPreviewStartsPreventsPlaybackAndReentryDoesNotResume() = runTest {
        var starts = 0; var stops = 0
        val api = object : UiFakeApi() {
            override suspend fun startPreview(): ApiResult<Unit> { starts++; return ApiResult.Success(Unit) }
            override suspend fun stopPreview(): ApiResult<Unit> { stops++; return ApiResult.Success(Unit) }
        }
        val model = RingtoneViewModel(api, backgroundScope)
        model.enter(); model.preview(); model.leave(); runCurrent()
        assertEquals(0, starts); assertEquals(1, stops)
        model.enter(); runCurrent(); assertEquals(0, starts)
        model.preview(); runCurrent(); assertEquals(1, starts)
        model.leave(); runCurrent(); assertEquals(2, stops)
    }

    @Test fun leavingDuringSuspendedStartStopsItsLatePlaybackAndNeverUsesGlobalStop() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var playing = false; var stops = 0
        val api = object : UiFakeApi() {
            override suspend fun startPreview(): ApiResult<Unit> {
                entered.complete(Unit); release.await(); playing = true
                return ApiResult.Success(Unit)
            }
            override suspend fun stopPreview(): ApiResult<Unit> { playing = false; stops++; return ApiResult.Success(Unit) }
            override suspend fun stopPlayback(): ApiResult<Unit> = error("must preserve real call playback")
        }
        val model = RingtoneViewModel(api, backgroundScope)
        model.enter(); model.preview(); runCurrent(); assertTrue(entered.isCompleted)
        model.leave(); runCurrent(); release.complete(Unit); runCurrent()
        assertFalse(playing); assertEquals(1, stops)
        model.enter(); runCurrent(); assertFalse(playing)
    }
}
