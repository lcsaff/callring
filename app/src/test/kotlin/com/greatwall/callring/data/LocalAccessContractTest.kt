package com.greatwall.callring.data

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.bootstrap.BackendConfiguration
import com.greatwall.callring.bootstrap.BackendGraph
import com.greatwall.callring.platform.UiNotificationEntryPoints
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.*
import com.greatwall.callring.contracts.command.CommandContext
import com.greatwall.callring.ui.feature.settings.QuietViewModel
import com.greatwall.callring.ui.feature.home.HomeQuietViewModel
import androidx.lifecycle.SavedStateHandle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LocalAccessContractTest {
    @Test fun localOperationsPreserveResultsWithOneThousandUnrelatedCalls() = characterize(1_000)
    @Test fun localOperationsPreserveResultsWithTenThousandUnrelatedCalls() = characterize(10_000)

    private fun characterize(unrelatedCalls: Int) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "local-access-$unrelatedCalls.db"
        SqliteBackendStore(context, databaseName).use { LocalAccessFixture.seed(it, unrelatedCalls) }
        val sqlProbe = SqlAccessProbe()
        val configuration = BackendConfiguration(
            AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM),
            databaseName = databaseName,
            sqlAccessProbe = sqlProbe,
            ringPreferencesName = "access_${unrelatedCalls}_ring",
            widgetPreferencesName = "access_${unrelatedCalls}_widgets",
            appearancePreferencesName = "access_${unrelatedCalls}_appearance",
            clock = Clock.fixed(Instant.ofEpochMilli(LocalAccessFixture.at + 60_000), ZoneOffset.UTC),
            dispatcher = Dispatchers.Unconfined,
        )
        val notifications = object : UiNotificationEntryPoints {
            override fun home(requestCode: Int): PendingIntent = PendingIntent.getActivity(
                context, requestCode, Intent("synthetic-access"), PendingIntent.FLAG_IMMUTABLE,
            )
            override fun playback(preview: Boolean, stopAction: PendingIntent): Notification =
                Notification.Builder(context, "synthetic").build()
        }
        val graph = BackendGraph(context, configuration, notifications, object : IncomingCallUiSink {
            override fun show(request: IncomingCallUiRequest) = Unit
        })
        try {
            // Audit reads are deliberately outside measured production operations and their counters.
            fun unrelatedFacts() = SqliteBackendStore(context, databaseName).use { store ->
                store.read { state ->
                    state.profiles.getValue(LocalAccessFixture.UNRELATED) to
                        state.calls.values.filter { it.number == LocalAccessFixture.UNRELATED }.sortedBy { it.id }
                }
            }
            val unrelatedBefore = unrelatedFacts()
            assertEquals(unrelatedCalls, unrelatedBefore.second.size)
            assertEquals("Synthetic B", unrelatedBefore.first.name)
            assertEquals("synthetic-legacy-result", unrelatedBefore.second.last().legacyResult)
            assertTrue(unrelatedBefore.second.first().classification.working)
            assertFalse(unrelatedBefore.second.first().originalClassification.working)
            assertNotEquals(unrelatedBefore.second.first().classification.configVersion,
                unrelatedBefore.second.first().originalClassification.configVersion)
            val observations = JSONObject().put("unrelatedCallCount", unrelatedCalls)
            val sqlObservations = JSONObject().put("unrelatedCallCount", unrelatedCalls)
                .put("implementationBaseSha", "b3f4ab7")
                .put("measurement", "query calls and successful cursor moves / scalar results; not SQLite scan rows")
            val recording = graph.recordings.registrations.registerIncoming(
                "synthetic-access-session", LocalAccessFixture.TARGET, LocalAccessFixture.at, null, null,
            )
            assertTrue("recording identity is independent of positive statistics IDs", recording.id < 0)
            graph.recordings.store.insertStatisticsLink(1L, "synthetic-access-session")
            val identity = graph.recordings.service.getIdentity(recording.id).requiredValue()
            assertEquals(LocalAccessFixture.TARGET, identity.number)
            suspend fun measure(name: String, operation: suspend () -> Unit) {
                val sqlBefore = sqlProbe.snapshot().associateBy { it.tag to it.tables }
                val before = graph.businessReadCounters()
                val beforeRevision = graph.coordinator.marker()
                operation()
                val sqlAfter = sqlProbe.snapshot()
                val sqlWindow = sqlAfter.mapNotNull { row ->
                    val old = sqlBefore[row.tag to row.tables]
                    val calls = row.calls - (old?.calls ?: 0)
                    if (calls == 0L) null else JSONObject()
                        .put("tag", row.tag.name).put("tables", JSONArray(row.tables.map { it.sqlName }.sorted()))
                        .put("queryCalls", calls).put("consumedRows", row.consumedRows - (old?.consumedRows ?: 0))
                        .put("closedCursors", row.closed - (old?.closed ?: 0))
                        .put("writtenRows", row.writtenRows - (old?.writtenRows ?: 0))
                        .put("queryOrMovementFailures", row.failures - (old?.failures ?: 0))
                }
                assertTrue("$name must observe the real Graph SQL chain", sqlWindow.isNotEmpty())
                sqlObservations.put(name, JSONArray(sqlWindow))
                val after = graph.businessReadCounters()
                val revisionDelta = graph.coordinator.marker() - beforeRevision
                if (name == "scoreRead") assertEquals("score must avoid full state loading", 0L, after.getValue("coreStateLoads") - before.getValue("coreStateLoads"))
                if (name.startsWith("quiet")) {
                    assertEquals("quiet must avoid full state loading", 0L, after.getValue("coreStateLoads") - before.getValue("coreStateLoads"))
                    assertEquals(if (name == "quietRead" || name == "quietNoop") 0L else 1L, revisionDelta)
                    assertTrue(sqlWindow.all { row -> row.getJSONArray("tables").toString() == "[\"settings\"]" })
                }
                observations.put(name, JSONObject()
                    .put("fullStateLoads", after.getValue("coreStateLoads") - before.getValue("coreStateLoads"))
                    .put("revisionDelta", revisionDelta)
                    .put("quietReads", after.getValue("quietReads") - before.getValue("quietReads"))
                    .put("quietWrites", after.getValue("quietWrites") - before.getValue("quietWrites"))
                    .put("publishedDomains", JSONArray(if (revisionDelta == 0L) emptyList<String>()
                        else graph.coordinator.invalidations.snapshots.value.let { snapshot -> snapshot.domains.filterValues { it.lastAnyRevision == snapshot.revision }.keys }.map { it.name }.sorted())))
                assertEquals("ordinary operations must not request a complete AI snapshot",
                    before.getValue("completeCaptures"), after.getValue("completeCaptures"))
                assertEquals("$name must preserve every unrelated profile/call fact",
                    unrelatedBefore, unrelatedFacts())
            }
            measure("scoreRead") { assertEquals(ScoreSettings(5, 2, 10, 30), graph.api.settings.getScoreSettings().requiredValue()) }
            measure("quietRead") {
                assertEquals(QuietMode.OFF, graph.api.settings.getQuietHours().requiredValue().mode)
            }
            val nonWork = QuietSettings(QuietMode.NON_WORK, false, (1..7).associateWith { emptyList<TimeWindow>() })
            measure("quietWrite") { assertEquals(nonWork, graph.api.settings.saveQuietHours(nonWork).requiredValue()) }
            measure("quietNoop") { assertEquals(nonWork, graph.api.settings.saveQuietHours(nonWork).requiredValue()) }
            val all = nonWork.copy(allNumbers = true)
            measure("quietEdit") { assertEquals(all, graph.api.edits.quiet(nonWork, all).requiredValue()) }
            measure("quietCommand") {
                val command = CommandContext("quiet-synthetic", graph.coordinator.capture(), 1, expectedScopes = graph.coordinator.expect(setOf("quiet"))) { true }
                assertEquals(QuietMode.OFF, graph.api.commandApi(command).settings.setQuietEnabled(false).requiredValue().mode)
                assertNotNull(graph.coordinator.committed(command.operationId))
            }
            measure("targetProfileWrite") {
                val saved = graph.api.callers.saveProfile(
                    SaveCallerProfile(LocalAccessFixture.TARGET, "Edited A", "Fixture", 1L),
                ).requiredValue()
                assertEquals("Edited A", saved.name)
            }
            measure("targetReport") {
                val date = LocalDate.of(2026, 10, 19)
                val report = graph.api.reports.load(ReportQuery(
                    LocalAccessFixture.TARGET, DateRange(date, date, "UTC"),
                )).requiredValue()
                assertEquals(1L, report.details.totalItems)
                assertEquals("Edited A", report.profile.name)
            }
            measure("incomingRegistration") {
                assertTrue(graph.backend.recordIncoming(
                    LocalAccessFixture.TARGET, LocalAccessFixture.at + 60_000,
                    CallDecision.NORMAL_RINGING,
                ) > 0)
            }
            assertEquals("Synthetic B", graph.api.callers.getEditor(LocalAccessFixture.UNRELATED)
                .requiredValue().draft.name)
            val output = File("build/qa/layer-boundaries/local-access-$unrelatedCalls.json")
            checkNotNull(output.parentFile).mkdirs()
            output.writeText(observations.toString(2))
            File(output.parentFile, "sql-scoped-$unrelatedCalls.json").writeText(sqlObservations.toString(2))
            // Both actual ViewModels consume the production cumulative StateFlow and narrow SQL reader.
            val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                var reportLoads = 0
                val reportModel = com.greatwall.callring.ui.state.SnapshotViewModel(graph.api.changes, setOf(com.greatwall.callring.contracts.change.DataDomain.REPORTS), {
                    reportLoads++
                    val date = LocalDate.of(2026, 10, 19)
                    graph.api.reports.load(ReportQuery(LocalAccessFixture.UNRELATED, DateRange(date, date, "UTC")))
                }, uiScope)
                val beforeUi = graph.businessReadCounters().getValue("quietReads")
                val quiet = QuietViewModel(graph.api, SavedStateHandle(), uiScope)
                val home = HomeQuietViewModel(graph.api.settings, graph.api.changes, testScope = uiScope)
                withTimeout(5000) { while (!quiet.state.value.loaded || !home.state.value.loaded) delay(1) }
                assertEquals(2L, graph.businessReadCounters().getValue("quietReads") - beforeUi)
                quiet.edit(quiet.state.value.data!!.copy(mode = QuietMode.NON_WORK))
                withTimeout(5000) { while (quiet.mutation.value.busy || home.state.value.data?.mode != QuietMode.NON_WORK) delay(1) }
                assertEquals(5L, graph.businessReadCounters().getValue("quietReads") - beforeUi) // one write-old + two loads
                home.setEnabled(false)
                withTimeout(5000) { while (home.mutation.value.busy || quiet.state.value.data?.mode != QuietMode.OFF) delay(1) }
                assertEquals(8L, graph.businessReadCounters().getValue("quietReads") - beforeUi)
                assertEquals(1, reportLoads)
                assertTrue(reportModel.state.value.loaded)
                observations.put("uiQuiet", JSONObject().put("initialQuietLoaderReads", 2)
                    .put("editorCommitOldValueReads", 1).put("editorCommitReloads", 2)
                    .put("homeCommitOldValueReads", 1).put("homeCommitReloads", 2)
                    .put("unrelatedReportInitialLoads", reportLoads).put("unrelatedReportReloads", 0))
                val count = graph.businessReadCounters().getValue("quietReads")
                graph.api.settings.saveAppearance(configuration.defaultAppearance).requiredValue()
                assertEquals("appearance is unrelated to quiet", count, graph.businessReadCounters().getValue("quietReads"))
            } finally { uiScope.cancel() }
            output.writeText(observations.toString(2))
        } finally {
            graph.close()
        }
    }
}
