package com.greatwall.callring.ui.state

import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.change.ChangeFeed
import com.greatwall.callring.contracts.change.DataChange
import com.greatwall.callring.contracts.change.DataDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Uses UI loaders and contract declarations only; no backend implementation or producer wiring. */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshIsolationTest {
    private class Feed : ChangeFeed {
        val events = MutableSharedFlow<DataChange>()
        override fun observe() = events
    }

    private class NumberSnapshot(
        changes: ChangeFeed,
        number: String,
        testScope: CoroutineScope,
    ) : UiViewModel(testScope) {
        var loads = 0
            private set
        private val loader = RequestLoader<String>(scope)
        val state = loader.state

        init {
            observeChanges(changes, setOf(DataDomain.CALLERS, DataDomain.POSITIONS), number, ::refresh)
        }

        fun refresh() = loader.load { ApiResult.Success("snapshot:${++loads}") }
    }

    @Test fun unrelatedNarrowDomainDoesNotInvokeSnapshotLoader() = runTest {
        val feed = Feed()
        var loads = 0
        val quiet = SnapshotViewModel(feed, setOf(DataDomain.QUIET_HOURS),
            load = { ApiResult.Success(++loads) }, testScope = backgroundScope)
        quiet.refresh()
        runCurrent()
        assertEquals(1, loads)

        feed.events.emit(DataChange(1, setOf(DataDomain.APPEARANCE), null, null))
        runCurrent()
        assertEquals(1, loads)
        assertEquals(1, quiet.state.value.data)

        feed.events.emit(DataChange(2, setOf(DataDomain.QUIET_HOURS), null, null))
        runCurrent()
        assertEquals(2, loads)
        assertEquals(2, quiet.state.value.data)
    }

    @Test fun numberedChangesIsolateAAndBWhileGlobalPositionChangesRefreshBoth() = runTest {
        val feed = Feed()
        val a = NumberSnapshot(feed, "100", backgroundScope)
        val b = NumberSnapshot(feed, "200", backgroundScope)
        a.refresh()
        b.refresh()
        runCurrent()

        feed.events.emit(DataChange(1, setOf(DataDomain.CALLERS), setOf("100"), null))
        runCurrent()
        assertEquals(2, a.loads)
        assertEquals(1, b.loads)
        assertEquals("snapshot:2", a.state.value.data)
        assertEquals("snapshot:1", b.state.value.data)

        feed.events.emit(DataChange(2, setOf(DataDomain.CALLERS), setOf("200"), null))
        runCurrent()
        assertEquals(2, a.loads)
        assertEquals(2, b.loads)

        // Empty means no matching numbered entity; null means every numbered entity.
        feed.events.emit(DataChange(3, setOf(DataDomain.CALLERS), emptySet(), null))
        runCurrent()
        assertEquals(2, a.loads)
        assertEquals(2, b.loads)
        feed.events.emit(DataChange(4, setOf(DataDomain.CALLERS), null, null))
        runCurrent()
        assertEquals(3, a.loads)
        assertEquals(3, b.loads)

        // A key attached to an event must not incorrectly narrow a global dependency.
        feed.events.emit(DataChange(5, setOf(DataDomain.POSITIONS), setOf("100"), null))
        runCurrent()
        assertEquals(4, a.loads)
        assertEquals(4, b.loads)
    }

    @Test fun baselineDemonstrationRecordedAllDomainBroadcastRefreshesBothButNarrowEventDoesNot() = runTest {
        val feed = Feed()
        val a = NumberSnapshot(feed, "100", backgroundScope)
        val b = NumberSnapshot(feed, "200", backgroundScope)
        var themeLoads = 0
        val theme = SnapshotViewModel(feed, setOf(DataDomain.APPEARANCE),
            load = { ApiResult.Success(++themeLoads) }, testScope = backgroundScope)
        a.refresh()
        b.refresh()
        theme.refresh()
        runCurrent()

        // Recorded producer shape, injected as input: this test does not require any future
        // producer to keep broadcasting all domains. The application graph test owns that evidence.
        val recordedBroadcast = DataChange(1, DataDomain.entries.toSet(), null, null)
        feed.events.emit(recordedBroadcast)
        runCurrent()
        assertEquals(2, a.loads)
        assertEquals(2, b.loads)
        assertEquals(2, themeLoads)

        feed.events.emit(DataChange(2, setOf(DataDomain.CALLERS), setOf("100"), null))
        runCurrent()
        assertEquals(3, a.loads)
        assertEquals(2, b.loads)
        assertEquals(2, themeLoads)
    }
}
