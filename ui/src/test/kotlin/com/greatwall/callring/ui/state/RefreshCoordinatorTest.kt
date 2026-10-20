package com.greatwall.callring.ui.state

import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.feature.home.HomeQuietViewModel
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshCoordinatorTest {
    @Test fun nullableFeedEventDuringFirstEditorLoadQueuesLatestTruth() = runTest {
        val events = MutableSharedFlow<DataChange>()
        val feed = object : ChangeFeed { override fun observe() = events }
        val first = CompletableDeferred<Unit>(); var value = 0; var loads = 0
        val editor = ImmediateEditor(feed, setOf(DataDomain.QUIET_HOURS), {
            val captured = value
            if (++loads == 1) first.await()
            ApiResult.Success(captured)
        }, { _, wanted -> ApiResult.Success(wanted) }, backgroundScope)
        editor.refresh(); runCurrent()
        value = 1; events.emit(DataChange(1, setOf(DataDomain.QUIET_HOURS), null, null)); runCurrent()
        first.complete(Unit); runCurrent()
        assertEquals(2, loads)
        assertEquals(1, editor.state.value.data)
    }
    @Test fun nullableFeedEventDuringFirstHomeLoadRejectsCancelledOldResult() = runTest {
        val events = MutableSharedFlow<DataChange>()
        val feed = object : ChangeFeed { override fun observe() = events }
        val first = CompletableDeferred<Unit>(); var value = QuietSettings(QuietMode.OFF, false, emptyMap()); var loads = 0
        val api = object : SettingsApi by UiFakeApi() {
            override suspend fun getQuietHours(): ApiResult<QuietSettings> {
                val captured = value
                if (++loads == 1) withContext(NonCancellable) { first.await() }
                return ApiResult.Success(captured)
            }
        }
        val home = HomeQuietViewModel(api, feed, testScope = backgroundScope)
        home.refresh(); runCurrent()
        value = value.copy(mode = QuietMode.NON_WORK)
        events.emit(DataChange(1, setOf(DataDomain.QUIET_HOURS), null, null)); runCurrent()
        first.complete(Unit); runCurrent()
        assertEquals(2, loads)
        assertEquals(value, home.state.value.data)
    }
    @Test fun cancelledOldFinallyCannotClearNewLoadingState() = runTest {
        val old = CompletableDeferred<Unit>(); val latest = CompletableDeferred<Unit>()
        var loads = 0; var loading = false
        val coordinator = RefreshCoordinator(backgroundScope, { 0 }, {
            if (++loads == 1) withContext(NonCancellable) { old.await() } else latest.await()
            loads
        }, {}, { loading = it })
        coordinator.request(); runCurrent(); coordinator.cancel(); coordinator.request(); runCurrent()
        assertTrue(loading)
        old.complete(Unit); runCurrent()
        assertTrue("old finally must not stop the current loading indicator", loading)
        latest.complete(Unit); runCurrent(); assertFalse(loading)
    }
    private class Feed : ChangeFeed {
        val state = MutableStateFlow(InvalidationSnapshot("process", 0, 0, emptyMap()))
        override fun snapshots() = state
        override fun observe(): Flow<DataChange> = error("quiet must use cumulative tokens")
        fun publish(revision: Long, domain: DataDomain) {
            state.value = InvalidationSnapshot("process", 0, revision, state.value.domains + (domain to DomainInvalidation(revision, revision)))
        }
    }
    @Test fun loadingKeepsOneFlightSkipsStaleResultAndLoadsOnlyLatestToken() = runTest {
        var token = 0; var loads = 0; var inFlight = 0; var maxInFlight = 0
        val first = CompletableDeferred<Unit>(); val applied = mutableListOf<Int>()
        val coordinator = RefreshCoordinator(backgroundScope, { token }, {
            loads++; inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
            val value = token
            if (loads == 1) first.await()
            inFlight--; value
        }, applied::add)
        coordinator.request(); runCurrent()
        token = 1; coordinator.request(); token = 2; coordinator.request(); runCurrent()
        assertEquals(1, loads)
        first.complete(Unit); runCurrent()
        assertEquals(listOf(2), applied); assertEquals(2, loads); assertEquals(1, maxInFlight)
    }
    @Test fun parameterChangeAndCancelledUncooperativeReadCannotPublishOldResult() = runTest {
        var parameter = "A"; var loads = 0
        val first = CompletableDeferred<Unit>(); val applied = mutableListOf<String>()
        val coordinator = RefreshCoordinator(backgroundScope, { 0 }, {
            val value = parameter; loads++
            if (loads == 1) withContext(NonCancellable) { first.await() }
            value
        }, applied::add)
        coordinator.request(parameter); runCurrent()
        parameter = "B"; coordinator.request(parameter); first.complete(Unit); runCurrent()
        assertEquals(listOf("B"), applied)
        coordinator.cancel(); parameter = "C"; coordinator.request(parameter); runCurrent()
        assertEquals(listOf("B", "C"), applied)
    }
    @Test fun realHomeQuietAndEditorUseCumulativeTokensAndDoNotDoubleQueryAfterWrite() = runTest {
        val feed = Feed(); var loads = 0; var value = QuietSettings(QuietMode.OFF, false, emptyMap())
        val settings = object : SettingsApi by UiFakeApi() {
            override suspend fun getQuietHours(): ApiResult<QuietSettings> { loads++; return ApiResult.Success(value) }
            override suspend fun setQuietEnabled(enabled: Boolean): ApiResult<QuietSettings> {
                value = value.copy(mode = if (enabled) QuietMode.NON_WORK else QuietMode.OFF)
                feed.publish(feed.state.value.revision + 1, DataDomain.QUIET_HOURS)
                return ApiResult.Success(value)
            }
        }
        val home = HomeQuietViewModel(settings, feed, testScope = backgroundScope)
        val editor = ImmediateEditor(feed, setOf(DataDomain.QUIET_HOURS), settings::getQuietHours, { _, wanted ->
            value = wanted; feed.publish(feed.state.value.revision + 1, DataDomain.QUIET_HOURS); ApiResult.Success(wanted)
        }, backgroundScope)
        runCurrent(); assertEquals(2, loads)
        home.setEnabled(true); runCurrent(); assertEquals(4, loads)
        assertEquals(value, home.state.value.data); assertEquals(value, editor.state.value.data)
        feed.publish(2, DataDomain.APPEARANCE); runCurrent(); assertEquals(4, loads)
        editor.edit(value.copy(allNumbers = true)); runCurrent(); assertEquals(6, loads)
        assertEquals(value, home.state.value.data); assertEquals(value, editor.state.value.data)
    }
    @Test fun homeNeverReplacesLaterFeedTruthWithEarlierSaveResult() = runTest {
        val feed = Feed(); var value = QuietSettings(QuietMode.OFF, false, emptyMap())
        val resultGate = CompletableDeferred<Unit>()
        val settings = object : SettingsApi by UiFakeApi() {
            override suspend fun getQuietHours() = ApiResult.Success(value)
            override suspend fun setQuietEnabled(enabled: Boolean): ApiResult<QuietSettings> {
                val own = value.copy(mode = QuietMode.NON_WORK)
                value = own; feed.publish(1, DataDomain.QUIET_HOURS)
                resultGate.await()
                return ApiResult.Success(own)
            }
        }
        val home = HomeQuietViewModel(settings, feed, testScope = backgroundScope)
        runCurrent(); home.setEnabled(true); runCurrent()
        value = value.copy(allNumbers = true); feed.publish(2, DataDomain.QUIET_HOURS); runCurrent()
        resultGate.complete(Unit); runCurrent()
        assertEquals(value, home.state.value.data)
    }
}
