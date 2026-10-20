package com.greatwall.callring.ui.state

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.greatwall.callring.contracts.change.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.ui.feature.settings.*
import com.greatwall.callring.ui.testing.UiFakeApi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimpleSettingsRefreshTest {
    private class Api : UiFakeApi() {
        val cumulative = MutableStateFlow(InvalidationSnapshot("test", 0, 0, emptyMap()))
        override fun snapshots() = cumulative
        var summaries = 0; var positions = 0; var scores = 0; var appearances = 0
        var enabled = false
        fun emit(domain: DataDomain) {
            val revision = cumulative.value.revision + 1
            cumulative.value = InvalidationSnapshot("test", 0, revision, cumulative.value.domains + (domain to DomainInvalidation(revision, revision)))
        }
        override suspend fun getSummary(): ApiResult.Success<SettingsSummary> {
            summaries++
            return (super.getSummary() as ApiResult.Success).let { ApiResult.Success(it.value.copy(statisticsEnabled = enabled)) }
        }
        override suspend fun getPositions(): ApiResult.Success<PositionSettings> { positions++; return super.getPositions() }
        override suspend fun getScoreSettings(): ApiResult.Success<ScoreSettings> { scores++; return super.getScoreSettings() }
        override suspend fun getAppearance(): ApiResult.Success<AppearanceSettings> { appearances++; return super.getAppearance() }
        override suspend fun setStatisticsEnabled(enabled: Boolean): ApiResult<Unit> {
            if (this.enabled != enabled) { this.enabled = enabled; emit(DataDomain.STATISTICS) }
            return ApiResult.Success(Unit)
        }
    }
    @Test fun summaryCommitLoadsOnceAndNoopDoesNotLoadAgain() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val api = Api(); val model = SettingsSummaryViewModel(api); store.put("summary", model)
            assertEquals(1, api.summaries)
            model.statistics(true); runCurrent()
            assertTrue(model.state.value.data!!.statisticsEnabled)
            assertEquals("one stable commit has one reload", 2, api.summaries)
            model.statistics(true); runCurrent()
            assertEquals("no-op has no additional reload", 2, api.summaries)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun overlaySummaryLoaderTracksEveryFieldInItsSummaryContract() = runTest {
        val api = Api()
        val model = com.greatwall.callring.ui.overlay.OverlayViewModel(
            com.greatwall.callring.contracts.callers.IncomingCallUiRequest("session", "30001", 123,
                com.greatwall.callring.contracts.callers.CallDecision.NORMAL_RINGING), api, backgroundScope)
        runCurrent(); assertEquals(1, api.summaries)
        for (domain in listOf(DataDomain.SCORE_SETTINGS, DataDomain.POSITIONS, DataDomain.STATISTICS,
                DataDomain.QUIET_HOURS, DataDomain.WORK_CALENDAR)) {
            val before = api.summaries
            api.emit(domain); runCurrent()
            assertEquals("summary must track $domain", before + 1, api.summaries)
        }
        assertTrue(model.settings.value.loaded)
        val count = api.summaries
        api.emit(DataDomain.RECORDING_ARCHIVES); api.emit(DataDomain.AI_SETTINGS); runCurrent()
        assertEquals(count, api.summaries)
    }
    @Test fun actualSettingModelsIgnoreRecordingAiAndUpdatesAndRefreshTheirOwnData() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val api = Api()
            store.put("positions", PositionsViewModel(api))
            store.put("score", ScoreSettingsViewModel(api, SavedStateHandle()))
            store.put("appearance", AppearanceViewModel(api, SavedStateHandle()))
            assertEquals(listOf(1, 1, 1), listOf(api.positions, api.scores, api.appearances))
            api.emit(DataDomain.RECORDING_ARCHIVES); api.emit(DataDomain.AI_SETTINGS); runCurrent()
            assertEquals(listOf(1, 1, 1), listOf(api.positions, api.scores, api.appearances))
            api.emit(DataDomain.SCORE_SETTINGS); runCurrent()
            assertEquals(listOf(1, 2, 1), listOf(api.positions, api.scores, api.appearances))
            api.emit(DataDomain.APPEARANCE); api.emit(DataDomain.POSITIONS); runCurrent()
            assertEquals(listOf(2, 2, 2), listOf(api.positions, api.scores, api.appearances))
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
