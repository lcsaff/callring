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
        var workReads = 0; var calendarReads = 0; var leaveReads = 0
        var enabled = false
        var synced = false; var leaveNote: String? = null
        var calendarGate: CompletableDeferred<Unit>? = null
        fun emit(domain: DataDomain) {
            val revision = cumulative.value.revision + 1
            cumulative.value = InvalidationSnapshot("test", 0, revision, cumulative.value.domains + (domain to DomainInvalidation(revision, revision)))
        }
        fun emitWork(key: String) {
            val revision = cumulative.value.revision + 1
            val old = cumulative.value.domains[DataDomain.WORK_CALENDAR]
            cumulative.value = InvalidationSnapshot("test", 0, revision, cumulative.value.domains +
                (DataDomain.WORK_CALENDAR to DomainInvalidation(revision, old?.wideRevision ?: 0, old?.entityRevisions.orEmpty() + (key to revision))))
        }
        override suspend fun getWorkSchedule(): ApiResult.Success<WorkScheduleSettings> { workReads++; return super.getWorkSchedule() }
        override suspend fun getCalendar(): ApiResult.Success<CalendarInfo> { calendarReads++; calendarGate?.await(); return super.getCalendar() }
        override suspend fun getLeaves(): ApiResult<List<LeaveEntry>> { leaveReads++; return super.getLeaves() }
        override suspend fun getSummary(): ApiResult.Success<SettingsSummary> {
            summaries++
            return (super.getSummary() as ApiResult.Success).let { ApiResult.Success(it.value.copy(statisticsEnabled = enabled)) }
        }
        override suspend fun getPositions(): ApiResult.Success<PositionSettings> { positions++; return super.getPositions() }
        override suspend fun getScoreSettings(): ApiResult.Success<ScoreSettings> { scores++; return super.getScoreSettings() }
        override suspend fun getAppearance(): ApiResult.Success<AppearanceSettings> { appearances++; return super.getAppearance() }
        override suspend fun synchronizeCalendar(year: Int): ApiResult<CalendarInfo> {
            if(!synced) { synced=true; emitWork("calendar") }
            return super.getCalendar()
        }
        override suspend fun saveLeave(command: SaveLeave): ApiResult<LeaveEntry> {
            if(leaveNote != command.note) { leaveNote=command.note; emitWork("leaves") }
            return ApiResult.Success(LeaveEntry(1,1,2,command.note,LeaveState.FINISHED))
        }
        override suspend fun setStatisticsEnabled(enabled: Boolean): ApiResult<Unit> {
            if (this.enabled != enabled) { this.enabled = enabled; emit(DataDomain.STATISTICS) }
            return ApiResult.Success(Unit)
        }
    }
    @Test fun historyCommitLoadsOnceNoopAddsZeroAndLoadingRaceDoesNotLoseInvalidation() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store=ViewModelStore()
        try {
            val api=Api(); val calendar=CalendarViewModel(api); val leaves=LeavesViewModel(api)
            store.put("calendar",calendar); store.put("leaves",leaves)
            runCurrent(); assertEquals(1,api.calendarReads); assertEquals(1,api.leaveReads)
            calendar.synchronize(2026); runCurrent(); assertEquals(2,api.calendarReads)
            calendar.synchronize(2026); runCurrent(); assertEquals(2,api.calendarReads)
            val command=SaveLeave(period=LeavePeriod.Timed(1,2),note="a")
            leaves.save(command); runCurrent(); assertEquals(2,api.leaveReads)
            leaves.save(command); runCurrent(); assertEquals(2,api.leaveReads)
            assertEquals(2,api.calendarReads)
            api.calendarGate=CompletableDeferred()
            calendar.refresh(); runCurrent(); assertEquals(3,api.calendarReads)
            api.emitWork("calendar"); runCurrent()
            api.calendarGate!!.complete(Unit); api.calendarGate=null; runCurrent()
            assertTrue(calendar.state.value.loaded)
            assertEquals("an invalidated in-flight load must be followed by a fresh read",4,api.calendarReads)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun historySettingQueriesSubscribeToTheirOwnActualWorkSubsets() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val api = Api()
            store.put("work", WorkScheduleViewModel(api,SavedStateHandle()))
            store.put("calendar", CalendarViewModel(api))
            store.put("leaves", LeavesViewModel(api))
            runCurrent()
            assertEquals(listOf(1,1,1),listOf(api.workReads,api.calendarReads,api.leaveReads))
            api.emitWork("leaves"); runCurrent()
            assertEquals(listOf(1,1,2),listOf(api.workReads,api.calendarReads,api.leaveReads))
            api.emitWork("schedule"); runCurrent()
            assertEquals(listOf(2,1,2),listOf(api.workReads,api.calendarReads,api.leaveReads))
            api.emitWork("calendar"); runCurrent()
            assertEquals(listOf(2,2,2),listOf(api.workReads,api.calendarReads,api.leaveReads))
            api.emit(DataDomain.RECORDING_ARCHIVES); api.emit(DataDomain.AI_SETTINGS); api.emit(DataDomain.APPEARANCE); runCurrent()
            assertEquals(listOf(2,2,2),listOf(api.workReads,api.calendarReads,api.leaveReads))
        } finally { store.clear(); Dispatchers.resetMain() }
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
