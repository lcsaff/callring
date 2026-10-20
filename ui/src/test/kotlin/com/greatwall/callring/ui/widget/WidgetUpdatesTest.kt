package com.greatwall.callring.ui.widget

import com.greatwall.callring.ui.testing.TestChangeFeed
import com.greatwall.callring.contracts.change.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdatesTest {
    @Test fun unrelatedAndTargetedChangesCannotAbandonAnEarlierFullRefresh() = runTest {
        val feed = TestChangeFeed()
        val release = CompletableDeferred<Unit>()
        val completed = mutableListOf<Set<Int>?>()
        var cancelled = false
        backgroundScope.observeWidgetChanges(object : ChangeFeed { override fun snapshots() = feed.snapshots() }) { ids ->
            if (ids == null) try { release.await() } catch (cancel: CancellationException) { cancelled = true; throw cancel }
            completed += ids
        }
        runCurrent()
        feed.publish(setOf(DataDomain.APPEARANCE)); runCurrent()
        feed.publish(setOf(DataDomain.RUNTIME)); runCurrent()
        feed.publish(setOf(DataDomain.WIDGET_BINDINGS), mapOf(DataDomain.WIDGET_BINDINGS to setOf(4).map { it.toString() }.toSet())); runCurrent()
        assertFalse(cancelled)
        release.complete(Unit); runCurrent()
        assertEquals(listOf<Set<Int>?>(null, null), completed)
    }
}
