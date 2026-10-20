package com.greatwall.callring.data

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.platform.UiNotificationEntryPoints
import java.time.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CallAccessContractTest {
    @Test fun thousand() = access(1000)

    @Test fun tenThousand() = access(10000)

    private fun access(size: Int) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "calling-access-$size.db"
        SqliteBackendStore(context, name).use { LocalAccessFixture.seed(it, size) }
        val probe = SqlAccessProbe()
        val config =
            BackendConfiguration(
                AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM),
                databaseName = name,
                sqlAccessProbe = probe,
                clock =
                    Clock.fixed(
                        Instant.ofEpochMilli(LocalAccessFixture.at + 60000),
                        ZoneOffset.UTC,
                    ),
                dispatcher = Dispatchers.Unconfined,
            )
        val notifications =
            object : UiNotificationEntryPoints {
                override fun home(requestCode: Int): PendingIntent =
                    PendingIntent.getActivity(
                        context,
                        requestCode,
                        Intent("caller-test"),
                        PendingIntent.FLAG_IMMUTABLE,
                    )

                override fun playback(preview: Boolean, stopAction: PendingIntent): Notification =
                    Notification.Builder(context, "synthetic").build()
            }
        val graph =
            BackendGraph(
                context,
                config,
                notifications,
                object : IncomingCallUiSink {
                    override fun show(request: IncomingCallUiRequest) = Unit
                },
            )
        try {
            graph.api.callers.getEditor(LocalAccessFixture.TARGET).requiredValue()
            val observations = org.json.JSONObject().put("unrelatedCallCount", size)
            val sqlObservations = org.json.JSONObject().put("unrelatedCallCount", size)
            fun unrelated() =
                android.database.sqlite.SQLiteDatabase.openDatabase(
                        context.getDatabasePath(name).absolutePath,
                        null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                    )
                    .use { db ->
                        fun facts(table: String) =
                            db.rawQuery(
                                    "SELECT * FROM $table WHERE number=? ORDER BY rowid",
                                    arrayOf(LocalAccessFixture.UNRELATED),
                                )
                                .use { c ->
                                    buildList {
                                        while (c.moveToNext()) add(
                                            c.columnNames.associateWith { column ->
                                                val index = c.getColumnIndexOrThrow(column)
                                                if (c.isNull(index)) null else c.getString(index)
                                            },
                                        )
                                    }
                                }
                        facts("callers") to facts("calls")
                    }
            val untouched = unrelated()
            fun measure(label: String, action: () -> Unit) {
                val before = graph.businessReadCounters()
                val revision = graph.coordinator.marker()
                val prior = probe.snapshot().associateBy { it.tag to it.tables }
                action()
                val windows =
                    probe.snapshot().mapNotNull { row ->
                        val old = prior[row.tag to row.tables]
                        val count = row.calls - (old?.calls ?: 0)
                        if (count == 0L) null
                        else
                            org.json
                                .JSONObject()
                                .put("tag", row.tag.name)
                                .put(
                                    "tables",
                                    org.json.JSONArray(row.tables.map { it.sqlName }.sorted()),
                                )
                                .put("queryCalls", count)
                                .put("consumedRows", row.consumedRows - (old?.consumedRows ?: 0))
                                .put("writtenRows", row.writtenRows - (old?.writtenRows ?: 0))
                    }
                observations.put(
                    label,
                    org.json
                        .JSONObject()
                        .put("revisionDelta", graph.coordinator.marker() - revision)
                        .put(
                            "ports",
                            org.json.JSONObject(
                                graph
                                    .businessReadCounters()
                                    .filterKeys {
                                        it !in
                                            setOf(
                                                "coreOpens",
                                                "coreStateLoads",
                                                "completeCaptures",
                                                "classifications",
                                                "sourceQueries",
                                                "runtimeProbes",
                                                "quietReads",
                                                "quietWrites",
                                            )
                                    }
                                    .mapValues { (key, value) -> value - (before[key] ?: 0) },
                            ),
                        )
                        .put(
                            "fullStateLoads",
                            graph.businessReadCounters().getValue("coreStateLoads") -
                                before.getValue("coreStateLoads"),
                        ),
                )
                sqlObservations.put(label, org.json.JSONArray(windows))
                val output = java.io.File("build/qa/layer-boundaries/calling-access-$size.json")
                output.parentFile!!.mkdirs()
                output.writeText(observations.toString(2))
                java.io
                    .File(output.parentFile, "sql-calling-$size.json")
                    .writeText(sqlObservations.toString(2))
                if (label == "coldDecisionInputs") {
                    assertTrue(
                        windows.none { it.getJSONArray("tables").toString().contains("calls") },
                    )
                    assertEquals(
                        0L,
                        windows
                            .filter { it.getJSONArray("tables").toString().contains("callers") }
                            .sumOf { it.getLong("consumedRows") },
                    )
                }
                if (label == "import")
                    assertEquals(
                        "new unlinked provider needs no redundant UPDATE",
                        2L,
                        windows
                            .filter {
                                it.getString("tag") == "DIRECT_UPDATE" &&
                                    it.getJSONArray("tables").toString().contains("calls")
                            }
                            .sumOf { it.getLong("writtenRows") },
                    )
                assertEquals(label, untouched, unrelated())
                assertEquals(
                    label,
                    before.getValue("coreStateLoads"),
                    graph.businessReadCounters().getValue("coreStateLoads"),
                )
                assertEquals(
                    label,
                    before.getValue("completeCaptures"),
                    graph.businessReadCounters().getValue("completeCaptures"),
                )
                assertTrue(
                    label,
                    windows
                        .filter { it.getJSONArray("tables").toString().contains("calls") }
                        .sumOf { it.getLong("consumedRows") } < 20,
                )
            }
            val engine = graph.backend
            var id = 0L
            measure("record") {
                id =
                    engine.recordIncoming(
                        LocalAccessFixture.TARGET,
                        LocalAccessFixture.at + 2000,
                        CallDecision.NORMAL_RINGING,
                    )
            }
            measure("duplicate") {
                assertEquals(
                    id,
                    engine.recordIncoming(
                        LocalAccessFixture.TARGET,
                        LocalAccessFixture.at + 2000,
                        CallDecision.NORMAL_RINGING,
                    ),
                )
            }
            measure("decision") { engine.updateDecision(id, CallDecision.QUIET_SILENCED) }
            measure("decisionNoop") { engine.updateDecision(id, CallDecision.QUIET_SILENCED) }
            measure("import") {
                engine.importSystemCalls(
                    listOf(
                        com.greatwall.callring.domain.SystemCallRecord(
                            "measured",
                            LocalAccessFixture.TARGET,
                            LocalAccessFixture.at + 2001,
                            30,
                            com.greatwall.callring.domain.model.CallResult.ANSWERED,
                        ),
                    ),
                )
            }
            measure("statisticsStart") { assertEquals(0L, engine.statisticsStart()) }
            measure("pending") { engine.hasRecentPending(LocalAccessFixture.at + 2000) }
            measure("coldDecisionInputs") { engine.refreshDecisionSnapshot() }
            val snapshot = engine.decisionSnapshot()
            measure("warmDecisionUnchanged") {
                engine.updateDecision(id, CallDecision.WHITELIST_ALLOWED)
            }
            assertSame(snapshot, engine.decisionSnapshot())
            measure("importNoop") {
                engine.importSystemCalls(
                    listOf(
                        com.greatwall.callring.domain.SystemCallRecord(
                            "measured",
                            LocalAccessFixture.TARGET,
                            LocalAccessFixture.at + 2001,
                            30,
                            com.greatwall.callring.domain.model.CallResult.ANSWERED,
                        ),
                    ),
                )
            }
            assertSame(snapshot, engine.decisionSnapshot())
        } finally {
            graph.close()
        }
    }
}
