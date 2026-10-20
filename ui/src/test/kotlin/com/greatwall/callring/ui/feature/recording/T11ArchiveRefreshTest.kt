package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.testing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class T11ArchiveRefreshTest {
    @Test fun positiveAliasMovesToCanonicalAndNullArchiveKeepsItsIdentitySubscription() = runTest {
        val feed = TestChangeFeed()
        val data = mutableMapOf<Long, CallArchive>(1L to CallArchive(1, "A", 0), -2L to CallArchive(-2, "B", 0))
        var linked: Long? = null; val reads = linkedMapOf<Long, Int>(); var metadata = 0
        val api = object : RecordingApi by FixtureRecordingApi {
            override suspend fun getArchive(callId: Long): ApiResult<CallArchive?> {
                reads[callId] = (reads[callId] ?: 0) + 1
                return ApiResult.Success(data[if (callId == 1L) linked ?: callId else callId])
            }
            override suspend fun getIdentity(callId: Long): ApiResult<RecordingIdentity> {
                metadata++
                return ApiResult.Success(RecordingIdentity(if (callId == 1L) linked ?: callId else callId, "A", 0))
            }
        }
        val a = ArchiveViewModel(api, 1, "A", 0, backgroundScope, adoptCommand = { _, _ -> error("unused") }, changes = feed)
        val b = ArchiveViewModel(api, -2, "B", 0, backgroundScope, adoptCommand = { _, _ -> error("unused") }, changes = feed)
        runCurrent(); assertEquals(mapOf(1L to 1, -2L to 1), reads); assertEquals(0, metadata)
        data[1] = data.getValue(1).copy(generation = 1); feed.entity(DataDomain.RECORDING_ARCHIVES, "1"); runCurrent()
        assertEquals(2, reads[1L]); assertEquals(1, reads[-2L])
        linked = -1; data[-1] = CallArchive(-1, "A", 0, generation = 2)
        feed.entity(DataDomain.RECORDING_ARCHIVES, "statistics:1", "-1"); runCurrent()
        assertEquals(-1L, a.state.value.data!!.callId); assertEquals(3, reads[1L])
        feed.entity(DataDomain.CALLERS, "A"); runCurrent(); assertEquals(3, reads[1L])
        data.remove(-1); feed.entity(DataDomain.RECORDING_ARCHIVES, "-1"); runCurrent()
        assertNull(a.state.value.data); assertEquals(1, metadata); assertEquals(4, reads[1L])
        data[-1] = CallArchive(-1, "A", 0, generation = 3); feed.entity(DataDomain.RECORDING_ARCHIVES, "-1"); runCurrent()
        assertEquals(3L, a.state.value.data!!.generation); assertEquals(5, reads[1L]); assertEquals(1, reads[-2L])
        assertEquals(-2L, b.state.value.data!!.callId)
    }

    @Test fun canonicalChangeDuringInitialPayloadRejectsStaleResultWithoutInitialDoubleQuery() = runTest {
        val feed = TestChangeFeed(); val gate = CompletableDeferred<Unit>(); var reads = 0
        var value: CallArchive? = CallArchive(-1, "A", 0, generation = 1)
        val api = object : RecordingApi by FixtureRecordingApi {
            override suspend fun getArchive(callId: Long): ApiResult<CallArchive?> {
                val captured = value
                if (++reads == 1) gate.await()
                return ApiResult.Success(captured)
            }
        }
        val model = ArchiveViewModel(api, 1, "A", 0, backgroundScope, adoptCommand = { _, _ -> error("unused") }, changes = feed)
        runCurrent(); value = value!!.copy(generation = 2)
        feed.entity(DataDomain.RECORDING_ARCHIVES, "-1"); runCurrent(); assertEquals(1, reads)
        gate.complete(Unit); runCurrent(); assertEquals(2, reads); assertEquals(2L, model.state.value.data!!.generation)
        feed.entity(DataDomain.RECORDING_ARCHIVES, "-2"); runCurrent(); assertEquals(2, reads)
    }

    @Test fun initialNullPositiveAliasResolvesMetadataOnceAndSubsequentCreationIsVisible() = runTest {
        val feed = TestChangeFeed(); var reads = 0; var identities = 0; var archive: CallArchive? = null
        val api = object : RecordingApi by FixtureRecordingApi {
            override suspend fun getArchive(callId: Long): ApiResult<CallArchive?> { reads++; return ApiResult.Success(archive) }
            override suspend fun getIdentity(callId: Long): ApiResult<RecordingIdentity> { identities++; return ApiResult.Success(RecordingIdentity(-1, "A", 0)) }
        }
        val model = ArchiveViewModel(api, 1, "A", 0, backgroundScope, adoptCommand = { _, _ -> error("unused") }, changes = feed)
        runCurrent(); assertEquals(1, reads); assertEquals(1, identities)
        archive = CallArchive(-1, "A", 0); feed.entity(DataDomain.RECORDING_ARCHIVES, "-1"); runCurrent()
        assertEquals(2, reads); assertEquals(-1L, model.state.value.data!!.callId)
    }
}
