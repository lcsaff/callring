package com.greatwall.callring.bootstrap

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.contracts.callers.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.contracts.widget.*
import com.greatwall.callring.contracts.recording.*
import com.greatwall.callring.contracts.ai.*
import com.greatwall.callring.contracts.command.*
import com.greatwall.callring.application.requiredValue
import com.greatwall.callring.application.conversation.OperationScopePlanner
import com.greatwall.callring.application.callers.NumberDeletionService
import com.greatwall.callring.application.callers.NumberDeletionRecordings
import com.greatwall.callring.platform.UiNotificationEntryPoints
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NumberDeletionIntegrationTest {
    @Test fun archiveFailureKeepsOriginalNewestFirstOrderAndStopsLaterSteps() = runBlocking {
        val graph = graph("delete_archive_order")
        try {
            graph.api.callers.saveProfile(SaveCallerProfile("31234", "keep", "unit", null)).requiredValue()
            graph.api.widgets.saveBinding(1, "31234").requiredValue()
            for ((id, started) in listOf(-1L to 300L, -2L to 300L, -3L to 100L)) {
                graph.recordings.store.compareAndSet(id, null, CallArchive(id, "31234", started, generation = 2, status = ArchiveStatus.READY))
            }
            val visited = mutableListOf<Long>()
            val result = deletion(graph, recordingDelete = { id, generation, cmd -> if (id == -1L) { visited += id; ApiResult.Failure(ApiError(ErrorCode.IO_FAILURE, "fixture.failure")) } else { visited += id; graph.recordings.service.delete(id, generation, cmd) } })
                .delete(DeleteNumberCommand("31234", 0, 3, 1))
            assertTrue(result is ApiResult.Failure)
            assertEquals(listOf(-2L, -1L), visited)
            assertNull(graph.recordings.store.get(-2))
            assertNotNull(graph.recordings.store.get(-3))
            assertNotNull(graph.recordings.store.get(-1))
            assertEquals("keep", graph.api.callers.getEditor("31234").requiredValue().draft.name)
            assertNotNull(graph.api.widgets.getBinding(1).requiredValue())
        } finally { graph.close() }
    }

    @Test fun impactDoesNotUseExplicitArchiveListing() = runBlocking {
        val graph = graph("deletion_metadata")
        try {
            graph.recordings.registrations.registerIncoming("session", "31234", 1, null, null)
            assertEquals(1, deletion(graph).impact("31234").requiredValue().archiveCount)
            val counts = graph.recordings.store.readCounters.snapshot()
            assertEquals(0L, counts["fullArchiveDecodes"] ?: 0L)
            assertEquals(0L, counts["listArchiveDecodes"] ?: 0L)
        } finally { graph.close() }
    }

    @Test fun foreignProfileEditAfterArchiveCleanupMustBePreserved() = runBlocking {
        val graph = graph("delete_profile_edited")
        try {
            graph.api.callers.saveProfile(SaveCallerProfile("31234", "original", "unit", null)).requiredValue()
            graph.recordings.registrations.registerIncoming("session", "31234", 1, null, null)
            val call = ConversationToolCall("c", "callers_deleteNumber", aiObject("number" to aiString("31234")))
            val scopes = OperationScopePlanner(graph.queries, graph.coordinator).prepare(call, 81)
            val command = CommandContext("delete", graph.coordinator.capture(), 1,
                expectedScopes = scopes, continueOwnCommits = true) { true }
            val result = deletion(graph, command = command, recordingDelete = { id, generation, cmd ->
                val result = graph.recordings.service.delete(id, generation, cmd)
                graph.api.callers.saveProfile(SaveCallerProfile("31234", "edited elsewhere", "unit", null)).requiredValue()
                result
            })
                .delete(DeleteNumberCommand("31234", 0, 1, 0))
            assertTrue(result is ApiResult.Failure)
            assertEquals("edited elsewhere", graph.api.callers.getEditor("31234").requiredValue().draft.name)
        } finally { graph.close() }
    }
    @Test fun deletionScopesProtectArchiveAndBindingCollectionMembership() = runBlocking {
        val graph = graph("delete_scopes")
        try {
            val call = ConversationToolCall("c", "callers_deleteNumber", aiObject("number" to aiString("31234")))
            val scopes = OperationScopePlanner(graph.queries, graph.coordinator).prepare(call, 81)
            assertTrue(scopes.keys.containsAll(setOf("recording", "preferences")))
            graph.api.widgets.saveBinding(1, "31234").requiredValue()
            val command = CommandContext("delete", graph.coordinator.capture(), 1, expectedScopes = scopes) { true }
            assertTrue(graph.api.commandApi(command).numberDeletion.delete(DeleteNumberCommand("31234", 0, 0, 1)) is ApiResult.Failure)
            assertEquals("31234", graph.api.widgets.getBinding(1).requiredValue()!!.number)
        } finally { graph.close() }
    }

    @Test fun foreignWidgetReassignmentAfterCoreDeletionMustBePreserved() = runBlocking {
        val graph = graph("delete_reassigned")
        try {
            graph.api.callers.saveProfile(SaveCallerProfile("31234", "name", "unit", null)).requiredValue()
            graph.api.widgets.saveBinding(1, "31234").requiredValue()
            val call = ConversationToolCall("c", "callers_deleteNumber", aiObject("number" to aiString("31234")))
            val scopes = OperationScopePlanner(graph.queries, graph.coordinator).prepare(call, 81)
            val command = CommandContext("delete", graph.coordinator.capture(), 1,
                expectedScopes = scopes, continueOwnCommits = true) { true }
            var lists = 0
            val result = deletion(graph, command = command, bindingsRead = { number ->
                val snapshot = targetBindings(graph, number)
                if (++lists == 2) graph.api.widgets.saveBinding(1, "39999").requiredValue()
                snapshot
            })
                .delete(DeleteNumberCommand("31234", 0, 0, 1))
            assertTrue(result is ApiResult.Failure)
            assertEquals("39999", graph.api.widgets.getBinding(1).requiredValue()!!.number)
        } finally { graph.close() }
    }
    @Test fun replacementWithSameCountAfterMetadataReadIsNotDeleted() = runBlocking {
        val graph = graph("delete_replaced_generation")
        try {
            graph.api.callers.saveProfile(SaveCallerProfile("31234", "keep", "unit", null)).requiredValue()
            graph.recordings.store.compareAndSet(-1, null, CallArchive(-1, "31234", 1, generation = 2, status = ArchiveStatus.READY))
            var reads = 0
            val service = deletion(graph, bindingsRead = { number ->
                if (++reads == 1) assertTrue(graph.recordings.store.compareAndSet(-1, 2,
                    CallArchive(-1, "31234", 1, generation = 3, status = ArchiveStatus.COMPLETE)))
                targetBindings(graph, number)
            })
            assertTrue(service.delete(DeleteNumberCommand("31234", 0, 1, 0)) is ApiResult.Failure)
            assertEquals(3L, graph.recordings.store.get(-1)!!.generation)
            assertEquals("keep", graph.api.callers.getEditor("31234").requiredValue().draft.name)
        } finally { graph.close() }
    }
    @Test fun guiDeletionRejectsForeignChangesBetweenArchiveAndCoreWithoutReplayingSuccess() = runBlocking {
        for (kind in listOf("archive", "profile", "call", "binding")) {
            val graph = graph("gui_delete_foreign_$kind")
            try {
                graph.api.settings.setStatisticsEnabled(true).requiredValue()
                graph.api.callers.saveProfile(SaveCallerProfile("31234", "keep", "unit", null)).requiredValue()
                val original = graph.recordings.registrations.registerIncoming("original", "31234", 1, null, null)
                val service = deletion(graph, recordingDelete = { id, generation, cmd ->
                    val result = graph.recordings.service.delete(id, generation, cmd)
                    when (kind) {
                        "archive" -> graph.recordings.registrations.registerIncoming("new", "31234", 2, null, null)
                        "profile" -> graph.api.callers.saveProfile(SaveCallerProfile("31234", "foreign", "unit", null)).requiredValue()
                        "call" -> graph.backend.recordIncoming("31234", graph.configuration.clock.millis() + 1, CallDecision.NORMAL_RINGING)
                        "binding" -> graph.api.widgets.saveBinding(7, "31234").requiredValue()
                    }
                    result
                })
                assertTrue(kind, service.delete(DeleteNumberCommand("31234", 0, 1, 0)) is ApiResult.Failure)
                assertNull(graph.recordings.store.get(original.id))
                assertTrue(graph.api.callers.numberDataImpact("31234").requiredValue().profileExists)
                when (kind) {
                    "archive" -> assertEquals(1, graph.recordings.service.numberArchives("31234").requiredValue().size)
                    "profile" -> assertEquals("foreign", graph.api.callers.getEditor("31234").requiredValue().draft.name)
                    "call" -> assertEquals(1, graph.api.callers.numberDataImpact("31234").requiredValue().callCount)
                    "binding" -> assertNotNull(graph.api.widgets.getBinding(7).requiredValue())
                }
            } finally { graph.close() }
        }
    }
    @Test fun oneThousandUnrelatedArchiveAndBindingReadRange() = accessRange(1000)
    @Test fun tenThousandUnrelatedArchiveAndBindingReadRange() = accessRange(10000)
    private fun accessRange(size: Int): Unit = runBlocking {
        val graph = graph("delete_range_$size")
        try {
            val context = RuntimeEnvironment.getApplication()
            graph.recordings.store.scopedReads()
            val dbPath = context.getDatabasePath(graph.configuration.databaseName + "_recording.db")
            android.database.sqlite.SQLiteDatabase.openDatabase(dbPath.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.beginTransaction()
                try {
                    val index = com.greatwall.callring.platform.ai.ArchiveProjectionIndex(db)
                    for (id in 1L..size.toLong()) {
                        val row = CallArchive(id, "39999", 1, generation = 2, status = ArchiveStatus.COMPLETE)
                        db.execSQL("INSERT INTO archives VALUES(?,?,?)", arrayOf<Any>(id, 2, com.greatwall.callring.platform.ai.RecordingJson.archive(row).toString()))
                        index.write(row)
                    }
                    val audio = File(graph.recordings.directory, "target.wav").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(73)) }
                    val row = CallArchive(-1, "3\u200B1[2]3/4", 10, generation = 2, status = ArchiveStatus.COMPLETE,
                        audioPath = audio.path, transcript = "unrelated text".repeat(100000))
                    db.execSQL("INSERT INTO archives VALUES(?,?,?)", arrayOf<Any>(-1, 2, com.greatwall.callring.platform.ai.RecordingJson.archive(row).toString()))
                    index.write(row)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            val stored = context.getSharedPreferences(graph.configuration.widgetPreferencesName, 0)
            stored.edit().apply {
                for (id in 1..size) putString("number_$id", "39999")
                putString("number_${size + 1}", "3\u200B1[2]3/4")
                putString("number_${size + 2}", "١/٢/٣")
            }.commit()
            var enumerated = 0L
            var enumerations = 0L
            val measured = java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader,
                arrayOf(android.content.SharedPreferences::class.java)) { _, method, arguments ->
                val value = method.invoke(stored, *(arguments ?: emptyArray()))
                if (method.name == "getAll") { enumerations++; enumerated += (value as Map<*, *>).size }
                value
            } as android.content.SharedPreferences
            val preferences = com.greatwall.callring.platform.AndroidPreferences(stored, measured, graph.configuration.defaultAppearance)
            val widgets = com.greatwall.callring.application.widget.WidgetBindingsService(preferences,
                { error("installed widgets are not a deletion dependency") }, graph.coordinator, kotlinx.coroutines.Dispatchers.Unconfined)
            val service = NumberDeletionService(graph.backend.callers::numberDataImpact,
                { _, _, _ -> error("preview cannot write core") }, graph.recordings.service,
                widgets::bindingsForNumber, { _, _ -> error("preview cannot write widgets") }, graph.coordinator, active = { true })
            val impact = service.impact("31234").requiredValue()
            assertEquals(1, impact.archiveCount); assertEquals(73L, impact.audioBytes); assertEquals(1, impact.widgetBindingCount)
            assertEquals(1L, enumerations); assertEquals(size + 2L, enumerated)
            assertEquals(listOf(size + 2), preferences.bindingsForNumber("١٢٣").map { it.widgetId })
            val counts = graph.recordings.store.readCounters.snapshot()
            assertEquals(size + 1L, counts["warmNumberRows"])
            assertEquals(1L, counts["targetMetadataRows"])
            assertEquals(1L, counts["payloadMetadataRows"])
            assertEquals(0L, counts["fullArchiveDecodes"] ?: 0L)
            assertEquals(0L, counts["listArchiveDecodes"] ?: 0L)
            File("build/qa/layer-boundaries/t10b3-number-access-$size.json").apply {
                parentFile!!.mkdirs()
                writeText(org.json.JSONObject(counts + mapOf("storedBindingEntriesPerEnumeration" to (size + 2L),
                    "previewBindingEnumerations" to 1L, "installedWidgetReads" to 0L, "returnedBindings" to 1L)).toString(2))
            }
        } finally { graph.close() }
    }
    private fun targetBindings(graph: BackendGraph, number: String): ApiResult<List<WidgetBinding>> {
        val context = RuntimeEnvironment.getApplication()
        val preferences = com.greatwall.callring.platform.AndroidPreferences(
            context.getSharedPreferences(graph.configuration.appearancePreferencesName, 0),
            context.getSharedPreferences(graph.configuration.widgetPreferencesName, 0), graph.configuration.defaultAppearance)
        return ApiResult.Success(preferences.bindingsForNumber(number))
    }
    private fun deletion(graph: BackendGraph, command: CommandContext? = null,
        recordingDelete: suspend (Long, Long, CommandContext) -> ApiResult<Unit> = { id, generation, cmd -> graph.recordings.service.delete(id, generation, cmd) },
        bindingsRead: suspend (String) -> ApiResult<List<WidgetBinding>> = { targetBindings(graph, it) },
    ) = NumberDeletionService(graph.backend.callers::numberDataImpact,
        { number, count, cmd -> graph.backend.commandApi(cmd).callers.deleteNumberCore(number, count) },
        object : NumberDeletionRecordings {
            override suspend fun archives(number: String) = graph.recordings.service.numberArchives(number)
            override suspend fun delete(callId: Long, expectedGeneration: Long, command: CommandContext) = recordingDelete(callId, expectedGeneration, command)
        }, bindingsRead,
        { binding, cmd -> graph.backend.commandApi(cmd).widgets.removeBinding(binding.widgetId) }, graph.coordinator, command, active = { true })
    private fun graph(name: String): BackendGraph {
        val context = RuntimeEnvironment.getApplication()
        val configuration = BackendConfiguration(
            AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM),
            databaseName = "$name.db", ringPreferencesName = "${name}_ring",
            widgetPreferencesName = "${name}_widgets", appearancePreferencesName = "${name}_appearance")
        val notifications = object : UiNotificationEntryPoints {
            override fun home(requestCode: Int) = PendingIntent.getActivity(context, requestCode, Intent("fixture"), PendingIntent.FLAG_IMMUTABLE)
            override fun playback(preview: Boolean, stopAction: PendingIntent) = Notification()
        }
        return BackendGraph(context, configuration, notifications, object : IncomingCallUiSink {
            override fun show(request: IncomingCallUiRequest) = Unit
        })
    }

    @Test fun guardedDeletionRemovesArchivesAudioCoreAndMultipleBindings() = runBlocking {
        val graph = graph("delete_composite")
        try {
            graph.api.callers.saveProfile(SaveCallerProfile("31234", "name", "unit", null)).requiredValue()
            for (id in 1..2) graph.api.widgets.saveBinding(id, "31234").requiredValue()
            val call = graph.recordings.registrations.registerIncoming("session", "31234", 1, null, null)
            val old = graph.recordings.store.get(call.id)!!
            graph.recordings.directory.mkdirs()
            val audio = File(graph.recordings.directory, "fixture.wav").apply { writeBytes(byteArrayOf(1, 2)) }
            assertTrue(graph.recordings.store.compareAndSet(call.id, old.generation,
                old.copy(generation = old.generation + 1, audioPath = audio.path, status = ArchiveStatus.READY)))
            val context = CommandContext("delete", graph.coordinator.capture(), 1) { true }
            graph.api.commandApi(context).numberDeletion.delete(DeleteNumberCommand("31234", 0, 1, 2)).requiredValue()
            assertFalse(graph.api.callers.numberDataImpact("31234").requiredValue().profileExists)
            assertTrue(graph.api.widgets.listBindings().requiredValue().stored.isEmpty())
            assertTrue(graph.api.recordings.listArchives().requiredValue().isEmpty())
            assertFalse(audio.exists())
        } finally { graph.close() }
    }

    @Test fun archiveOnlyRemnantsDoNotRequireAProfile() = runBlocking {
        val graph = graph("delete_archive_only")
        try {
            graph.recordings.registrations.registerIncoming("session", "31234", 1, null, null)
            graph.api.numberDeletion.delete(DeleteNumberCommand("31234", 0, 1, 0)).requiredValue()
            assertTrue(graph.api.recordings.listArchives().requiredValue().isEmpty())
        } finally { graph.close() }
    }

    @Test fun failedAudioRemovalRetainsArchiveForRetry() = runBlocking {
        val graph = graph("delete_audio_failure")
        try {
            val call = graph.recordings.registrations.registerIncoming("session", "31234", 1, null, null)
            val old = graph.recordings.store.get(call.id)!!
            val audio = File(graph.recordings.directory, "blocked.wav").apply { mkdirs() }
            val child = File(audio, "obstruction").apply { writeText("fixture") }
            assertTrue(graph.recordings.store.compareAndSet(call.id, old.generation,
                old.copy(generation = old.generation + 1, audioPath = audio.path, status = ArchiveStatus.READY)))
            assertTrue(graph.api.recordings.delete(call.id) is ApiResult.Failure)
            assertNotNull(graph.recordings.store.get(call.id))
            assertTrue(child.delete())
            graph.api.recordings.delete(call.id).requiredValue()
            assertFalse(audio.exists())
            assertNull(graph.recordings.store.get(call.id))
        } finally { graph.close() }
    }
}
