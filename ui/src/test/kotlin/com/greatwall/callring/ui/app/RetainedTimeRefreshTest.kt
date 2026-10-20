package com.greatwall.callring.ui.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.ui.state.SnapshotViewModel
import com.greatwall.callring.ui.testing.TestChangeFeed
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RetainedTimeRefreshTest {
    @get:Rule val compose = createComposeRule()

    @Test fun navigationRecreatesCompositionButRetainedQueryCatchesMissedBoundaryAndInterval() {
        val clock = MutableClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var reads = 0
        val feed = TestChangeFeed()
        val model = SnapshotViewModel(feed, setOf(DataDomain.WORK_CALENDAR),
            load = { ApiResult.Success(++reads) }, testScope = scope, clock = clock)
        var visible by mutableStateOf(true)
        try {
            compose.setContent {
                if (visible) TimeRefresh(model.timeRefresh, 0, nextBoundary = 5000, intervalMillis = 60_000, refresh = model::refresh)
            }
            compose.runOnIdle { assertEquals("initial feed owns exactly one load", 1, reads); visible = false }
            compose.waitForIdle()
            clock.now = 2000
            compose.runOnIdle { visible = true }
            compose.runOnIdle { assertEquals("short navigation return does not reload", 1, reads); visible = false }
            compose.waitForIdle()
            clock.now = 6000
            compose.runOnIdle { visible = true }
            compose.runOnIdle { assertEquals("retained data crossed its work boundary while disposed", 2, reads); visible = false }
            compose.waitForIdle()
            clock.now = 70_000
            compose.runOnIdle { visible = true }
            compose.runOnIdle { assertEquals("elapsed interval survives another composition recreation", 3, reads); visible = false }
            compose.waitForIdle()
            clock.now = 120_000
            compose.runOnIdle { feed.publish(setOf(DataDomain.WORK_CALENDAR)) }
            compose.runOnIdle { assertEquals(4, reads) }
            clock.now = 121_000
            compose.runOnIdle { visible = true }
            compose.runOnIdle { assertEquals("a successful feed read calibrates the retained time baseline", 4, reads) }
        } finally { scope.cancel() }
    }

    private class MutableClock(var now: Long = 1000) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }
}
