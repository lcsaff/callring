package com.greatwall.callring.data

import com.greatwall.callring.data.database.QueryTag
import com.greatwall.callring.data.database.SqlAccessProbe
import com.greatwall.callring.data.database.SqlTable
import com.greatwall.callring.data.database.SqliteCoreStorageResource

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONObject
import com.greatwall.callring.contracts.common.ApiResult
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
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
    private val internationalDigits = "8613800138000"

    @Test fun plusStoredNumberMatchesBothRecordingSpellingsAtAssociationBoundaries() {
        val context = RuntimeEnvironment.getApplication()
        SqliteCoreStorageResource(context, "recording-plus-boundaries").use { resource ->
            LocalAccessFixture.seed(resource, 1_000)
            resource.fixtureSql { state ->
                state.seedCaller(com.greatwall.callring.domain.model.ProfileRecord("+$internationalDigits"))
                state.seedCall(state.callFact(1)!!.copy(number = "+$internationalDigits"))
            }
            for (number in listOf(internationalDigits, "+$internationalDigits")) {
                for (offset in listOf(-3_000L, 3_000L, 8_000L, 120_000L))
                    assertEquals("$number/$offset", listOf(1L),
                        com.greatwall.callring.application.recording.findSystemRecordingCalls(
                            resource.sessions, number, LocalAccessFixture.at + offset).map { it.id })
                for (offset in listOf(-3_001L, 120_001L))
                    assertTrue("$number/$offset", com.greatwall.callring.application.recording.findSystemRecordingCalls(
                        resource.sessions, number, LocalAccessFixture.at + offset).isEmpty())
            }
        }
    }

    @Test fun nearPlusCallWinsBeforeDelayedDigitsFallbackAndUsesStableScreeningId() {
        val context = RuntimeEnvironment.getApplication()
        SqliteCoreStorageResource(context, "recording-plus-priority").use { resource ->
            LocalAccessFixture.seed(resource, 1_000)
            resource.fixtureSql { state ->
                state.seedCaller(com.greatwall.callring.domain.model.ProfileRecord(internationalDigits))
                state.seedCaller(com.greatwall.callring.domain.model.ProfileRecord("+$internationalDigits"))
                state.seedCall(state.callFact(1)!!.copy(number = internationalDigits))
                state.seedCall(state.callFact(2)!!.copy(number = "+$internationalDigits",
                    time = LocalAccessFixture.at + 30_000, linkedScreenId = 3L))
                state.seedCall(state.callFact(3)!!.copy(hidden = true))
            }
            assertEquals(listOf(3L), com.greatwall.callring.application.recording.findSystemRecordingCalls(
                resource.sessions, internationalDigits, LocalAccessFixture.at + 31_000).map { it.id })
        }
    }

    @Test fun plusAndDigitsCandidatesRemainAmbiguousWithBoundedReads() {
        val context = RuntimeEnvironment.getApplication()
        val probe = SqlAccessProbe()
        SqliteCoreStorageResource(context, "recording-plus-ambiguous", probe).use { resource ->
            LocalAccessFixture.seed(resource, 1_000)
            resource.fixtureSql { state ->
                state.seedCaller(com.greatwall.callring.domain.model.ProfileRecord(internationalDigits))
                state.seedCaller(com.greatwall.callring.domain.model.ProfileRecord("+$internationalDigits"))
                for (id in 1L..2L) state.seedCall(state.callFact(id)!!.copy(
                    number = if (id % 2L == 0L) "+$internationalDigits" else internationalDigits,
                    time = LocalAccessFixture.at))
            }
            assertEquals(setOf(1L, 2L), com.greatwall.callring.application.recording.findSystemRecordingCalls(
                resource.sessions, internationalDigits, LocalAccessFixture.at).map { it.id }.toSet())
            resource.fixtureSql { state ->
                for (id in 3L..8L) state.seedCall(state.callFact(id)!!.copy(
                    number = if (id % 2L == 0L) "+$internationalDigits" else internationalDigits,
                    time = LocalAccessFixture.at))
            }
            val before = probe.snapshot().sumOf { it.consumedRows }
            val matches = com.greatwall.callring.application.recording.findSystemRecordingCalls(
                resource.sessions, internationalDigits, LocalAccessFixture.at)
            assertEquals(2, matches.size)
            assertEquals(2, matches.map { it.id }.distinct().size)
            assertTrue(probe.snapshot().sumOf { it.consumedRows } - before <= 4L)
        }
    }

    @Test fun incomingRecordingCanStartAfterRingingButCannotPrecedeTheCall() {
        val context = RuntimeEnvironment.getApplication()
        SqliteCoreStorageResource(context, "recording-delay").use { resource ->
            LocalAccessFixture.seed(resource, 1_000)
            assertEquals(listOf(1L), com.greatwall.callring.application.recording.findSystemRecordingCalls(
                resource.sessions, LocalAccessFixture.TARGET, LocalAccessFixture.at + 8_000,
            ).map { it.id })
            assertEquals(listOf(1L), com.greatwall.callring.application.recording.findSystemRecordingCalls(
                resource.sessions, LocalAccessFixture.TARGET, LocalAccessFixture.at + 120_000,
            ).map { it.id })
            assertTrue(com.greatwall.callring.application.recording.findSystemRecordingCalls(
                resource.sessions, LocalAccessFixture.TARGET, LocalAccessFixture.at + 120_001,
            ).isEmpty())
            assertTrue(com.greatwall.callring.application.recording.findSystemRecordingCalls(
                resource.sessions, LocalAccessFixture.TARGET, LocalAccessFixture.at - 3001,
            ).isEmpty())
        }
    }
    @Test fun graphIdentityWithOneThousandUnrelatedCalls() = checkGraph(1_000)
    @Test fun graphIdentityWithTenThousandUnrelatedCalls() = checkGraph(10_000)
    private fun checkGraph(size: Int) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "recording-target-$size"
        SqliteCoreStorageResource(context, name).use { LocalAccessFixture.seed(it, size) }
        val lookupProbe = SqlAccessProbe()
        SqliteCoreStorageResource(context,name,lookupProbe).use { resource ->
            val matches = com.greatwall.callring.application.recording.findSystemRecordingCalls(resource.sessions,LocalAccessFixture.TARGET,LocalAccessFixture.at)
            assertEquals(listOf(1L),matches.map { it.id })
            assertEquals(1L,lookupProbe.snapshot().sumOf { it.consumedRows })
        }
        val probe = SqlAccessProbe()
        val config = BackendConfiguration(AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM),
            databaseName = name, sqlAccessProbe = probe)
        val notifications = object : UiNotificationEntryPoints {
            override fun home(requestCode: Int) = PendingIntent.getActivity(context, requestCode, Intent("fixture"), PendingIntent.FLAG_IMMUTABLE)
            override fun playback(preview: Boolean, stopAction: PendingIntent) = Notification()
        }
        BackendGraph(com.greatwall.callring.bootstrap.BackupGraphOwner { _, _ -> throw AssertionError("Unexpected fixture restore") }, context, config, notifications).use { graph ->
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
