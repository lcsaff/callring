package com.greatwall.callring.data

import com.greatwall.callring.data.database.SqlAccessProbe
import com.greatwall.callring.data.database.SqliteCoreStorageResource
import com.greatwall.callring.data.database.values

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.report.*
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.bootstrap.BackendConfiguration
import com.greatwall.callring.bootstrap.BackendGraph
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
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
class HistorySettingsAccessContractTest {
    @Test fun localOperationsPreserveResultsWithOneThousandUnrelatedCalls() = characterize(1_000)
    @Test fun localOperationsPreserveResultsWithTenThousandUnrelatedCalls() = characterize(10_000)

    private fun characterize(unrelatedCalls: Int) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "history-settings-$unrelatedCalls.db"
        SqliteCoreStorageResource(context, databaseName).use { LocalAccessFixture.seed(it, unrelatedCalls) }
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
            fun unrelatedFacts() = SqliteCoreStorageResource(context, databaseName).use { store ->
                store.fullSnapshot.capture().let { state ->
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
                .put("implementationBaseSha", "b99c5ae")
                .put("measurement", "query calls and successful cursor moves / scalar results; not SQLite scan rows")
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
                sqlObservations.put(name, JSONArray(sqlWindow))
                val after = graph.businessReadCounters()
                val revisionDelta = graph.coordinator.marker() - beforeRevision
                assertEquals("$name logical revision", if (name.endsWith("Read") || name.endsWith("Noop")) 0L else 1L, revisionDelta)
                assertEquals("$name must avoid complete snapshot capture", 0L, after.getValue("completeCaptures") - before.getValue("completeCaptures"))
                assertTrue("$name must avoid calls rows", sqlWindow.none { it.getJSONArray("tables").toString().contains("calls") })
                observations.put(name, JSONObject()
                    .put("completeCaptures", after.getValue("completeCaptures") - before.getValue("completeCaptures"))
                    .put("revisionDelta", revisionDelta)
                    .put("quietReads", after.getValue("quietReads") - before.getValue("quietReads"))
                    .put("quietWrites", after.getValue("quietWrites") - before.getValue("quietWrites"))
                    .put("publishedDomains", JSONArray(if (revisionDelta == 0L) emptyList<String>()
                        else graph.coordinator.invalidations.snapshots.value.let { snapshot -> snapshot.domains.filterValues { it.lastAnyRevision == snapshot.revision }.keys }.map { it.name }.sorted())))
                assertEquals("$name must preserve every unrelated profile/call fact",
                    unrelatedBefore, unrelatedFacts())
            }
            measure("calendarRead") { graph.api.settings.getCalendar().requiredValue() }
            measure("overrideNoop") { graph.api.settings.deleteDateOverride(LocalDate.of(2026, 2, 2)).requiredValue() }
            measure("leavesRead") { graph.api.settings.getLeaves().requiredValue() }
            var saved: WorkScheduleSettings? = null
            measure("workRead") { saved = graph.api.settings.getWorkSchedule().requiredValue() }
            measure("workWrite") { saved = graph.api.settings.saveWorkSchedule(SaveWorkSchedule(saved!!.zoneId, saved!!.weekly, saved!!.makeup)).requiredValue() }
            val first = saved!!
            measure("workAppend") { saved = graph.api.settings.saveWorkSchedule(SaveWorkSchedule(first.zoneId, first.weekly, first.makeup)).requiredValue() }
            assertTrue(saved!!.configVersion > first.configVersion)
            assertEquals(first.effectiveFromEpochMillis + 1, saved!!.effectiveFromEpochMillis)
            measure("workReset") { graph.api.settings.resetWorkSchedule().requiredValue() }
            assertThrows(IllegalStateException::class.java) { graph.backend.decisionSnapshot() }
            graph.backend.refreshDecisionSnapshot()
            graph.api.settings.setQuietEnabled(true).requiredValue()
            val latestUnrelatedInputs = graph.backend.decisionSnapshot().inputs
            val untouched = SqliteCoreStorageResource(context,databaseName).use { it.fullSnapshot.capture().let { s -> s.calls.toMap() } }
            suspend fun correction(name: String, expectedRevision: Long = 1, operation: suspend () -> Unit) {
                val before = graph.businessReadCounters()
                val revision = graph.coordinator.marker()
                val prior = sqlProbe.snapshot().associateBy { it.tag to it.tables }
                operation()
                assertEquals(name, expectedRevision, graph.coordinator.marker() - revision)
                val after = graph.businessReadCounters()
                assertEquals(name, 0L, after.getValue("completeCaptures") - before.getValue("completeCaptures"))
                val rows = sqlProbe.snapshot().mapNotNull { row ->
                    val old = prior[row.tag to row.tables]
                    val calls = row.calls - (old?.calls ?: 0)
                    if(calls == 0L) null else JSONObject().put("tag", row.tag.name).put("tables", JSONArray(row.tables.map { it.sqlName }.sorted()))
                        .put("queryCalls", calls).put("consumedRows", row.consumedRows - (old?.consumedRows ?: 0))
                        .put("writtenRows", row.writtenRows - (old?.writtenRows ?: 0))
                }
                assertTrue(name, rows.none { it.getJSONArray("tables").toString().contains("callers") })
                if(expectedRevision == 0L) assertTrue(name, rows.none { it.getLong("writtenRows") > 0 })
                sqlObservations.put(name, JSONArray(rows))
                observations.put(name, JSONObject().put("completeCaptures", after.getValue("completeCaptures") - before.getValue("completeCaptures")).put("revisionDelta", graph.coordinator.marker() - revision)
                    .put("scope", "necessary full-history correction in batches of 256; consumed rows scale with history"))
                val decision = graph.backend.decisionSnapshot().inputs
                assertEquals(latestUnrelatedInputs.quiet,decision.quiet)
                assertEquals(latestUnrelatedInputs.overlayEnabled,decision.overlayEnabled)
                assertEquals(latestUnrelatedInputs.whitelistedNumbers,decision.whitelistedNumbers)
                assertEquals(latestUnrelatedInputs.statisticsIntervals,decision.statisticsIntervals)
                SqliteCoreStorageResource(context,databaseName).use { store -> store.fullSnapshot.capture().let { state ->
                    state.calls.forEach { (id, call) ->
                        assertEquals("$name original/unrelated fields $id", untouched.getValue(id), call.copy(classification = untouched.getValue(id).classification))
                        assertEquals("$name current $id", com.greatwall.callring.domain.calling.WorkClassifier.classify(state.work,call.time), call.classification)
                    }
                } }
            }
            val override = DateOverride(LocalDate.of(2099,1,1),DateOverrideKind.REST,emptyList())
            correction("overrideCorrection") { graph.api.settings.saveDateOverride(override).requiredValue() }
            correction("overrideIdenticalNoop",0) { graph.api.settings.saveDateOverride(override).requiredValue() }
            correction("overrideDeleteCorrection") { graph.api.settings.deleteDateOverride(override.date).requiredValue() }
            var leave: LeaveEntry? = null
            correction("leaveCreateCorrection") { leave = graph.api.settings.saveLeave(SaveLeave(period=LeavePeriod.Timed(LocalAccessFixture.at,LocalAccessFixture.at+60_000),note="a")).requiredValue() }
            correction("leaveOnlyNoteCorrection") { graph.api.settings.saveLeave(SaveLeave(leave!!.id,LeavePeriod.Timed(leave!!.startInclusiveEpochMillis,leave!!.endExclusiveEpochMillis),"b")).requiredValue() }
            correction("leaveIdenticalNoop",0) { graph.api.settings.saveLeave(SaveLeave(leave!!.id,LeavePeriod.Timed(leave!!.startInclusiveEpochMillis,leave!!.endExclusiveEpochMillis),"b")).requiredValue() }
            correction("leaveDeleteCorrection") { graph.api.settings.deleteLeave(leave!!.id).requiredValue() }
            val output = File("build/qa/layer-boundaries/history-settings-$unrelatedCalls.json")
            checkNotNull(output.parentFile).mkdirs()
            output.writeText(observations.toString(2))
            File(output.parentFile, "sql-history-settings-$unrelatedCalls.json").writeText(sqlObservations.toString(2))
        } finally {
            graph.close()
        }
    }
}
