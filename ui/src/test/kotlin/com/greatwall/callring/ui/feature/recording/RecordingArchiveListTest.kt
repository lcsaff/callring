package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.testing.*
import com.greatwall.callring.ui.app.UiDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import com.greatwall.callring.contracts.change.DataDomain
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingArchiveListTest {
    @Test fun listLoadsOnlyRecordingArchivesNewestFirstWithOpaqueNegativeIds() = runTest {
        val older = CallArchive(-7, "10086", 10)
        val newer = CallArchive(-8, "10010", 20, status = ArchiveStatus.COMPLETE)
        var loads = 0
        val api = object : RecordingApi by FixtureRecordingApi {
            override suspend fun queryArchives(query: ArchiveListQuery): ApiResult<ArchiveListPage> {
                loads++
                return ApiResult.Success(archivePageForTest(older, newer))
            }
        }
        val model = RecordingArchivesViewModel(api, backgroundScope, com.greatwall.callring.ui.testing.TestChangeFeed())
        model.refresh(); runCurrent()
        assertEquals(listOf(newer.listItemForTest(), older.listItemForTest()), model.state.value.data)
        assertEquals(1, loads)
        assertTrue(model.state.value.loaded)
        assertEquals("archive/-8/10010/20", UiDestination.Archive(newer.callId, newer.number, newer.startedAtEpochMillis).route)
        assertEquals(UiDestination.RecordingArchives, UiDestination.fromExternal("recording-archives", null))
    }

    @Test fun refreshShowsFailureAndCanRecoverToEmptyList() = runTest {
        var fail = true
        val api = object : RecordingApi by FixtureRecordingApi {
            override suspend fun queryArchives(query: ArchiveListQuery): ApiResult<ArchiveListPage> = if (fail)
                ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "recording.storage_or_network"))
            else ApiResult.Success(archivePageForTest())
        }
        val model = RecordingArchivesViewModel(api, backgroundScope, com.greatwall.callring.ui.testing.TestChangeFeed())
        model.refresh(); runCurrent()
        assertNotNull(model.state.value.error)
        fail = false
        model.refresh(); runCurrent()
        assertEquals(emptyList<ArchiveListItem>(), model.state.value.data)
        assertNull(model.state.value.error)
    }

    @Test fun laterPageFailureRetriesWithoutDuplicatingRowsAndRefreshResetsChain() = runTest {
        val first = CallArchive(-1, "10086", 20).listItemForTest()
        val second = CallArchive(-2, "10010", 10).listItemForTest()
        val cursor = ArchiveListCursor("owner", 1, 20, -1, 50)
        var fail = true
        val api = object : RecordingApi by FixtureRecordingApi {
            override suspend fun queryArchives(query: ArchiveListQuery): ApiResult<ArchiveListPage> =
                if (query.cursor == null) ApiResult.Success(ArchiveListPage(listOf(first), cursor, false))
                else if (fail) ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "recording.storage_or_network"))
                else ApiResult.Success(ArchiveListPage(listOf(second), null, true))
        }
        val model = RecordingArchivesViewModel(api, backgroundScope, TestChangeFeed())
        runCurrent()
        model.more(); runCurrent()
        assertEquals(listOf(first), model.state.value.data)
        assertNotNull(model.state.value.error)
        fail = false
        model.more(); runCurrent()
        assertEquals(listOf(first, second), model.state.value.data)
        assertTrue(model.paging.value.complete)
        model.more(); runCurrent()
        assertEquals(listOf(first, second), model.state.value.data)
        model.refresh(); runCurrent()
        assertEquals(listOf(first), model.state.value.data)
    }

    @Test fun sourceInvalidationDiscardsLateOldPageAndIgnoresOtherSettings() = runTest {
        val old = CallArchive(-1, "old", 20).listItemForTest()
        val latest = CallArchive(-2, "new", 30).listItemForTest()
        val stale = CallArchive(-3, "stale", 10).listItemForTest()
        val cursor = ArchiveListCursor("owner", 1, 20, -1, 50)
        val gate = CompletableDeferred<Unit>()
        var changed = false
        var calls = 0
        val feed = TestChangeFeed()
        val api = object : RecordingApi by FixtureRecordingApi {
            override suspend fun queryArchives(query: ArchiveListQuery): ApiResult<ArchiveListPage> {
                calls++
                return if (query.cursor != null) {
                    gate.await()
                    ApiResult.Success(ArchiveListPage(listOf(stale), null, true))
                } else if (changed) ApiResult.Success(ArchiveListPage(listOf(latest), null, true))
                else ApiResult.Success(ArchiveListPage(listOf(old), cursor, false))
            }
        }
        val model = RecordingArchivesViewModel(api, backgroundScope, feed)
        runCurrent()
        feed.publish(setOf(DataDomain.AI_SETTINGS)); runCurrent()
        assertEquals(1, calls)
        model.more(); runCurrent()
        changed = true
        feed.entity(DataDomain.RECORDING_ARCHIVES, "-2"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(latest), model.state.value.data)
        assertEquals(3, calls)
    }

    @Test fun legacyPreparationIsNotReportedAsEmptyAndResumesWithBoundedRequests() = runTest {
        var calls = 0
        val api = object : RecordingApi by FixtureRecordingApi {
            override suspend fun queryArchives(query: ArchiveListQuery): ApiResult<ArchiveListPage> {
                calls++
                return ApiResult.Success(if (calls == 1) ArchiveListPage(emptyList(), null, false, preparing = true, remaining = 1)
                    else archivePageForTest(CallArchive(-1, "10086", 10)))
            }
        }
        val model = RecordingArchivesViewModel(api, backgroundScope, TestChangeFeed())
        runCurrent()
        assertFalse(model.state.value.loaded)
        assertTrue(model.paging.value.preparing)
        advanceTimeBy(100); runCurrent()
        assertEquals(2, calls)
        assertTrue(model.state.value.loaded)
        assertEquals(1, model.state.value.data!!.size)
    }
}
