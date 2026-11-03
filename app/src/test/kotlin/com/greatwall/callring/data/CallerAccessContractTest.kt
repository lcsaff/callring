package com.greatwall.callring.data

import com.greatwall.callring.data.database.SqlAccessProbe
import com.greatwall.callring.data.database.SqlTable
import com.greatwall.callring.data.database.SqliteCoreStorageResource
import com.greatwall.callring.data.database.values

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
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
class CallerAccessContractTest {
    @Test fun thousand() = access(1000)

    @Test fun tenThousand() = access(10000)

    private fun access(size: Int) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "caller-access-$size.db"
        SqliteCoreStorageResource(context, name).use { LocalAccessFixture.seed(it, size) }
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
            BackendGraph(com.greatwall.callring.bootstrap.BackupGraphOwner { _, _ -> throw AssertionError("Unexpected fixture restore") },
                context,
                config,
                notifications,
                object : IncomingCallUiSink {
                    override fun show(request: IncomingCallUiRequest) = Unit
                },
            )
        try {
            val before = graph.businessReadCounters().getValue("completeCaptures")
            assertEquals(
                "Synthetic A",
                graph.api.callers.getEditor(LocalAccessFixture.TARGET).requiredValue().draft.name,
            )
            assertEquals(
                "editor must not load full state",
                before,
                graph.businessReadCounters().getValue("completeCaptures"),
            )
            assertTrue(probe.snapshot().none { SqlTable.CALLS in it.tables })

            val observations = org.json.JSONObject().put("unrelatedCallCount", size)
            val sqlObservations =
                org.json
                    .JSONObject()
                    .put("unrelatedCallCount", size)
                    .put("measurement", "query invocations / consumed rows, not engine scan rows")
            fun unrelated() =
                SqliteCoreStorageResource(context, name).use { store ->
                    store.fullSnapshot.capture().let { state ->
                        state.profiles[LocalAccessFixture.UNRELATED] to
                            state.calls.values
                                .filter { it.number == LocalAccessFixture.UNRELATED }
                                .toList()
                    }
                }
            val unchanged = unrelated()
            suspend fun measure(label: String, calls: Boolean = false, action: suspend () -> Unit) {
                val prior = probe.snapshot().associateBy { it.tag to it.tables }
                val counters = graph.businessReadCounters()
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
                val captureDelta = graph.businessReadCounters().getValue("completeCaptures") -
                    counters.getValue("completeCaptures")
                assertEquals(label, 0L, captureDelta)
                if (!calls)
                    assertTrue(
                        label,
                        windows.none { it.getJSONArray("tables").toString().contains("calls") },
                    )
                observations.put(
                    label,
                    org.json
                        .JSONObject()
                        .put("completeCaptures", captureDelta)
                        .put("revisionDelta", graph.coordinator.marker() - revision),
                )
                sqlObservations.put(label, org.json.JSONArray(windows))
                assertEquals(unchanged, unrelated())
            }
            measure("editor") {
                graph.api.callers.getEditor(LocalAccessFixture.TARGET).requiredValue()
            }
            measure("emptySearch") {
                assertEquals(
                    2L,
                    graph.api.callers.search(CallerSearch()).requiredValue().totalItems,
                )
            }
            measure("textSearch") {
                assertEquals(
                    2L,
                    graph.api.callers
                        .search(CallerSearch(" Synthetic "))
                        .requiredValue()
                        .totalItems,
                )
            }
            measure("save") {
                graph.api.callers
                    .saveProfile(
                        SaveCallerProfile(LocalAccessFixture.TARGET, "Measured A", "Fixture", 1),
                    )
                    .requiredValue()
            }
            measure("noop") {
                graph.api.callers
                    .saveProfile(
                        SaveCallerProfile(LocalAccessFixture.TARGET, "Measured A", "Fixture", 1),
                    )
                    .requiredValue()
            }
            measure("detail", true) {
                assertEquals(1L, graph.api.callers.getCallDetail(1).requiredValue().id)
            }
            measure("latest", true) {
                assertEquals(size + 1L, graph.api.callers.getLatestCall().requiredValue()!!.id)
            }
            measure("overview", true) {
                val o =
                    graph.api.callers
                        .getOverview(OverviewQuery(LocalAccessFixture.TARGET))
                        .requiredValue()
                assertEquals(1L, o.lifetimeCallCount)
                assertEquals(1L, o.latestCall!!.id)
            }
            measure("impact", true) {
                assertEquals(
                    1,
                    graph.api.callers
                        .numberDataImpact(LocalAccessFixture.TARGET)
                        .requiredValue()
                        .callCount,
                )
            }
            val output = java.io.File("build/qa/layer-boundaries/caller-access-$size.json")
            output.parentFile!!.mkdirs()
            output.writeText(observations.toString(2))
            java.io
                .File(output.parentFile, "sql-caller-$size.json")
                .writeText(sqlObservations.toString(2))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val counts = mutableMapOf<String, Int>()
                val callers =
                    object : CallersApi by graph.api.callers {
                        override suspend fun getEditor(
                            number: String?,
                        ): com.greatwall.callring.contracts.common.ApiResult<CallerEditorData> {
                            counts[number.orEmpty()] = (counts[number.orEmpty()] ?: 0) + 1
                            return graph.api.callers.getEditor(number)
                        }
                    }
                var reportA = 0
                var reportB = 0
                val reports =
                    object :
                        com.greatwall.callring.contracts.report.ReportsApi by graph.api.reports {
                        override suspend fun load(
                            query: com.greatwall.callring.contracts.report.ReportQuery,
                        ): com.greatwall.callring.contracts.common.ApiResult<
                            com.greatwall.callring.contracts.report.CallerReport,
                        > {
                            if (query.number == LocalAccessFixture.TARGET) reportA++ else reportB++
                            return graph.api.reports.load(query)
                        }
                    }
                val editorA =
                    com.greatwall.callring.ui.feature.callers.CallerEditorViewModel(
                        LocalAccessFixture.TARGET,
                        callers,
                        graph.api.changes,
                        androidx.lifecycle.SavedStateHandle(),
                        scope,
                        graph.api.edits::profile,
                    )
                val editorB =
                    com.greatwall.callring.ui.feature.callers.CallerEditorViewModel(
                        LocalAccessFixture.UNRELATED,
                        callers,
                        graph.api.changes,
                        androidx.lifecycle.SavedStateHandle(),
                        scope,
                        graph.api.edits::profile,
                    )
                val range =
                    com.greatwall.callring.contracts.common.DateRange(
                        LocalDate.of(2026, 10, 19),
                        LocalDate.of(2026, 10, 19),
                        "UTC",
                    )
                val a =
                    com.greatwall.callring.ui.feature.report.ReportViewModel(
                        LocalAccessFixture.TARGET,
                        reports,
                        graph.api.changes,
                        androidx.lifecycle.SavedStateHandle(),
                        range,
                        scope,
                    )
                val b =
                    com.greatwall.callring.ui.feature.report.ReportViewModel(
                        LocalAccessFixture.UNRELATED,
                        reports,
                        graph.api.changes,
                        androidx.lifecycle.SavedStateHandle(),
                        range,
                        scope,
                    )
                assertTrue(
                    editorA.state.value.loaded &&
                        editorB.state.value.loaded &&
                        a.state.value.loaded &&
                        b.state.value.loaded,
                )
                editorA.edit { copy(name = "Edited A") }
                editorA.save()
                withTimeout(5000) { while (editorA.mutation.value.busy) delay(1) }
                assertEquals(
                    "one commit reloads A editor once",
                    2,
                    counts[LocalAccessFixture.TARGET],
                )
                assertEquals(1, counts[LocalAccessFixture.UNRELATED])
                assertEquals(2, reportA)
                assertEquals(1, reportB)
                editorA.save()
                withTimeout(5000) { while (editorA.mutation.value.busy) delay(1) }
                assertEquals("noop does not reload", 2, counts[LocalAccessFixture.TARGET])
                assertEquals(2, reportA)
                assertEquals(1, reportB)
                graph.api.settings
                    .savePosition(SavePosition(name = "unreferenced", level = 2, active = true))
                    .requiredValue()
                withTimeout(5000) {
                    while (
                        counts[LocalAccessFixture.TARGET] != 3 ||
                            counts[LocalAccessFixture.UNRELATED] != 2
                    ) delay(1)
                }
                assertEquals("reports do not load unreferenced role options", 2, reportA)
                assertEquals(1, reportB)
            } finally {
                scope.cancel()
            }
        } finally {
            graph.close()
        }
    }
}
