package com.greatwall.callring.ui.feature.recording

import com.greatwall.callring.contracts.change.DataDomain
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.ui.testing.FixtureRecordingApi
import com.greatwall.callring.ui.testing.TestChangeFeed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveSuggestionsModelTest {
    @Test fun unrelatedChangesSkipReadsAndConflictsShowFreshValues() = runTest {
        val original = CallerSuggestions(-7, "31234", 2, "initial", listOf(
            CallerSuggestion(CallerSuggestionField.NAME, "李四", "张三", "我是张三")))
        val latest = original.copy(token = "fresh", fields = listOf(original.fields.single().copy(currentValue = "王五")))
        val feed = TestChangeFeed()
        var reads = 0
        var confirm: ConfirmCallerSuggestions? = null
        val api = object : RecordingApi by FixtureRecordingApi {
            override suspend fun getCallerSuggestions(callId: Long): ApiResult<CallerSuggestions?> {
                reads++
                return ApiResult.Success(original)
            }
            override suspend fun confirmCallerSuggestions(command: ConfirmCallerSuggestions): ApiResult<CallerSuggestionConfirmation> {
                confirm = command
                return ApiResult.Success(CallerSuggestionConfirmation.Conflict(latest))
            }
        }
        val model = ArchiveSuggestionsModel(api, feed, backgroundScope)
        val archive = CallArchive(-7, "3 1234", 0, generation = 2, status = ArchiveStatus.COMPLETE,
            manualAnalysisState = ManualAnalysisState.PENDING_CONFIRMATION)
        model.show(archive)
        runCurrent()
        feed.publish(setOf(DataDomain.AI_SETTINGS))
        feed.entity(DataDomain.CALLERS, "99999")
        runCurrent()
        assertEquals(1, reads)
        feed.entity(DataDomain.CALLERS, "31234")
        runCurrent()
        assertEquals(2, reads)
        val selection = setOf(CallerSuggestionField.NAME)
        model.confirm(original, selection)
        runCurrent()
        assertEquals(ConfirmCallerSuggestions(-7, "initial", selection), confirm)
        assertEquals(latest, model.state.value.load.data)
        assertEquals(1, model.state.value.conflictCount)
        model.show(archive.copy(status = ArchiveStatus.ANALYZING))
        runCurrent()
        assertNull(model.state.value.load.data)
    }
}
