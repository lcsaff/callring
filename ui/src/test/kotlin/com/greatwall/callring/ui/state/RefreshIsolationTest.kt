package com.greatwall.callring.ui.state

import com.greatwall.callring.ui.testing.TestChangeFeed
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.contracts.change.ChangeFeed
import com.greatwall.callring.contracts.change.DataDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.lifecycle.SavedStateHandle
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.settings.SaveNumberRule
import com.greatwall.callring.contracts.settings.NumberRuleEntry
import com.greatwall.callring.ui.testing.UiFakeApi
import com.greatwall.callring.ui.testing.FixtureRecordingApi
import com.greatwall.callring.ui.feature.callers.CallerListViewModel
import com.greatwall.callring.ui.feature.recording.RecordingArchivesViewModel
import com.greatwall.callring.ui.feature.report.ReportViewModel
import java.time.LocalDate

/** Uses UI loaders and contract declarations only; no backend implementation or producer wiring. */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshIsolationTest {
    private class Feed : ChangeFeed {
        val events = TestChangeFeed()
        override fun snapshots() = events.snapshots()
    }

    private class NumberSnapshot(
        changes: ChangeFeed,
        number: String,
        testScope: CoroutineScope,
    ) : UiViewModel(testScope) {
        var loads = 0
            private set
        private val loader = ObservedQuery(scope, changes, numberSubscription(setOf(DataDomain.CALLERS, DataDomain.POSITIONS), number),
            load = { ApiResult.Success("snapshot:${++loads}") })
        val state = loader.state
        init { loader.start(scope) }
        fun refresh() = loader.request()
    }

    @Test fun unrelatedNarrowDomainDoesNotInvokeSnapshotLoader() = runTest {
        val feed = Feed()
        var loads = 0
        val quiet = SnapshotViewModel(feed, setOf(DataDomain.QUIET_HOURS),
            load = { ApiResult.Success(++loads) }, testScope = backgroundScope)
        quiet.refresh()
        runCurrent()
        assertEquals(1, loads)

        feed.events.publish(setOf(DataDomain.APPEARANCE))
        runCurrent()
        assertEquals(1, loads)
        assertEquals(1, quiet.state.value.data)

        feed.events.publish(setOf(DataDomain.QUIET_HOURS))
        runCurrent()
        assertEquals(2, loads)
        assertEquals(2, quiet.state.value.data)
    }

    @Test fun numberedChangesIsolateAAndBWhileGlobalPositionChangesRefreshBoth() = runTest {
        val feed = Feed()
        val a = NumberSnapshot(feed, "100", backgroundScope)
        val b = NumberSnapshot(feed, "200", backgroundScope)
        a.refresh()
        b.refresh()
        runCurrent()

        feed.events.publish(setOf(DataDomain.CALLERS), setOf(DataDomain.CALLERS).filter { it in setOf(DataDomain.CALLERS, DataDomain.CALLS, DataDomain.REPORTS) }.associateWith { setOf("100") })
        runCurrent()
        assertEquals(2, a.loads)
        assertEquals(1, b.loads)
        assertEquals("snapshot:2", a.state.value.data)
        assertEquals("snapshot:1", b.state.value.data)

        feed.events.publish(setOf(DataDomain.CALLERS), setOf(DataDomain.CALLERS).filter { it in setOf(DataDomain.CALLERS, DataDomain.CALLS, DataDomain.REPORTS) }.associateWith { setOf("200") })
        runCurrent()
        assertEquals(2, a.loads)
        assertEquals(2, b.loads)

        // Empty means no matching numbered entity; null means every numbered entity.
        feed.events.publish(setOf(DataDomain.CALLERS), setOf(DataDomain.CALLERS).filter { it in setOf(DataDomain.CALLERS, DataDomain.CALLS, DataDomain.REPORTS) }.associateWith { emptySet() })
        runCurrent()
        assertEquals(2, a.loads)
        assertEquals(2, b.loads)
        feed.events.publish(setOf(DataDomain.CALLERS))
        runCurrent()
        assertEquals(3, a.loads)
        assertEquals(3, b.loads)

        // A key attached to an event must not incorrectly narrow a global dependency.
        feed.events.publish(setOf(DataDomain.POSITIONS), setOf(DataDomain.POSITIONS).filter { it in setOf(DataDomain.CALLERS, DataDomain.CALLS, DataDomain.REPORTS) }.associateWith { setOf("100") })
        runCurrent()
        assertEquals(4, a.loads)
        assertEquals(4, b.loads)
    }

    @Test fun baselineDemonstrationRecordedAllDomainBroadcastRefreshesBothButNarrowEventDoesNot() = runTest {
        val feed = Feed()
        val a = NumberSnapshot(feed, "100", backgroundScope)
        val b = NumberSnapshot(feed, "200", backgroundScope)
        var themeLoads = 0
        val theme = SnapshotViewModel(feed, setOf(DataDomain.APPEARANCE),
            load = { ApiResult.Success(++themeLoads) }, testScope = backgroundScope)
        a.refresh()
        b.refresh()
        theme.refresh()
        runCurrent()

        // Recorded producer shape, injected as input: this test does not require any future
        // producer to keep broadcasting all domains. The application graph test owns that evidence.
        feed.events.publish(DataDomain.entries.toSet())
        runCurrent()
        assertEquals(2, a.loads)
        assertEquals(2, b.loads)
        assertEquals(2, themeLoads)

        feed.events.publish(setOf(DataDomain.CALLERS), setOf(DataDomain.CALLERS).filter { it in setOf(DataDomain.CALLERS, DataDomain.CALLS, DataDomain.REPORTS) }.associateWith { setOf("100") })
        runCurrent()
        assertEquals(3, a.loads)
        assertEquals(2, b.loads)
        assertEquals(2, themeLoads)
    }
    @Test fun numberRuleChangeLeavesRealReportRecordingAndPersonSearchLoadersIdle() = runTest {
        var reportLoads = 0; var recordingLoads = 0; var personSearchLoads = 0
        val api = object : UiFakeApi() {
            override suspend fun load(query: ReportQuery): ApiResult.Success<CallerReport> {
                reportLoads++; return super.load(query)
            }
            override suspend fun search(query: CallerSearch): ApiResult<Page<CallerProfile>> {
                personSearchLoads++; return super.search(query)
            }
            override val recordings = object : RecordingApi by FixtureRecordingApi {
                override suspend fun listArchives(): ApiResult<List<CallArchive>> {
                    recordingLoads++; return ApiResult.Success(emptyList())
                }
            }
        }
        val report = ReportViewModel("100", api.reports, api.changes,
            SavedStateHandle(), DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 23)), backgroundScope)
        val recordings = RecordingArchivesViewModel(api.recordings, backgroundScope, api.changes)
        val people = CallerListViewModel(api.callers::search, api::checkNumber,
            api.changes, SavedStateHandle(mapOf("text" to "测试")), backgroundScope)
        runCurrent()
        assertEquals(listOf(1, 1, 1), listOf(reportLoads, recordingLoads, personSearchLoads))
        val previousReport = report.state.value.data; val previousRecordings = recordings.state.value.data
        api.saveNumberRule(SaveNumberRule(api.numberRule, listOf(NumberRuleEntry("8", 6)))); runCurrent()
        assertEquals(listOf(1, 1, 1), listOf(reportLoads, recordingLoads, personSearchLoads))
        assertEquals(previousReport, report.state.value.data); assertEquals(previousRecordings, recordings.state.value.data)
        assertEquals("测试", people.query.value.text)
    }
}
