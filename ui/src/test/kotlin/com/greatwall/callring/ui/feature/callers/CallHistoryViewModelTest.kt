package com.greatwall.callring.ui.feature.callers

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

    private fun row(id: Long): CallHistoryEntry {
        val classification = ClassificationFact(true, WorkReason.WORKING, null, true, "UTC", 0, null, 0)
        val call = CallDetail(id, "31234", id, null, null, CallResult.MISSED, CallDecision.NORMAL_RINGING,
            CallSource.SYSTEM_CALL_LOG, false, classification, classification, false, null)
        return CallHistoryEntry(call, CallHistoryRecording(null, CallArchiveAvailability.NONE, false, false))
    }
}
