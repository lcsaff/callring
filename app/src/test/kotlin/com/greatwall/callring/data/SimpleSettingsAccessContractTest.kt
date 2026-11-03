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
class SimpleSettingsAccessContractTest {
    @Test fun localOperationsPreserveResultsWithOneThousandUnrelatedCalls() = characterize(1_000)
    @Test fun localOperationsPreserveResultsWithTenThousandUnrelatedCalls() = characterize(10_000)

    private fun characterize(unrelatedCalls: Int) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "simple-settings-$unrelatedCalls.db"
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
        val graph = BackendGraph(com.greatwall.callring.bootstrap.BackupGraphOwner { _, _ -> throw AssertionError("Unexpected fixture restore") }, context, configuration, notifications, object : IncomingCallUiSink {
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
            measure("appearanceWrite") {
                graph.api.settings.saveAppearance(configuration.defaultAppearance.copy(presetId = "new")).requiredValue()
            }
            assertTrue("appearance must not issue core SQL", sqlProbe.snapshot().isEmpty())
            assertEquals("appearance must not even open core", 0L, graph.businessReadCounters().getValue("coreOpens"))
            assertThrows(IllegalStateException::class.java) { graph.backend.decisionSnapshot() }
            val decision = graph.backend.refreshDecisionSnapshot() // explicit initialization is outside measured ordinary operations
            measure("scoreRead") { assertEquals(ScoreSettings(5, 2, 10, 30), graph.api.settings.getScoreSettings().requiredValue()) }
            measure("scoreWrite") { graph.api.settings.saveScoreSettings(ScoreSettings(6, 2, 10, 30)).requiredValue() }
            measure("scoreNoop") { graph.api.settings.saveScoreSettings(ScoreSettings(6, 2, 10, 30)).requiredValue() }
            measure("scoreEdit") { graph.api.edits.score(ScoreSettings(6, 2, 10, 30), ScoreSettings(6, 3, 10, 30)).requiredValue() }
            assertSame("score cannot rebuild decision inputs", decision, graph.backend.decisionSnapshot())
            measure("overlayNoop") { graph.api.settings.setOverlayEnabled(false).requiredValue() }
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
            measure("overlayEnable") { graph.api.settings.setOverlayEnabled(true).requiredValue() }
            assertTrue(graph.backend.decisionSnapshot().inputs.overlayEnabled)
            measure("overlayDisable") { graph.api.settings.setOverlayEnabled(false).requiredValue() }
            assertFalse(graph.backend.decisionSnapshot().inputs.overlayEnabled)
            measure("statisticsDisable") { graph.api.settings.setStatisticsEnabled(false).requiredValue() }
            measure("statisticsEnable") { graph.api.settings.setStatisticsEnabled(true).requiredValue() }
            measure("statisticsNoop") { graph.api.settings.setStatisticsEnabled(true).requiredValue() }
            val statisticsDecision = graph.backend.decisionSnapshot()
            assertEquals(2, statisticsDecision.inputs.statisticsIntervals.size)
            assertNotNull(statisticsDecision.inputs.statisticsIntervals.first().endMillis)
            assertNull(statisticsDecision.inputs.statisticsIntervals.last().endMillis)
            measure("positionsRead") { graph.api.settings.getPositions().requiredValue() }
            var position: PositionOption? = null
            measure("positionCreate") { position = graph.api.settings.savePosition(SavePosition(null, " New ", 4, true)).requiredValue() }
            measure("positionNoop") { graph.api.settings.savePosition(SavePosition(position!!.id, "New", 4, true)).requiredValue() }
            measure("positionEdit") { graph.api.edits.position(SavePosition(position!!.id, "New", 4, true), SavePosition(position!!.id, "New", 5, true)).requiredValue() }
            measure("myPosition") { graph.api.settings.setMyPosition(position!!.id).requiredValue() }
            measure("myPositionNoop") { graph.api.settings.setMyPosition(position!!.id).requiredValue() }
            measure("myPositionClear") { graph.api.settings.setMyPosition(null).requiredValue() }
            measure("positionDelete") { graph.api.settings.deletePosition(position!!.id).requiredValue() }
            assertSame("positions cannot rebuild decision inputs", statisticsDecision, graph.backend.decisionSnapshot())
            measure("summaryRead") { assertEquals(ScoreSettings(6, 3, 10, 30), graph.api.settings.getSummary().requiredValue().score) }
            measure("widgetWrite") { graph.api.widgets.saveBinding(17, "30001").requiredValue() }
            measure("widgetNoop") { graph.api.widgets.saveBinding(17, "30001").requiredValue() }
            measure("widgetMissingRemoveNoop") { graph.api.widgets.removeBinding(18).requiredValue() }
            measure("widgetRead") { assertEquals("30001", graph.api.widgets.getBinding(17).requiredValue()!!.number) }
            measure("widgetDelete") { graph.api.widgets.removeBinding(17).requiredValue() }
            val recordingStore = com.greatwall.callring.data.recording.AndroidRecordingStore(context, "narrow-config-$unrelatedCalls",
                object : com.greatwall.callring.data.recording.RecordingCipher {
                    override fun encrypt(value: String) = value
                    override fun decrypt(value: String) = value
                }, recordingVersion = { graph.coordinator.capture().values.getValue("recording") })
            recordingStore.use {
                val settings = com.greatwall.callring.application.recording.AiSettingsService(recordingStore, graph.coordinator, { true }, { _, _ -> },
                    folderView = { com.greatwall.callring.contracts.recording.RecordingFolderView(null, com.greatwall.callring.contracts.runtime.AccessState.NOT_REQUIRED) }, operationActive = { true })
                measure("recordingConfigRead") { settings.getRecordingView().requiredValue() }
                measure("recordingConfigWrite") { settings.patchRecording(settings.getRecordingView().requiredValue(),
                    com.greatwall.callring.contracts.recording.RecordingSettingsPatch(recordingEnabled = com.greatwall.callring.contracts.ai.PatchField.Set(true))).requiredValue() }
                measure("recordingConfigNoop") { settings.patchRecording(settings.getRecordingView().requiredValue(), com.greatwall.callring.contracts.recording.RecordingSettingsPatch()).requiredValue() }
                measure("aiConfigWrite") { settings.patchAi(settings.getAiView().requiredValue(), com.greatwall.callring.contracts.ai.AiSettingsPatch(
                    budget = com.greatwall.callring.contracts.ai.PatchField.Set(com.greatwall.callring.contracts.ai.ConversationBudget(65536, 4096)))).requiredValue() }
                measure("aiConfigNoop") { settings.patchAi(settings.getAiView().requiredValue(), com.greatwall.callring.contracts.ai.AiSettingsPatch()).requiredValue() }
            }
            val output = File("build/qa/layer-boundaries/settings-access-$unrelatedCalls.json")
            checkNotNull(output.parentFile).mkdirs()
            output.writeText(observations.toString(2))
            File(output.parentFile, "sql-settings-$unrelatedCalls.json").writeText(sqlObservations.toString(2))
        } finally {
            graph.close()
        }
    }
}
