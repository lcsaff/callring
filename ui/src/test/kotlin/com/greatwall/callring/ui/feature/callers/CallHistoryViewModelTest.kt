package com.greatwall.callring.ui.feature.callers

import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.ui.testing.FixtureRecordingApi
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallHistoryViewModelTest {
    @Test fun invalidationDiscardsLatePageAndRebuildsTheLoadedWindow() = runTest {
        val initial = listOf(row(10), row(9), row(8))
        val revised = listOf(row(11), row(10), row(9))
        val gate = CompletableDeferred<Unit>()
        var current = initial
        val requests = mutableListOf<Long?>()
        val api = object : UiFakeApi() {
            fun archiveChanged() { feed.entity(DataDomain.RECORDING_ARCHIVES, "-7") }
            override suspend fun queryCallHistory(query: CallHistoryQuery): ApiResult<CallHistoryPage> {
                val after = query.cursor?.callId
                requests += after
                val snapshot = current
                if (after == 9L && snapshot == initial) gate.await()
                val item = snapshot.firstOrNull { after == null || it.call.id < after }
                val next = item?.takeUnless { it == snapshot.last() }?.call?.id?.let {
                    CallHistoryCursor(InvalidationToken("test", 0, emptyMap()), it, it, query.pageSize)
                }
                return ApiResult.Success(CallHistoryPage(listOfNotNull(item), next, SnapshotMeta(0, 0)))
            }
        }
        val model = CallHistoryViewModel(api, FixtureRecordingApi, api.changes, backgroundScope)
        runCurrent()
        model.more()
        runCurrent()
        assertEquals(initial.take(2), model.state.value.data?.entries)
        model.more()
        runCurrent()
        current = revised
        api.archiveChanged()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(revised.take(2), model.state.value.data?.entries)
        assertEquals(listOf(null, 10L, 9L, null, 11L), requests)
    }

    @Test fun positionNameChangesInvalidateHistoryButAiSettingsDoNot() = runTest {
        var reads = 0
        val api = object : UiFakeApi() {
            fun positionChanged() {
                feed.publish(setOf(DataDomain.POSITIONS, DataDomain.CALLERS), mapOf(DataDomain.CALLERS to setOf("100")))
            }
            fun unrelatedPositionChanged() { feed.publish(setOf(DataDomain.POSITIONS)) }
            fun settingsChanged() { feed.publish(setOf(DataDomain.AI_SETTINGS)) }
            override suspend fun queryCallHistory(query: CallHistoryQuery): ApiResult<CallHistoryPage> {
                reads++
                return ApiResult.Success(CallHistoryPage(listOf(row(1)), null, SnapshotMeta(0, 0)))
            }
        }
        CallHistoryViewModel(api, FixtureRecordingApi, api.changes, backgroundScope)
        runCurrent()
        api.settingsChanged()
        api.unrelatedPositionChanged()
        runCurrent()
        assertEquals(1, reads)
        api.positionChanged()
        runCurrent()
        assertEquals(2, reads)
    }

    @Test fun numberHistoryRestoresItsLoadedWindowWithFixedPageSize() = runTest {
        val rows = (121L downTo 1).map(::row)
        val requests = mutableListOf<CallHistoryQuery>()
        val api = object : UiFakeApi() {
            fun callChanged(number: String) { feed.entity(DataDomain.CALLS, number) }
            fun callerChanged(number: String) { feed.entity(DataDomain.CALLERS, number) }
            override suspend fun queryCallHistory(query: CallHistoryQuery): ApiResult<CallHistoryPage> {
                requests += query
                val start = query.cursor?.callId
                val remaining = rows.filter { start == null || it.call.id < start }
                val page = remaining.take(query.pageSize)
                val next = if (remaining.size > page.size) page.last().call.id.let {
                    CallHistoryCursor(InvalidationToken("test", 0, emptyMap()), it, it, query.pageSize, query.number)
                } else null
                return ApiResult.Success(CallHistoryPage(page, next, SnapshotMeta(0, 0)))
            }
        }
        val saved = SavedStateHandle()
        val model = CallHistoryViewModel(api, FixtureRecordingApi, api.changes, backgroundScope,
            number = "31234", initialPageDepth = 2, saved = saved)
        runCurrent()
        assertEquals(2, model.loadedPageDepth.value)
        assertEquals(rows.take(100), model.state.value.data?.entries)
        model.more()
        runCurrent()
        assertEquals(3, model.loadedPageDepth.value)
        assertEquals(rows, model.state.value.data?.entries)
        assertEquals(null, model.state.value.data?.nextCursor)
        api.callChanged("30000")
        api.callerChanged("30000")
        runCurrent()
        assertEquals(3, requests.size)
        api.callerChanged("31234")
        runCurrent()
        assertEquals(rows, model.state.value.data?.entries)
        assertEquals(listOf(null, 72L, 22L, null, 72L, 22L), requests.map { it.cursor?.callId })
        assertEquals(setOf(50), requests.map { it.pageSize }.toSet())
        assertEquals(setOf("31234"), requests.map { it.number }.toSet())

        val restored = CallHistoryViewModel(api, FixtureRecordingApi, api.changes, backgroundScope,
            number = "31234", saved = saved)
        runCurrent()
        assertEquals(3, restored.loadedPageDepth.value)
        assertEquals(rows, restored.state.value.data?.entries)
        assertEquals(listOf(null, 72L, 22L), requests.takeLast(3).map { it.cursor?.callId })
    }

    private fun row(id: Long): CallHistoryEntry {
        val classification = ClassificationFact(true, WorkReason.WORKING, null, true, "UTC", 0, null, 0)
        val call = CallDetail(id, "31234", id, null, null, CallResult.MISSED, CallDecision.NORMAL_RINGING,
            CallSource.SYSTEM_CALL_LOG, false, classification, classification, false, null)
        return CallHistoryEntry(call, CallHistoryRecording(null, CallArchiveAvailability.NONE, false, false))
    }
}
