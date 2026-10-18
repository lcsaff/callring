package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.app.UiDestination
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingArchiveListTest {
    @Test fun listLoadsOnlyRecordingArchivesNewestFirstWithOpaqueNegativeIds() = runTest {
        val older = CallArchive(-7, "10086", 10)
        val newer = CallArchive(-8, "10010", 20, status = ArchiveStatus.COMPLETE)
        var loads = 0
        val api = object : RecordingApi by UnavailableRecordingApi {
            override suspend fun listArchives(): ApiResult<List<CallArchive>> {
                loads++
                return ApiResult.Success(listOf(older, newer))
            }
        }
        val model = RecordingArchivesViewModel(api, this)
        model.refresh(); runCurrent()
        assertEquals(listOf(newer, older), model.state.value.data)
        assertEquals(1, loads)
        assertTrue(model.state.value.loaded)
        assertEquals("archive/-8/10010/20", UiDestination.Archive(newer.callId, newer.number, newer.startedAtEpochMillis).route)
        assertEquals(UiDestination.RecordingArchives, UiDestination.fromExternal("recording-archives", null))
    }

    @Test fun refreshShowsFailureAndCanRecoverToEmptyList() = runTest {
        var fail = true
        val api = object : RecordingApi by UnavailableRecordingApi {
            override suspend fun listArchives(): ApiResult<List<CallArchive>> = if (fail)
                ApiResult.Failure(ApiError(ErrorCode.STORAGE_FAILURE, "recording.storage_or_network"))
            else ApiResult.Success(emptyList())
        }
        val model = RecordingArchivesViewModel(api, this)
        model.refresh(); runCurrent()
        assertNotNull(model.state.value.error)
        fail = false
        model.refresh(); runCurrent()
        assertEquals(emptyList<CallArchive>(), model.state.value.data)
        assertNull(model.state.value.error)
    }
}
