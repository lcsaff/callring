package com.greatwall.callring.data

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONObject
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.platform.UiNotificationEntryPoints
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RecordingCallAccessTest {
    @Test fun graphIdentityWithOneThousandUnrelatedCalls() = checkGraph(1_000)
    @Test fun graphIdentityWithTenThousandUnrelatedCalls() = checkGraph(10_000)
    private fun checkGraph(size: Int) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "recording-target-$size"
        SqliteCoreStorageResource(context, name).use { LocalAccessFixture.seed(it, size) }
        val probe = SqlAccessProbe()
        val config = BackendConfiguration(AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM),
            databaseName = name, sqlAccessProbe = probe)
        val notifications = object : UiNotificationEntryPoints {
            override fun home(requestCode: Int) = PendingIntent.getActivity(context, requestCode, Intent("fixture"), PendingIntent.FLAG_IMMUTABLE)
            override fun playback(preview: Boolean, stopAction: PendingIntent) = Notification()
        }
        BackendGraph(context, config, notifications, object : IncomingCallUiSink {
            override fun show(request: IncomingCallUiRequest) = Unit
        }).use { graph ->
            val identity = graph.api.recordings.getIdentity(1).requiredValue()
            assertEquals(LocalAccessFixture.TARGET, identity.number)
            assertEquals(0L, graph.businessReadCounters().getValue("completeCaptures"))
            assertEquals(1L, probe.snapshot().filter { SqlTable.CALLS in it.tables }.sumOf { it.consumedRows })
            assertTrue(probe.snapshot().none { it.tag in setOf(QueryTag.LOAD_ROWS) })
            val observations = JSONObject().put("unrelatedCalls", size).put("targetCallConsumedRows", 1)
                .put("completeCaptures", graph.businessReadCounters().getValue("completeCaptures"))
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("UPDATE calls SET hidden=1 WHERE id=1")
                db.execSQL("UPDATE calls SET linked_screen_id=1 WHERE id=2")
                val beforeHidden = probe.snapshot().filter { SqlTable.CALLS in it.tables }.sumOf { it.consumedRows }
                val hidden = graph.api.recordings.getIdentity(1).requiredValue()
                assertEquals(1L, hidden.callId)
                assertEquals(LocalAccessFixture.UNRELATED, hidden.number)
                assertEquals(LocalAccessFixture.at + 1000, hidden.startedAtEpochMillis)
                observations.put("hiddenCallConsumedRows", probe.snapshot().filter { SqlTable.CALLS in it.tables }.sumOf { it.consumedRows } - beforeHidden)
                assertEquals(hidden, graph.api.recordings.getIdentity(2).requiredValue())
                assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) { db.execSQL("UPDATE calls SET linked_screen_id=1 WHERE id=3") }
                assertEquals(hidden, graph.api.recordings.getIdentity(2).requiredValue())
                db.execSQL("UPDATE calls SET hidden=1 WHERE id IN (2,3)")
                assertTrue(graph.api.recordings.getIdentity(1) is ApiResult.Failure)
            }
            assertTrue(graph.api.recordings.getIdentity(Long.MAX_VALUE) is ApiResult.Failure)
            assertTrue(graph.api.recordings.getIdentity(Long.MIN_VALUE) is ApiResult.Failure)
            assertTrue(graph.api.recordings.getIdentity(-123) is ApiResult.Failure)
            val independent = graph.recordings.registrations.registerIncoming("own", "30000", 100, null, null)
            graph.recordings.store.insertStatisticsLink(1, "own")
            assertEquals(independent.id, graph.api.recordings.getIdentity(1).requiredValue().callId)
            assertEquals(independent.id, graph.api.recordings.getIdentity(independent.id).requiredValue().callId)
            graph.recordings.store.delete(independent.id)
            assertNull(graph.api.recordings.getArchive(independent.id).requiredValue())
            assertEquals(0L, graph.businessReadCounters().getValue("completeCaptures"))
            assertTrue(probe.snapshot().none { it.tag in setOf(QueryTag.LOAD_ROWS) })
            File("build/qa/layer-boundaries/t10b1-call-$size.json").apply { parentFile!!.mkdirs(); writeText(observations.toString(2)) }
            Unit

        }
    }
}
