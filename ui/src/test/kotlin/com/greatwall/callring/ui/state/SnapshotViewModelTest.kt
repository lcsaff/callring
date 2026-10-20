package com.greatwall.callring.ui.state

import com.greatwall.callring.ui.testing.TestChangeFeed
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.change.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SnapshotViewModelTest {
    @Test fun actualRuntimeTransitionsReloadButDuplicateTokensAndUnrelatedDomainsDoNot() = runTest {
        val events = TestChangeFeed()
        var loads = 0
        val model = SnapshotViewModel(object : ChangeFeed { override fun snapshots() = events.snapshots() },
            setOf(DataDomain.RUNTIME), load = { ApiResult.Success(++loads) }, testScope = backgroundScope)
        runCurrent()
        repeat(2) { events.publish(setOf(DataDomain.RUNTIME)); runCurrent() }
        val latest = events.state.value
        events.state.value = InvalidationSnapshot(latest.incarnation, latest.epoch, latest.revision, latest.domains)
        runCurrent()
        events.publish(setOf(DataDomain.CALLERS)); runCurrent()
        assertEquals(3, loads)
        assertEquals(3, model.state.value.data)
    }

    @Test fun successfulWriteUsesTheOnlyCumulativePublication() = runTest {
        var value = "before"
        val feed = TestChangeFeed()
        val model = SnapshotViewModel(feed,
            setOf(DataDomain.RUNTIME), load = { ApiResult.Success(value) }, testScope = backgroundScope)
        model.refresh(); runCurrent()
        model.execute({ value = "after"; feed.publish(setOf(DataDomain.RUNTIME)); ApiResult.Success(Unit) }); runCurrent()
        assertEquals("after", model.state.value.data)
    }
}
