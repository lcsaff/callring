package com.greatwall.callring.ui.widget

import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.ui.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class T11WidgetSessionTest {
    @Test fun coldCatchupActiveCommitAndErrorThenNoopHaveSingleReadOwner() = runTest {
        WidgetRefreshSignals.requests.value = emptyMap()
        val feed = TestChangeFeed(); var reads = 0; var updates = 0
        val api = object : UiFakeApi() { override suspend fun getAppearance() = super.getAppearance().also { reads++ } }
        backgroundScope.observeWidgetChanges(feed) { updates++; WidgetRefreshSignals.signal(listOf(7, 8)) }
        runCurrent(); assertEquals(1, updates)
        val model = WidgetModel(api)
        val initial = WidgetRefreshSignals.requests.value[7]
        model.load(); assertEquals(1, reads)
        val delivered = mutableListOf<WidgetSessionUpdate>()
        backgroundScope.launch { widgetSessionUpdates(7, initial, model).collect { delivered += it } }
        runCurrent(); assertEquals(1, reads)
        feed.publish(setOf(DataDomain.CALLERS)); runCurrent(); assertEquals(1, reads)
        feed.publish(setOf(DataDomain.QUIET_HOURS)); runCurrent(); assertEquals(2, reads); assertEquals(2, updates)
        val error = ApiError(ErrorCode.STORAGE_FAILURE, "failed")
        WidgetRefreshSignals.present(listOf(7), error); runCurrent()
        assertEquals(error, delivered.last().error); assertEquals(2, reads)
        WidgetRefreshSignals.present(listOf(7), null); runCurrent()
        assertNull(delivered.last().error); assertEquals("successful no-op only clears presentation", 2, reads)
    }
    @Test fun changeDuringColdInitialReadIsNotDroppedAndInstancesShareGlobalFacts() = runTest {
        WidgetRefreshSignals.requests.value = emptyMap()
        val feed = TestChangeFeed(); var reads = 0; val gate = CompletableDeferred<Unit>()
        val api = object : UiFakeApi() {
            override suspend fun getAppearance(): ApiResult.Success<com.greatwall.callring.contracts.settings.AppearanceSettings> {
                if (++reads == 1) gate.await()
                return super.getAppearance()
            }
        }
        backgroundScope.observeWidgetChanges(feed) { WidgetRefreshSignals.signal(listOf(7, 8)) }
        runCurrent()
        val model = WidgetModel(api); val initial = WidgetRefreshSignals.requests.value[7]
        val cold = backgroundScope.launch { model.load() }
        runCurrent(); feed.publish(setOf(DataDomain.APPEARANCE)); runCurrent(); gate.complete(Unit); runCurrent(); cold.join()
        val updates = mutableListOf<WidgetSessionUpdate>()
        backgroundScope.launch { widgetSessionUpdates(7, initial, model).collect { updates += it } }
        runCurrent(); assertEquals(2, reads); assertNotNull(updates.single().snapshot)
        val secondInitial = WidgetRefreshSignals.requests.value[8]
        val second = WidgetModel(api); second.load()
        backgroundScope.launch { widgetSessionUpdates(8, secondInitial, second).collect() }
        runCurrent(); val before = reads
        feed.entity(DataDomain.WORK_CALENDAR, "schedule"); runCurrent(); assertEquals(before, reads)
        feed.entity(DataDomain.WORK_CALENDAR, "leaves"); runCurrent(); assertEquals(before + 2, reads)
    }
}
