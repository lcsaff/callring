package com.greatwall.callring.ui.overlay

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OverlayViewModelTest {
    @Test fun overviewUsesTheRequestTimestampAndRetainsOriginalDecision() = runTest {
        var received: OverviewQuery? = null
        val api = object : UiFakeApi() {
            override suspend fun getOverview(query: OverviewQuery): ApiResult<CallerOverview> {
                received = query
                return super.getOverview(query)
            }
        }
        val request = IncomingCallUiRequest("session", "100", 1234, CallDecision.QUIET_SILENCED)
        val model = OverlayViewModel(request, api, backgroundScope)
        runCurrent()
        assertEquals(OverviewQuery("100", 1234), received)
        assertEquals(CallDecision.QUIET_SILENCED, model.request.decision)
        assertEquals(80, model.overview.value.data?.rollingScore?.points)
    }

    @Test fun whitelistOperationSubmitsTheExplicitTargetValue() = runTest {
        var target: Boolean? = null
        val api = object : UiFakeApi() {
            override suspend fun setWhitelisted(number: String, enabled: Boolean): ApiResult<CallerProfile> {
                target = enabled
                return super.setWhitelisted(number, enabled)
            }
        }
        val model = OverlayViewModel(IncomingCallUiRequest("session", "100", 0, CallDecision.NORMAL_RINGING), api, backgroundScope)
        runCurrent()
        model.whitelist(true); runCurrent()
        assertEquals(true, target)
        assertTrue(model.overview.value.data!!.profile.whitelisted)
    }
}
