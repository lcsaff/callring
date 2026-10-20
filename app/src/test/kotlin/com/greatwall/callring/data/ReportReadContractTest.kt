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
class ReportReadContractTest {
    @Test fun thousand() = access(1000)

    @Test fun tenThousand() = access(10000)

    private fun access(size: Int) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "report-home-access-$size.db"
        SqliteBackendStore(context, name).use { store ->
            LocalAccessFixture.seed(store, size)
            val current = store.read { it.calls.getValue(1) }
            store.sessions.writeCalling { writer ->
                val original =
                    current.originalClassification.copy(
                        reason = com.greatwall.callring.domain.model.WorkReason.HOLIDAY,
                        holidayName = "Original holiday",
                        configVersion = 1,
                    )
                val screen =
                    writer.insertCallingCall(
                        current.copy(
                            id = 0,
                            time = LocalAccessFixture.at - 100,
                            logId = null,
                            hidden = true,
                            counted = false,
                            linkedScreenId = null,
                            decision = com.greatwall.callring.domain.model.CallDecision.UNKNOWN,
                            decisionNote = "legacy decision",
                            legacyDecision = "legacy decision",
                            originalClassification = original,
                        ),
                    )
                writer.updateCallingCalls(
                    listOf(current to current.copy(linkedScreenId = screen.id)),
                )
                writer.ensureCallingProfile("31-234")
                writer.insertCallingCall(
                    current.copy(
                        id = 0,
                        number = "31-234",
                        time = current.time + 100,
                        logId = "variant-100002",
                    ),
                )
            }
        }
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
            val observations = org.json.JSONObject().put("unrelatedCallCount", size)
            val sqlObservations =
                org.json
                    .JSONObject()
                    .put("measurement", "query invocations / consumed rows, not engine scan rows")
            fun auditUnrelated(): List<List<String?>> =
                android.database.sqlite.SQLiteDatabase.openDatabase(
                        context.getDatabasePath(name).absolutePath,
                        null,
                        android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                    )
                    .use { db ->
                        listOf(
                                "SELECT * FROM calls WHERE number='39999' ORDER BY id",
                                "SELECT * FROM callers WHERE number='39999'",
                                "SELECT * FROM calendar_audit ORDER BY id",
                            )
                            .flatMap { statement ->
                                db.rawQuery(statement, null).use { cursor ->
                                    buildList {
                                        while (cursor.moveToNext()) add(
                                            (0 until cursor.columnCount).map {
                                                if (cursor.isNull(it)) null
                                                else cursor.getString(it)
                                            },
                                        )
                                    }
                                }
                            }
                    }
            val unrelatedBefore = auditUnrelated()
            suspend fun measure(label: String, action: suspend () -> Unit) {
                val counters = graph.businessReadCounters()
                val prior = probe.snapshot().associateBy { it.tag to it.tables }
                val revision = graph.coordinator.marker()
                action()
                val windows =
                    probe.snapshot().mapNotNull { row ->
                        val old = prior[row.tag to row.tables]
                        val invocations = row.calls - (old?.calls ?: 0)
                        if (invocations == 0L) null
                        else
                            org.json
                                .JSONObject()
                                .put("tag", row.tag.name)
                                .put(
                                    "tables",
                                    org.json.JSONArray(row.tables.map { it.sqlName }.sorted()),
                                )
                                .put("queryCalls", invocations)
                                .put("consumedRows", row.consumedRows - (old?.consumedRows ?: 0))
                                .put("writtenRows", row.writtenRows - (old?.writtenRows ?: 0))
                    }
                assertEquals(
                    label,
                    0L,
                    graph.businessReadCounters().getValue("coreStateLoads") -
                        counters.getValue("coreStateLoads"),
                )
                assertEquals(
                    label,
                    counters.getValue("completeCaptures"),
                    graph.businessReadCounters().getValue("completeCaptures"),
                )
                assertEquals(label, revision, graph.coordinator.marker())
                val callRows =
                    windows
                        .filter { it.getJSONArray("tables").toString().contains("calls") }
                        .sumOf { it.getLong("consumedRows") }
                assertTrue("$label consumed unrelated history: $callRows", callRows <= 4)
                if (label == "home")
                    assertTrue(
                        windows.none { row ->
                            val tables = row.getJSONArray("tables").toString()
                            tables.contains("callers") || tables.contains("positions")
                        },
                    )
                val narrow = org.json.JSONObject()
                graph.businessReadCounters().forEach { (key, value) ->
                    val delta = value - (counters[key] ?: 0L)
                    if (delta != 0L) narrow.put(key, delta)
                }
                observations.put(
                    label,
                    org.json
                        .JSONObject()
                        .put("fullStateLoads", 0)
                        .put("completeCaptures", 0)
                        .put("revisionDelta", 0)
                        .put("narrowAccess", narrow),
                )
                sqlObservations.put(label, org.json.JSONArray(windows))
                assertEquals(
                    "all B persisted columns/profile and audit unchanged",
                    unrelatedBefore,
                    auditUnrelated(),
                )
            }
            val range =
                com.greatwall.callring.contracts.common.DateRange(
                    LocalDate.of(2026, 10, 19),
                    LocalDate.of(2026, 10, 19),
                    "UTC",
                )
            measure("report") {
                val report =
                    graph.api.reports
                        .load(
                            com.greatwall.callring.contracts.report.ReportQuery(
                                LocalAccessFixture.TARGET,
                                range,
                            ),
                        )
                        .requiredValue()
                assertEquals(1L, report.details.totalItems)
                assertEquals(1L, report.totals.totalCalls)
                val detail = report.details.items.single()
                assertEquals(LocalAccessFixture.at - 100, detail.screeningAtEpochMillis)
                assertEquals(CallSource.MERGED, detail.source)
                assertEquals(CallDecision.UNKNOWN, detail.decision)
                assertEquals("legacy decision", detail.decisionNote)
                assertEquals("Original holiday", detail.originalClassification.holidayName)
                assertTrue(detail.classification.working)
                assertTrue(detail.corrected)
            }
            measure("home") { assertNotNull(graph.api.home.load().requiredValue().latestCall) }
            val uri = android.net.Uri.parse("content://report-test/$size.csv")
            var documentWrites = 0
            val output =
                object : java.io.ByteArrayOutputStream() {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        if (
                            length == 3 &&
                                bytes[offset] == 0xef.toByte() &&
                                bytes[offset + 1] == 0xbb.toByte() &&
                                bytes[offset + 2] == 0xbf.toByte()
                        )
                            documentWrites++
                        super.write(bytes, offset, length)
                    }
                }
            org.robolectric.Shadows.shadowOf(context.contentResolver)
                .registerOutputStream(uri, output)
            measure("csv") {
                val receipt =
                    graph.api.reports
                        .exportCsv(
                            com.greatwall.callring.contracts.report.ReportExportQuery(
                                LocalAccessFixture.TARGET,
                                range,
                            ),
                            com.greatwall.callring.contracts.common.DocumentRef(uri.toString()),
                        )
                        .requiredValue()
                assertEquals(1L, receipt.recordCount)
                assertEquals("one actual document stream write", 1, documentWrites)
                assertTrue(output.toString("UTF-8").endsWith("\r\n"))
            }
            java.io.File("build/qa").mkdirs()
            java.io
                .File("build/qa/report-home-access-$size.json")
                .writeText(observations.toString(2))
            java.io
                .File("build/qa/sql-report-home-$size.json")
                .writeText(sqlObservations.toString(2))
        } finally {
            graph.close()
        }
    }
}
