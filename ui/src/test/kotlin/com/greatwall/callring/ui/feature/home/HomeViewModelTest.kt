package com.greatwall.callring.ui.feature.home

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Test fun previewQueriesLatestCallAndDeliversItsOriginalFactsOnlyOnce() = runTest {
        val fact = ClassificationFact(true, WorkReason.WORKING, null, true, "Asia/Shanghai", 1, null, 1)
        val call = CallDetail(1, "100", 123, null, null, CallResult.UNKNOWN, CallDecision.QUIET_SILENCED,
            CallSource.SYSTEM_CALL_LOG, true, fact, fact, false, null)
        var reads = 0
        val api = object : UiFakeApi() {
            override suspend fun getLatestCall(): ApiResult<CallDetail?> { reads++; return ApiResult.Success(call) }
        }
        val model = HomeViewModel(api, backgroundScope)
        runCurrent()
        assertEquals(0, reads)
        model.previewLatest(); runCurrent()
        assertEquals(call, model.pendingPreview.value)
        model.consumePreview()
        assertNull(model.pendingPreview.value)
        model.previewLatest(); runCurrent()
        assertEquals(2, reads)
    }
    @Test fun noLatestCallIsAnExplicitLoadedEmptyResult() = runTest {
        val model = HomeViewModel(UiFakeApi(), backgroundScope)
        model.previewLatest(); runCurrent()
        assertTrue(model.latestPreview.value.loaded)
        assertNull(model.latestPreview.value.data)
        assertNull(model.pendingPreview.value)
    }
}
