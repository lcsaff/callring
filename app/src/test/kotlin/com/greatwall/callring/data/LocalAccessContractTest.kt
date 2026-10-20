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
import kotlinx.coroutines.runBlocking
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
                .put("baselineCodeSha", "a0b420a")
                .put("measurement", "query calls and successful cursor moves / scalar results; not SQLite scan rows")
            val recording = graph.recordings.store.registerIncoming(
                "synthetic-access-session", LocalAccessFixture.TARGET, LocalAccessFixture.at, null,
            )
            assertTrue("recording identity is independent of positive statistics IDs", recording.id < 0)
            graph.recordings.store.linkStatistics(1L, "synthetic-access-session")
            val identity = graph.recordings.getIdentity(recording.id).requiredValue()
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
                        .put("queryOrMovementFailures", row.failures - (old?.failures ?: 0))
                }
                assertTrue("$name must observe the real Graph SQL chain", sqlWindow.isNotEmpty())
                sqlObservations.put(name, JSONArray(sqlWindow))
                val after = graph.businessReadCounters()
                val revisionDelta = graph.coordinator.marker() - beforeRevision
                observations.put(name, JSONObject()
                    .put("fullStateLoads", after.getValue("coreStateLoads") - before.getValue("coreStateLoads"))
                    .put("revisionDelta", revisionDelta)
                    .put("publishedDomains", JSONArray(if (revisionDelta == 0L) emptyList<String>()
                        else graph.coordinator.changes.value.domains.map { it.name }.sorted())))
                assertEquals("ordinary operations must not request a complete AI snapshot",
                    before.getValue("completeCaptures"), after.getValue("completeCaptures"))
                assertEquals("$name must preserve every unrelated profile/call fact",
                    unrelatedBefore, unrelatedFacts())
            }
            measure("quietRead") {
                assertEquals(QuietMode.OFF, graph.api.settings.getQuietHours().requiredValue().mode)
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
            File(output.parentFile, "sql-baseline-$unrelatedCalls.json").writeText(sqlObservations.toString(2))
        } finally {
            graph.close()
        }
    }
}
