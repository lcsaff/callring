package com.greatwall.callring.ui.widget

import com.greatwall.callring.contracts.change.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetUpdatesTest {
    @Test fun unrelatedAndTargetedChangesCannotAbandonAnEarlierFullRefresh() = runTest {
        val feed = MutableSharedFlow<DataChange>(extraBufferCapacity = 8)
        val release = CompletableDeferred<Unit>()
        val completed = mutableListOf<Set<Int>?>()
        var cancelled = false
        backgroundScope.observeWidgetChanges(object : ChangeFeed { override fun observe() = feed }) { ids ->
            if (ids == null) try { release.await() } catch (cancel: CancellationException) { cancelled = true; throw cancel }
            completed += ids
        }
        runCurrent()
        feed.emit(DataChange(1, setOf(DataDomain.APPEARANCE), null, null)); runCurrent()
        feed.emit(DataChange(1, setOf(DataDomain.RUNTIME), null, null)); runCurrent()
        feed.emit(DataChange(2, setOf(DataDomain.WIDGET_BINDINGS), null, setOf(4))); runCurrent()
        assertFalse(cancelled)
        release.complete(Unit); runCurrent()
        assertEquals(listOf(null, setOf(4)), completed)
    }
}
